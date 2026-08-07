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
import com.socketio4j.socketio.listener.DataListener;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-readiness test: Mid-Stream Transport Upgrade Resilience.
 *
 * <p>Verifies that clients starting on HTTP Polling can upgrade to WebSocket mid-stream
 * while continuous message traffic is actively in-flight, guaranteeing zero packet loss
 * and zero message duplication.
 */

public class TransportUpgradeIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(TransportUpgradeIsolationTest.class);

    private static final int CLIENT_COUNT = 10;
    private static final int MESSAGES_PER_CLIENT = 30;
    private static final int TOTAL_EXPECTED_MESSAGES = CLIENT_COUNT * MESSAGES_PER_CLIENT;
    private static final long TIMEOUT_SECS = 60L;

    private SocketIOServer server;
    private int port;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();

    public static class EchoMessage {
        private String cliCode;
        private String randomMsg;
        private int seq;

        public EchoMessage() {}

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
        private int seq;
        private String echoMsg;

        public AckResponse() {}

        public String getAck() { return ack; }
        public void setAck(String ack) { this.ack = ack; }

        public String getCliCode() { return cliCode; }
        public void setCliCode(String cliCode) { this.cliCode = cliCode; }

        public int getSeq() { return seq; }
        public void setSeq(int seq) { this.seq = seq; }

        public String getEchoMsg() { return echoMsg; }
        public void setEchoMsg(String echoMsg) { this.echoMsg = echoMsg; }
    }

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
            throw new RuntimeException("Could not find available TCP port", e);
        }
    }

    @Test
    @DisplayName("Mid-Stream Transport Upgrade: Polling to WebSocket under Traffic")
    public void testMidStreamTransportUpgrade() throws Exception {
        port = findAvailablePort();

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setAllowCustomRequests(true);

        server = new SocketIOServer(config);

        Map<UUID, String> sessionToCliCodeMap = new ConcurrentHashMap<>();
        AtomicInteger serverVerifiedCount = new AtomicInteger(0);
        AtomicInteger clientAckSuccessCount = new AtomicInteger(0);
        CopyOnWriteArrayList<String> upgradeFailures = new CopyOnWriteArrayList<>();

        server.addEventListener("register-client", String.class, new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String cliCode, AckRequest ackSender) {
                sessionToCliCodeMap.put(client.getSessionId(), cliCode);
                if (ackSender.isAckRequested()) {
                    ackSender.sendAckData("REGISTERED");
                }
            }
        });

        server.addEventListener("echo-upgrade", EchoMessage.class, new DataListener<EchoMessage>() {
            @Override
            public void onData(SocketIOClient client, EchoMessage data, AckRequest ackSender) {
                String expectedCode = sessionToCliCodeMap.get(client.getSessionId());
                if (expectedCode == null || !expectedCode.equals(data.getCliCode())) {
                    upgradeFailures.add("Cross-contamination during upgrade! Expected " + expectedCode + " got " + data.getCliCode());
                    return;
                }

                serverVerifiedCount.incrementAndGet();

                AckResponse response = new AckResponse();
                response.setAck("ACK");
                response.setCliCode(data.getCliCode());
                response.setSeq(data.getSeq());
                response.setEchoMsg(data.getRandomMsg());

                if (ackSender.isAckRequested()) {
                    ackSender.sendAckData(response);
                }
            }
        });

        server.start();

        CountDownLatch connectLatch = new CountDownLatch(CLIENT_COUNT);
        CountDownLatch registerLatch = new CountDownLatch(CLIENT_COUNT);

        // Start clients on polling with upgrade = true (allows mid-stream upgrade)
        for (int i = 0; i < CLIENT_COUNT; i++) {
            final String cliCode = "CLI-UPG-" + i;

            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.transports = new String[]{ "polling", "websocket" };
            opts.upgrade = true;

            Socket socket = IO.socket("http://127.0.0.1:" + port, opts);
            clients.add(socket);

            socket.on(Socket.EVENT_CONNECT, args -> {
                connectLatch.countDown();
                socket.emit("register-client", new Object[]{ cliCode }, new Ack() {
                    @Override
                    public void call(Object... ackArgs) {
                        registerLatch.countDown();
                    }
                });
            });

            socket.connect();
        }

        assertTrue(connectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Clients failed to connect");
        assertTrue(registerLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Clients failed to register");

        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_COUNT);
        CountDownLatch totalAckLatch = new CountDownLatch(TOTAL_EXPECTED_MESSAGES);
        CountDownLatch completionLatch = new CountDownLatch(CLIENT_COUNT);

        for (int i = 0; i < CLIENT_COUNT; i++) {
            final Socket clientSocket = clients.get(i);
            final String cliCode = "CLI-UPG-" + i;

            executor.submit(() -> {
                try {
                    for (int seq = 0; seq < MESSAGES_PER_CLIENT; seq++) {
                        String randomMsg = "MSG-" + UUID.randomUUID();
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("cliCode", cliCode);
                        payload.put("randomMsg", randomMsg);
                        payload.put("seq", seq);

                        final int currentSeq = seq;

                        clientSocket.emit("echo-upgrade", new Object[]{ payload }, new Ack() {
                            @Override
                            public void call(Object... ackArgs) {
                                try {
                                    if (ackArgs.length > 0) {
                                        JSONObject resp = (JSONObject) ackArgs[0];
                                        if (cliCode.equals(resp.optString("cliCode")) && currentSeq == resp.optInt("seq")) {
                                            clientAckSuccessCount.incrementAndGet();
                                        } else {
                                            upgradeFailures.add("ACK mismatch during upgrade: " + resp);
                                        }
                                    }
                                } finally {
                                    totalAckLatch.countDown();
                                }
                            }
                        });

                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    upgradeFailures.add("Thread exception: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        executor.shutdown();
        boolean threadsFinished = completionLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS);
        boolean acksFinished = totalAckLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS);

        assertTrue(threadsFinished, "Upgrade threads timed out");
        assertTrue(acksFinished, "Upgrade ACKs timed out");
        assertTrue(upgradeFailures.isEmpty(), () -> "Upgrade failures:\n" + String.join("\n", upgradeFailures));

        assertEquals(TOTAL_EXPECTED_MESSAGES, serverVerifiedCount.get());
        assertEquals(TOTAL_EXPECTED_MESSAGES, clientAckSuccessCount.get());
    }
}
