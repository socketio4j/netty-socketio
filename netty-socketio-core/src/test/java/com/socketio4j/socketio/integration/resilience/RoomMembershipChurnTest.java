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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-readiness test: High-Concurrency Room Membership Churn.
 *
 * <p>Verifies thread safety of namespace room collections when 20 clients concurrently
 * join and leave multiple rooms at high frequency while server broadcasts fire simultaneously.
 */
public class RoomMembershipChurnTest {

    private static final Logger log = LoggerFactory.getLogger(RoomMembershipChurnTest.class);

    private static final int CLIENT_COUNT = 20;
    private static final int CHURN_CYCLES = 15;
    private static final long TIMEOUT_SECS = 45L;

    private SocketIOServer server;
    private int port;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();

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
    @DisplayName("Room Churn Test: High-Frequency Concurrent Join/Leave under Broadcast Traffic")
    public void testHighFrequencyRoomChurn() throws Exception {
        port = findAvailablePort();

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setTransports(Transport.WEBSOCKET);

        server = new SocketIOServer(config);

        CopyOnWriteArrayList<String> churnFailures = new CopyOnWriteArrayList<>();
        AtomicInteger serverBroadcasts = new AtomicInteger(0);

        server.addEventListener("join-dynamic", String.class, new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String room, AckRequest ackSender) {
                client.joinRoom(room);
                if (ackSender.isAckRequested()) ackSender.sendAckData("JOINED");
            }
        });

        server.addEventListener("leave-dynamic", String.class, new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String room, AckRequest ackSender) {
                client.leaveRoom(room);
                if (ackSender.isAckRequested()) ackSender.sendAckData("LEFT");
            }
        });

        server.start();

        CountDownLatch connectLatch = new CountDownLatch(CLIENT_COUNT);

        for (int i = 0; i < CLIENT_COUNT; i++) {
            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.transports = new String[]{ "websocket" };

            Socket socket = IO.socket("http://127.0.0.1:" + port, opts);
            clients.add(socket);

            socket.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            socket.connect();
        }

        assertTrue(connectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Clients failed to connect");

        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_COUNT);
        CountDownLatch completionLatch = new CountDownLatch(CLIENT_COUNT);

        for (int i = 0; i < CLIENT_COUNT; i++) {
            final Socket socket = clients.get(i);
            final int clientIdx = i;

            executor.submit(() -> {
                try {
                    for (int cycle = 0; cycle < CHURN_CYCLES; cycle++) {
                        String roomName = "churn-room-" + (cycle % 5);

                        CountDownLatch joinAck = new CountDownLatch(1);
                        socket.emit("join-dynamic", new Object[]{ roomName }, new Ack() {
                            @Override
                            public void call(Object... args) { joinAck.countDown(); }
                        });
                        joinAck.await(5, TimeUnit.SECONDS);

                        // Broadcast while clients are in room
                        if (clientIdx == 0) {
                            server.getRoomOperations(roomName).sendEvent("churn-broadcast", "data-" + cycle);
                            serverBroadcasts.incrementAndGet();
                        }

                        CountDownLatch leaveAck = new CountDownLatch(1);
                        socket.emit("leave-dynamic", new Object[]{ roomName }, new Ack() {
                            @Override
                            public void call(Object... args) { leaveAck.countDown(); }
                        });
                        leaveAck.await(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    churnFailures.add("Churn error: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        executor.shutdown();
        boolean finished = completionLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS);

        assertTrue(finished, "Room churn threads timed out");
        assertTrue(churnFailures.isEmpty(), () -> "Churn failures detected: " + churnFailures);
        assertTrue(serverBroadcasts.get() > 0, "Server broadcasts should have executed");
    }
}
