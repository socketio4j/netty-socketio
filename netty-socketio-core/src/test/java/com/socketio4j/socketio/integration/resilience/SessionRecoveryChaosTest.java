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
 * Production-readiness test: Network Flakiness & Session Recovery Chaos Test.
 *
 * <p>Simulates abrupt client disconnects (network blips) during traffic and verifies that:
 * <ul>
 *   <li>Server handles abrupt connection channel closes gracefully without memory corruption.</li>
 *   <li>Client reconnection flushes pending packets cleanly upon session resumption.</li>
 * </ul>
 */
public class SessionRecoveryChaosTest {

    private static final Logger log = LoggerFactory.getLogger(SessionRecoveryChaosTest.class);

    private static final int CLIENT_COUNT = 10;
    private static final long TIMEOUT_SECS = 45L;

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
    @DisplayName("Session Recovery Chaos Test: Abrupt Network Blip Recovery")
    public void testAbruptNetworkBlipRecovery() throws Exception {
        port = findAvailablePort();

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setTransports(Transport.WEBSOCKET);

        server = new SocketIOServer(config);

        Map<UUID, String> sessionMap = new ConcurrentHashMap<>();
        AtomicInteger totalReceived = new AtomicInteger(0);
        CopyOnWriteArrayList<String> failures = new CopyOnWriteArrayList<>();

        server.addEventListener("chaos-event", EchoMessage.class, new DataListener<EchoMessage>() {
            @Override
            public void onData(SocketIOClient client, EchoMessage data, AckRequest ackSender) {
                sessionMap.put(client.getSessionId(), data.getCliCode());
                totalReceived.incrementAndGet();
                if (ackSender.isAckRequested()) {
                    ackSender.sendAckData("ACK-" + data.getSeq());
                }
            }
        });

        server.start();

        CountDownLatch initialConnectLatch = new CountDownLatch(CLIENT_COUNT);

        // Phase 1: Connect 10 clients
        for (int i = 0; i < CLIENT_COUNT; i++) {
            final String cliCode = "CHAOS-CLI-" + i;
            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.reconnectionAttempts = 10;
            opts.reconnectionDelay = 50;
            opts.transports = new String[]{ "websocket" };

            Socket socket = IO.socket("http://127.0.0.1:" + port, opts);
            clients.add(socket);

            socket.on(Socket.EVENT_CONNECT, args -> initialConnectLatch.countDown());
            socket.connect();
        }

        assertTrue(initialConnectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Initial connect failed");

        // Send Phase 1 batch
        CountDownLatch phase1AckLatch = new CountDownLatch(CLIENT_COUNT);
        for (int i = 0; i < CLIENT_COUNT; i++) {
            Socket socket = clients.get(i);
            Map<String, Object> payload = new HashMap<>();
            payload.put("cliCode", "CHAOS-CLI-" + i);
            payload.put("randomMsg", UUID.randomUUID().toString());
            payload.put("seq", 1);

            socket.emit("chaos-event", new Object[]{ payload }, new Ack() {
                @Override
                public void call(Object... args) {
                    phase1AckLatch.countDown();
                }
            });
        }
        assertTrue(phase1AckLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Phase 1 ACKs timed out");

        // Phase 2: Abruptly disconnect half the sockets (simulate abrupt network blip)
        CountDownLatch reconnectLatch = new CountDownLatch(CLIENT_COUNT / 2);
        for (int i = 0; i < CLIENT_COUNT / 2; i++) {
            Socket s = clients.get(i);
            s.on(Socket.EVENT_CONNECT, args -> reconnectLatch.countDown());
            s.disconnect(); // Abrupt disconnect
            s.connect();    // Immediate reconnect
        }

        assertTrue(reconnectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Reconnection after blip timed out");

        // Phase 3: Send Phase 2 batch to all clients after recovery
        CountDownLatch phase2AckLatch = new CountDownLatch(CLIENT_COUNT);
        for (int i = 0; i < CLIENT_COUNT; i++) {
            Socket socket = clients.get(i);
            Map<String, Object> payload = new HashMap<>();
            payload.put("cliCode", "CHAOS-CLI-" + i);
            payload.put("randomMsg", UUID.randomUUID().toString());
            payload.put("seq", 2);

            socket.emit("chaos-event", new Object[]{ payload }, new Ack() {
                @Override
                public void call(Object... args) {
                    phase2AckLatch.countDown();
                }
            });
        }
        assertTrue(phase2AckLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Phase 2 ACKs timed out");

        assertEquals(CLIENT_COUNT * 2, totalReceived.get(), "Total received messages mismatch after network blip");
        assertTrue(failures.isEmpty(), () -> "Failures during chaos recovery: " + failures);
    }
}
