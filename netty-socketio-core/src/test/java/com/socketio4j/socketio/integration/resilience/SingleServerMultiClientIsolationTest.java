/**
 * Copyright (c) 2025 The Socketio4j Project
 * Parent project : Copyright (c) 2012-2025 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.socketio4j.socketio.integration.resilience;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.listener.DataListener;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extremely rigorous evidence-based multi-client isolation test suite.
 *
 * <p>Validates strict client isolation under high-frequency continuous message traffic:
 * <ul>
 *   <li>25 concurrent Java Socket.IO clients, each with a unique client code (CLI-0 .. CLI-24).</li>
 *   <li>All 25 clients emit messages fully in parallel across 25 concurrent threads (2,500 total round-trips per test run).</li>
 *   <li>Transport upgrade disabled (polling-only mode and websocket-only mode tested separately).</li>
 *   <li>Each client continuously emits code + random message payload to the server.</li>
 *   <li>Server verifies that incoming session ID strictly matches the registered client code (zero cross-contamination).</li>
 *   <li>Server returns an ACK containing ACK status, client code, sequence number, and server random nonce.</li>
 *   <li>Client verifies that the ACK received contains its own exact client code, sequence, and echo message.</li>
 *   <li>Empirical evidence verified via atomic success counters and failure collection with a 120s timeout budget.</li>
 * </ul>
 */
public class SingleServerMultiClientIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(SingleServerMultiClientIsolationTest.class);

    private static final int CLIENT_COUNT = 25;
    private static final int MESSAGES_PER_CLIENT = 100;
    private static final int TOTAL_EXPECTED_MESSAGES = CLIENT_COUNT * MESSAGES_PER_CLIENT;
    private static final long TIMEOUT_SECS = 120L;

    private SocketIOServer server;
    private int port;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();

    // ── DTO Data Structures ───────────────────────────────────────────────────

    public static class EchoMessage {
        private String cliCode;
        private String randomMsg;
        private int seq;

        public EchoMessage() {}

        public EchoMessage(String cliCode, String randomMsg, int seq) {
            this.cliCode = cliCode;
            this.randomMsg = randomMsg;
            this.seq = seq;
        }

        public String getCliCode() { return cliCode; }
        public void setCliCode(String cliCode) { this.cliCode = cliCode; }

        public String getRandomMsg() { return randomMsg; }
        public void setRandomMsg(String randomMsg) { this.randomMsg = randomMsg; }

        public int getSeq() { return seq; }
        public void setSeq(int seq) { this.seq = seq; }
    }

    public static class AckResponse {
        private String ack;
        private String cliCode;
        private String serverNonce;
        private int seq;
        private String echoMsg;

        public AckResponse() {}

        public String getAck() { return ack; }
        public void setAck(String ack) { this.ack = ack; }

        public String getCliCode() { return cliCode; }
        public void setCliCode(String cliCode) { this.cliCode = cliCode; }

        public String getServerNonce() { return serverNonce; }
        public void setServerNonce(String serverNonce) { this.serverNonce = serverNonce; }

        public int getSeq() { return seq; }
        public void setSeq(int seq) { this.seq = seq; }

        public String getEchoMsg() { return echoMsg; }
        public void setEchoMsg(String echoMsg) { this.echoMsg = echoMsg; }
    }

    // ── Test Lifecycle ────────────────────────────────────────────────────────

    @AfterEach
    public void tearDown() {
        for (Socket socket : clients) {
            if (socket != null) {
                try {
                    socket.off();
                    socket.disconnect();
                } catch (Exception e) {
                    log.warn("Error disconnecting client: {}", e.getMessage());
                }
            }
        }
        clients.clear();

        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("Error stopping server: {}", e.getMessage());
            }
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find an available TCP port", e);
        }
    }

    // ── Test Cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rigorous Parallel Multi-Client Isolation Test: HTTP Polling (Upgrade Disabled)")
    public void testPollingTransportStrictIsolation() throws Exception {
        runMultiClientIsolationTest(Transport.POLLING);
    }

    @Test
    @DisplayName("Rigorous Parallel Multi-Client Isolation Test: WebSocket (Upgrade Disabled)")
    public void testWebSocketTransportStrictIsolation() throws Exception {
        runMultiClientIsolationTest(Transport.WEBSOCKET);
    }

    // ── Core Test Runner ──────────────────────────────────────────────────────

    private void runMultiClientIsolationTest(Transport targetTransport) throws Exception {
        port = findAvailablePort();

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setTransports(targetTransport);
        config.setAllowCustomRequests(true);

        server = new SocketIOServer(config);

        // Session-to-ClientCode mapping on server
        Map<UUID, String> sessionToCliCodeMap = new ConcurrentHashMap<>();
        AtomicInteger serverVerifiedCount = new AtomicInteger(0);
        AtomicInteger clientAckSuccessCount = new AtomicInteger(0);
        CopyOnWriteArrayList<String> isolationFailures = new CopyOnWriteArrayList<>();

        // Register client mapping handler
        server.addEventListener("register-client", String.class, new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String cliCode, AckRequest ackSender) {
                sessionToCliCodeMap.put(client.getSessionId(), cliCode);
                if (ackSender.isAckRequested()) {
                    ackSender.sendAckData("REGISTERED");
                }
            }
        });

        // Register main echo handler with strict isolation check
        server.addEventListener("echo-isolation", EchoMessage.class, new DataListener<EchoMessage>() {
            @Override
            public void onData(SocketIOClient client, EchoMessage data, AckRequest ackSender) {
                String expectedCode = sessionToCliCodeMap.get(client.getSessionId());
                if (expectedCode == null) {
                    isolationFailures.add("Server Error: Session " + client.getSessionId()
                            + " has no registered client code");
                    return;
                }

                // SERVER-SIDE VERIFICATION: Ensure incoming payload cliCode matches registered session
                if (!expectedCode.equals(data.getCliCode())) {
                    isolationFailures.add("SERVER CROSS-CONTAMINATION DETECTED! Session " + client.getSessionId()
                            + " mapped to " + expectedCode + " but received payload with cliCode " + data.getCliCode());
                    return;
                }

                serverVerifiedCount.incrementAndGet();

                // Build ACK response containing server nonce and client code
                AckResponse response = new AckResponse();
                response.setAck("ACK");
                response.setCliCode(data.getCliCode());
                response.setServerNonce(UUID.randomUUID().toString());
                response.setSeq(data.getSeq());
                response.setEchoMsg(data.getRandomMsg());

                if (ackSender.isAckRequested()) {
                    ackSender.sendAckData(response);
                }
            }
        });

        server.start();

        // Connect 10 Java clients
        CountDownLatch connectLatch = new CountDownLatch(CLIENT_COUNT);
        CountDownLatch registerLatch = new CountDownLatch(CLIENT_COUNT);

        String transportName = (targetTransport == Transport.POLLING) ? "polling" : "websocket";

        for (int i = 0; i < CLIENT_COUNT; i++) {
            final String cliCode = "CLI-" + i;

            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.reconnectionAttempts = 5;
            opts.reconnectionDelay = 100;
            opts.transports = new String[]{ transportName };
            opts.upgrade = false; // Disable transport upgrade explicitly

            Socket socket = IO.socket("http://127.0.0.1:" + port, opts);
            clients.add(socket);

            socket.on(Socket.EVENT_CONNECT, args -> {
                connectLatch.countDown();
                // Register client code with server
                socket.emit("register-client", new Object[]{ cliCode }, new Ack() {
                    @Override
                    public void call(Object... ackArgs) {
                        registerLatch.countDown();
                    }
                });
            });

            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                isolationFailures.add("Connect error for " + cliCode + ": " + (args.length > 0 ? args[0] : "unknown"));
            });

            socket.connect();
        }

        assertTrue(connectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS),
                "All " + CLIENT_COUNT + " clients failed to connect via " + transportName);
        assertTrue(registerLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS),
                "All " + CLIENT_COUNT + " clients failed to register with server");

        assertEquals(CLIENT_COUNT, sessionToCliCodeMap.size(),
                "Server session mapping size mismatch");

        // Concurrent parallel message generation across all 10 clients
        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_COUNT);
        CountDownLatch totalAckLatch = new CountDownLatch(TOTAL_EXPECTED_MESSAGES);
        CountDownLatch completionLatch = new CountDownLatch(CLIENT_COUNT);

        for (int i = 0; i < CLIENT_COUNT; i++) {
            final Socket clientSocket = clients.get(i);
            final String cliCode = "CLI-" + i;

            executor.submit(() -> {
                try {
                    for (int seq = 0; seq < MESSAGES_PER_CLIENT; seq++) {
                        String randomMsg = "MSG-" + UUID.randomUUID();
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("cliCode", cliCode);
                        payload.put("randomMsg", randomMsg);
                        payload.put("seq", seq);

                        final int currentSeq = seq;

                        clientSocket.emit("echo-isolation", new Object[]{ payload }, new Ack() {
                            @Override
                            public void call(Object... ackArgs) {
                                try {
                                    if (ackArgs.length == 0) {
                                        isolationFailures.add(cliCode + " seq " + currentSeq + ": Received empty ACK");
                                        return;
                                    }

                                    JSONObject resp = (JSONObject) ackArgs[0];
                                    String respCliCode = resp.optString("cliCode");
                                    String respAck = resp.optString("ack");
                                    int respSeq = resp.optInt("seq");
                                    String respEcho = resp.optString("echoMsg");
                                    String serverNonce = resp.optString("serverNonce");

                                    // CLIENT-SIDE VERIFICATION: Ensure ACK belongs exclusively to this client
                                    if (!cliCode.equals(respCliCode)) {
                                        isolationFailures.add("CLIENT CROSS-CONTAMINATION DETECTED! " + cliCode
                                                + " received ACK meant for " + respCliCode);
                                    } else if (!"ACK".equals(respAck)) {
                                        isolationFailures.add(cliCode + " seq " + currentSeq + ": Invalid ACK flag " + respAck);
                                    } else if (currentSeq != respSeq) {
                                        isolationFailures.add(cliCode + ": Sequence mismatch! Expected "
                                                + currentSeq + " but got " + respSeq);
                                    } else if (!randomMsg.equals(respEcho)) {
                                        isolationFailures.add(cliCode + " seq " + currentSeq + ": Echo message corrupted");
                                    } else if (serverNonce == null || serverNonce.isEmpty()) {
                                        isolationFailures.add(cliCode + " seq " + currentSeq + ": Missing server nonce");
                                    } else {
                                        clientAckSuccessCount.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    isolationFailures.add(cliCode + " seq " + currentSeq + ": ACK exception: " + e.getMessage());
                                } finally {
                                    totalAckLatch.countDown();
                                }
                            }
                        });

                        // Small 5ms pause between rapid message emits per thread to throttle HTTP polling queue
                        if (targetTransport == Transport.POLLING) {
                            Thread.sleep(5);
                        }
                    }
                } catch (Exception e) {
                    isolationFailures.add(cliCode + ": Execution thread exception: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        executor.shutdown();
        boolean threadsFinished = completionLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS);
        boolean acksFinished = totalAckLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS);

        // ── EMPIRICAL EVIDENCE ASSERTIONS ──────────────────────────────────────
        assertTrue(threadsFinished, "Test execution threads timed out");
        assertTrue(acksFinished, () -> "Timed out waiting for all " + TOTAL_EXPECTED_MESSAGES + " ACKs (received " + clientAckSuccessCount.get() + ")");
        assertTrue(isolationFailures.isEmpty(),
                () -> "Isolation failures detected (" + isolationFailures.size() + "):\n"
                        + String.join("\n", isolationFailures));

        assertEquals(TOTAL_EXPECTED_MESSAGES, serverVerifiedCount.get(),
                "Server verified message count mismatch");
        assertEquals(TOTAL_EXPECTED_MESSAGES, clientAckSuccessCount.get(),
                "Client ACK success count mismatch");
    }
}
