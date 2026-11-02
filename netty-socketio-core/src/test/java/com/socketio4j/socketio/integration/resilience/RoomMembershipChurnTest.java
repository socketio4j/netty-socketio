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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.TestResourceCleanup;
import com.socketio4j.socketio.Transport;

import io.socket.client.IO;
import io.socket.client.Socket;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrent membership mutations with wire barriers and exact recipient assertions. */
public class RoomMembershipChurnTest {
    @Test
    public void testHighFrequencyRoomChurn() throws Exception {
        broadcastsReachExactlyTheMembersAfterConcurrentJoinAndLeave();
    }

    @Test
    void broadcastsReachExactlyTheMembersAfterConcurrentJoinAndLeave() throws Exception {
        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(0);
        config.setTransports(Transport.WEBSOCKET);
        SocketIOServer server = new SocketIOServer(config);
        List<Socket> clients = new ArrayList<>();
        List<List<String>> deliveries = new ArrayList<>();
        server.addEventListener("join-dynamic", String.class, (client, room, ack) -> {
            client.joinRoom(room);
            ack.sendAckData("JOINED");
        });
        server.addEventListener("leave-dynamic", String.class, (client, room, ack) -> {
            client.leaveRoom(room);
            ack.sendAckData("LEFT");
        });
        try {
            server.start();
            CountDownLatch connected = new CountDownLatch(20);
            for (int i = 0; i < 20; i++) {
                IO.Options options = new IO.Options();
                options.forceNew = true;
                options.reconnection = false;
                options.transports = new String[]{"websocket"};
                Socket client = IO.socket("http://127.0.0.1:" + config.getPort(), options);
                clients.add(client);
                List<String> received = new CopyOnWriteArrayList<>();
                deliveries.add(received);
                client.on("churn-broadcast", args -> received.add(String.valueOf(args[0])));
                client.on(Socket.EVENT_CONNECT, args -> connected.countDown());
                client.connect();
            }
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            for (int cycle = 0; cycle < 15; cycle++) {
                String room = "churn-room-" + (cycle % 5);
                // Emit all mutations before waiting: server event loops process the clients concurrently.
                List<Runnable> joins = new ArrayList<>();
                for (Socket client : clients) {
                    joins.add(emitCheckedAck(client, "join-dynamic", room, "JOINED"));
                }
                joins.forEach(Runnable::run);
                List<Runnable> leaves = new ArrayList<>();
                for (int i = 0; i < clients.size(); i += 2) {
                    leaves.add(emitCheckedAck(clients.get(i), "leave-dynamic", room, "LEFT"));
                }
                leaves.forEach(Runnable::run);
                CountDownLatch roundEnded = new CountDownLatch(clients.size());
                for (Socket client : clients) {
                    client.once("round-end", args -> roundEnded.countDown());
                }
                String token = "round-" + cycle;
                server.getRoomOperations(room).sendEvent("churn-broadcast", token);
                // Same outbound client queue: receipt proves all earlier broadcasts were consumed.
                server.getBroadcastOperations().sendEvent("round-end", token);
                assertTrue(roundEnded.await(5, TimeUnit.SECONDS), "Every client must observe the wire barrier");
                for (int i = 0; i < clients.size(); i++) {
                    assertEquals(i % 2 == 0 ? Collections.emptyList() : Collections.singletonList(token),
                            deliveries.get(i), "Wrong recipients, duplicates or out-of-order delivery in " + token);
                    deliveries.get(i).clear();
                }
                leaves.clear();
                for (int i = 1; i < clients.size(); i += 2) {
                    leaves.add(emitCheckedAck(clients.get(i), "leave-dynamic", room, "LEFT"));
                }
                leaves.forEach(Runnable::run);
                assertTrue(server.getAllClients().stream().noneMatch(client -> client.getAllRooms().contains(room)),
                        "Last leave must remove every membership before the next round");
            }
            clients.forEach(Socket::disconnect);
            await().atMost(5, TimeUnit.SECONDS).until(() -> server.getAllClients().isEmpty());
        } finally {
            List<TestResourceCleanup.ThrowingAction> cleanup = new ArrayList<>();
            for (Socket client : clients) {
                cleanup.add(() -> { client.off(); client.disconnect(); client.close(); });
            }
            cleanup.add(server::stop);
            TestResourceCleanup.runAll("room churn cleanup", cleanup.toArray(new TestResourceCleanup.ThrowingAction[0]));
        }
    }

    private static Runnable emitCheckedAck(Socket client, String event, String room, String expected) {
        CountDownLatch acked = new CountDownLatch(1);
        AtomicReference<Object[]> response = new AtomicReference<>();
        client.emit(event, new Object[]{room}, args -> { response.set(args); acked.countDown(); });
        return () -> {
            try {
                assertTrue(acked.await(5, TimeUnit.SECONDS), "Missing " + event + " ACK");
                assertArrayEquals(new Object[]{expected}, response.get());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for membership ACK", error);
            }
        };
    }
}
