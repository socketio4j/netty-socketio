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
package com.socketio4j.socketio.integration;


import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author https://github.com/sanjomo
 * @date 11/12/25 3:53 pm
 */
public abstract class DistributedCommonTest {

    protected SocketIOServer node1;
    protected SocketIOServer node2;

    protected int port1;
    protected int port2;
    
    // ===================================================================
    //   0. TWO NODES ROOM BROADCAST
    // ===================================================================
    @Test
    public void testTwoNodesRoomBroadcast() throws Exception {
        final int clients = 2;
        final int broadcasts = 2;
        final int expectedTotalMsgs = clients * broadcasts;

        CountDownLatch connectLatch = new CountDownLatch(clients);
        CountDownLatch joinLatch = new CountDownLatch(clients);
        CountDownLatch msgLatch = new CountDownLatch(expectedTotalMsgs);

        List<String> aMsgs = new CopyOnWriteArrayList<>();
        List<String> bMsgs = new CopyOnWriteArrayList<>();

        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;

        Socket a = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket b = io.socket.client.IO.socket("http://localhost:" + port2, opts);

        // --- SETUP LISTENERS ---
        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());

        a.on("join-ok", data -> joinLatch.countDown());
        b.on("join-ok", data -> joinLatch.countDown());

        a.on("room-event", data -> {
            if (data.length > 0) {
                aMsgs.add((String) data[0]);
                msgLatch.countDown();
            }
        });

        b.on("room-event", data -> {
            if (data.length > 0) {
                bMsgs.add((String) data[0]);
                msgLatch.countDown();
            }
        });

        // --- EXECUTION ---
        a.connect();
        b.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        a.emit("join-room", "room1");
        b.emit("join-room", "room1");
        //Thread.sleep(3000);
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        //Thread.sleep(500); // Buffer for Redis adapter sync

        node1.getRoomOperations("room1").sendEvent("room-event", "m1");
        node2.getRoomOperations("room1").sendEvent("room-event", "m2");

        assertTrue(msgLatch.await(5, TimeUnit.SECONDS), "Did not receive all messages");

        // --- ASSERTIONS ---
        try {
            assertEquals(2, aMsgs.size());
            assertEquals(2, bMsgs.size());
            assertTrue(aMsgs.containsAll(Arrays.asList("m1", "m2")), "A missing m1/m2");
            assertTrue(bMsgs.containsAll(Arrays.asList("m1", "m2")), "B missing m1/m2");
        } finally {
            a.disconnect();
            b.disconnect();
        }
    }


    // ===================================================================
    //   1. MULTIPLE CLIENTS — ROOM MEMBERS RECEIVE, NON-MEMBERS DO NOT
    // ===================================================================
    @Test
    public void testRoomBroadcastMultipleClients() throws Exception {

        final int allClients = 4;
        CountDownLatch connectLatch = new CountDownLatch(allClients);
        CountDownLatch joinLatch = new CountDownLatch(2); // a1, b1 join

        CountDownLatch latchRoom = new CountDownLatch(2); // a1, b1 receive

        AtomicReferenceArray<String> msg =
                new AtomicReferenceArray<>(allClients);
        // Store 4 results

        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;

        Socket a1 = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket b1 = io.socket.client.IO.socket("http://localhost:" + port2, opts);


        Socket a2 = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket b2 = io.socket.client.IO.socket("http://localhost:" + port2, opts);

        // Connection listeners
        a1.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        a2.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b1.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b2.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());

        // Join listeners (only a1 and b1 care)
        a1.on("join-ok", data -> joinLatch.countDown());
        b1.on("join-ok", data -> joinLatch.countDown());


        a1.connect();
        a2.connect();
        b1.connect();
        b2.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "All clients failed to connect");

        a1.emit("join-room", "room1");
        b1.emit("join-room", "room1");
        awaitRoomSync("room1", 2);
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");
        CountDownLatch unexpectedLatch = new CountDownLatch(2);

        // Give adapter time to sync room state
        //Thread.sleep(500);
        a1.on("room-event", data -> {
            if (data.length > 0) {
                latchRoom.countDown();
                msg.set(0, (String) data[0]);
            }
        });
        b1.on("room-event", data -> {
            if (data.length > 0) {
                latchRoom.countDown();
                msg.set(2, (String) data[0]);
            }
        });
        a2.on("room-event", data -> {
            if (data.length > 0) {
                unexpectedLatch.countDown(); // Should not happen
                msg.set(1, (String) data[0]);
            }
        });
        b2.on("room-event", data -> {
            if (data.length > 0) {
                unexpectedLatch.countDown(); // Should not happen
                msg.set(3, (String) data[0]);
            }
        });
        node1.getRoomOperations("room1").sendEvent("room-event", "hello");

        //Thread.sleep(2000);



        assertTrue(latchRoom.await(3, TimeUnit.SECONDS), "Room members did not receive message");
        assertFalse(unexpectedLatch.await(256, TimeUnit.MILLISECONDS), "Non-room clients should not receive messages");
        assertEquals("hello", msg.get(0)); // a1 received
        assertEquals("hello", msg.get(2)); // b1 received
        assertNull(msg.get(1)); // a2 did not receive
        assertNull(msg.get(3)); // b2 did not receive

        a1.disconnect();
        a2.disconnect();
        b1.disconnect();
        b2.disconnect();
    }

    // ===================================================================
    //   2. BROADCAST FROM BOTH NODES (Cleaned up unsafe array)
    // ===================================================================
    @Test
    public void testRoomBroadcastFromBothNodes() throws Exception {
        final int clientCount = 4;
        final int expectedBroadcasts = 2; // m1 and m2
        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch joinLatch = new CountDownLatch(clientCount);
        CountDownLatch msgLatch = new CountDownLatch(clientCount * expectedBroadcasts); // 8 total

        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;

        Socket a1 = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket b1 = io.socket.client.IO.socket("http://localhost:" + port2, opts);


        Socket a2 = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket b2 = io.socket.client.IO.socket("http://localhost:" + port2, opts);

        Set<String> a1Data = ConcurrentHashMap.newKeySet();
        Set<String> a2Data = ConcurrentHashMap.newKeySet();
        Set<String> b1Data = ConcurrentHashMap.newKeySet();
        Set<String> b2Data = ConcurrentHashMap.newKeySet();


        a1.on("room-event", args -> {
            msgLatch.countDown();
            a1Data.add((String) args[0]);
        });
        a2.on("room-event", args -> {
            msgLatch.countDown();
            a2Data.add((String) args[0]);
        });
        b1.on("room-event", args -> {
            msgLatch.countDown();
            b1Data.add((String) args[0]);
        });
        b2.on("room-event", args -> {
            msgLatch.countDown();
            b2Data.add((String) args[0]);
        });

        // Add connection/join listeners
        List<Socket> allClients = Arrays.asList(a1, a2, b1, b2);
        allClients.forEach(c -> c.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown()));
        allClients.forEach(c -> c.on("join-ok", args -> joinLatch.countDown()));


        a1.connect();
        a2.connect();
        b1.connect();
        b2.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        a1.emit("join-room", "room1");
        a2.emit("join-room", "room1");
        b1.emit("join-room", "room1");
        b2.emit("join-room", "room1");
        awaitRoomSync("room1", 4);
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        node1.getRoomOperations("room1").sendEvent("room-event", "m1");
        node2.getRoomOperations("room1").sendEvent("room-event", "m2");

        //Thread.sleep(1000);

        assertTrue(msgLatch.await(5, TimeUnit.SECONDS), "Did not receive all 8 events");
        assertEquals(8, a1Data.size() + a2Data.size() + b1Data.size() + b2Data.size(), "Each client must receive 2 messages");
        Set<String> expected = new HashSet<>(Arrays.asList("m1", "m2"));
        assertEquals(expected, a1Data);
        assertEquals(expected, b1Data);
        assertEquals(expected, a2Data);
        assertEquals(expected, b2Data);
        a1.disconnect();
        a2.disconnect();
        b1.disconnect();
        b2.disconnect();
    }

    // ===================================================================
    //   3. LEAVE ROOM — MUST NOT RECEIVE (Fixed non-deterministic sleep)
    // ===================================================================
    @Test
    public void testRoomLeave() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch joinLatch = new CountDownLatch(2);

        AtomicReferenceArray<String> msg =
                new AtomicReferenceArray<>(2); // msg[0]=a, msg[1]=b

        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;

        Socket a = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket b = io.socket.client.IO.socket("http://localhost:" + port2, opts);

        // Connection/Join Listeners
        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        a.on("join-ok", data -> joinLatch.countDown());
        b.on("join-ok", data -> joinLatch.countDown());

        a.connect();
        b.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        a.emit("join-room", "room1");
        b.emit("join-room", "room1");
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        // ---- FIRST BROADCAST ----
        CountDownLatch latchFirst = new CountDownLatch(2);
        a.off("room-event");
        b.off("room-event");
        a.on("room-event", args -> {
            msg.set(0, (String) args[0]);
            latchFirst.countDown();
        });
        b.on("room-event", args -> {
            msg.set(1, (String) args[0]);
            latchFirst.countDown();
        });

        node1.getRoomOperations("room1").sendEvent("room-event", "first");
        assertTrue(latchFirst.await(2, TimeUnit.SECONDS), "First broadcast failed");
        assertEquals("first", msg.get(0));
        assertEquals("first", msg.get(1));

        // ---- b LEAVES ----
        CountDownLatch leaveLatch = new CountDownLatch(1);
        b.on("leave-ok", data -> leaveLatch.countDown()); // Listen for leave ack
        b.emit("leave-room", "room1");
        assertTrue(leaveLatch.await(2, TimeUnit.SECONDS), "Client B failed to leave room");

        // Reset message storage for second broadcast
        msg.set(0, null);
        msg.set(1, null);

        // ---- SECOND BROADCAST ----
        CountDownLatch latchSecond = new CountDownLatch(1); // Only A should receive
        a.off("room-event"); // Clear old latch on A
        a.on("room-event", args -> {
            msg.set(0, (String) args[0]);
            latchSecond.countDown();
        });

        // B's listener is still active, but should not receive the message
        // B's listener will NOT countdown the latchSecond (latchSecond = 1)

        node1.getRoomOperations("room1").sendEvent("room-event", "second");

        assertTrue(latchSecond.await(2, TimeUnit.SECONDS), "Client A did not receive second message");
        assertEquals("second", msg.get(0)); // A MUST receive

        assertNull(msg.get(1), "Client B received message despite leaving the room!");

        a.disconnect();
        b.disconnect();
    }


    // ===================================================================
    //   4. JOIN AFTER BROADCAST — NO BACKFILL (Fixed non-deterministic sleep)
    // ===================================================================
    @Test
    public void testJoinAfterBroadcastNoBackfill() throws Exception {

        String room = "room-" + UUID.randomUUID();

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch joinLatchA = new CountDownLatch(1);
        CountDownLatch joinLatchB = new CountDownLatch(1);

        CountDownLatch earlyLatch = new CountDownLatch(1);
        CountDownLatch lateLatch  = new CountDownLatch(2);
        AtomicReferenceArray<String> joinMsg =
                new AtomicReferenceArray<>(2);
        AtomicReferenceArray<String> roomMsg =
                new AtomicReferenceArray<>(2);


        IO.Options opts = new IO.Options();
        opts.forceNew = true;

        Socket a = IO.socket("http://localhost:" + port1, opts);
        Socket b = IO.socket("http://localhost:" + port2, opts);

        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());

        a.on("join-ok", args -> {
            joinMsg.set(0, (String) args[0]);
            joinLatchA.countDown();
        });

        b.on("join-ok", args -> {
            joinMsg.set(1, (String) args[0]);
            joinLatchB.countDown();
        });

        // ---- ROOM EVENT LISTENERS (NO off(), NO reuse)
        a.on("room-event", args -> {
            String v = (String) args[0];
            if ("early".equals(v)) {
                roomMsg.set(0, v);
                earlyLatch.countDown();
            } else if ("late".equals(v)) {
                roomMsg.set(0, v);
                lateLatch.countDown();
            }
        });

        b.on("room-event", args -> {
            String v = (String) args[0];
            if ("late".equals(v)) {
                roomMsg.set(1, v);
                lateLatch.countDown();
            }
        });

        // ---- CONNECT
        a.connect();
        b.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        // ---- A joins first
        a.emit("join-room", room);
        assertTrue(joinLatchA.await(2, TimeUnit.SECONDS));
        assertEquals("OK", joinMsg.get(0));

        // ---- EARLY broadcast
        node1.getRoomOperations(room).sendEvent("room-event", "early");
        assertTrue(earlyLatch.await(2, TimeUnit.SECONDS));
        assertEquals("early", roomMsg.get(0));
        assertNull(roomMsg.get(1));

        // ---- B joins late
        b.emit("join-room", room);
        assertTrue(joinLatchB.await(2, TimeUnit.SECONDS));

        // ---- WAIT FOR DISTRIBUTED ROOM SYNC
        awaitRoomSync(room, 2);

        // ---- LATE broadcast
        node2.getRoomOperations(room).sendEvent("room-event", "late");
        assertTrue(lateLatch.await(2, TimeUnit.SECONDS));

        assertEquals("late", roomMsg.get(0));
        assertEquals("late", roomMsg.get(1));

        a.disconnect();
        b.disconnect();
    }

    private void awaitRoomSync(String room, int expected)
            throws InterruptedException {

        long deadline = System.currentTimeMillis() + 5000;

        while (System.currentTimeMillis() < deadline) {
            int count =
                    node1.getRoomOperations(room).getClients().size() +
                            node2.getRoomOperations(room).getClients().size();

            if (count == expected) {
                return;
            }
            Thread.sleep(1);
        }
        fail("Room sync not completed for " + room);
    }



    // ===================================================================
    //   5. EXCEPT SENDER — SENDER MUST NOT RECEIVE (Fixed helper logic)
    // ===================================================================
    @Test
    public void testSendExceptSender() throws Exception {

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch joinLatch = new CountDownLatch(2);

        AtomicReferenceArray<String> msg =
                new AtomicReferenceArray<>(2);
        CountDownLatch latchReceive = new CountDownLatch(1); // Only B should receive
        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;

        Socket a = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket b = io.socket.client.IO.socket("http://localhost:" + port2, opts);
        // Connection/Join Listeners
        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        a.on("join-ok", data -> joinLatch.countDown());
        b.on("join-ok", data -> joinLatch.countDown());
        a.on("room-event", args -> {
            msg.set(0, (String) args[0]);
            latchReceive.countDown();
        });
        b.on("room-event", args -> {
            msg.set(1, (String) args[0]);
            latchReceive.countDown();
        });
        a.connect();
        b.connect();
        //Thread.sleep(2000);
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        a.emit("join-room", "room1");
        b.emit("join-room", "room1");
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        //Thread.sleep(500); // Give adapter time to sync room state

        // Emit from a custom method that finds all clients *except* 'a' and sends to them.
        sendExcept("room1", "room-event", "hello", a.id());

        assertTrue(latchReceive.await(2, TimeUnit.SECONDS), "Client B did not receive message");
        assertEquals("hello", msg.get(1)); // b receives
        assertNull(msg.get(0));            // a does NOT receive

        a.disconnect();
        b.disconnect();
    }
    // Helper method to send event except to a specific sender ID
    private void sendExcept(String room, String event, String data, String senderId) {
        // Must check both nodes to ensure the distributed room list is correctly queried
        for (SocketIOServer s : Arrays.asList(node1, node2)) {
            for (SocketIOClient c : s.getRoomOperations(room).getClients()) {
                if (!c.getSessionId().toString().equals(senderId)) {
                    // Send directly to the client's session
                    c.sendEvent(event, data);
                }
            }
        }
    }


    // ===================================================================
    //   6. MULTIPLE ROOMS — NO CROSS TALK (Fixed non-deterministic sleep)
    // ===================================================================
    @Test
    public void testMultipleRoomsNoLeakage() throws Exception {

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch joinLatch = new CountDownLatch(2);

        AtomicReferenceArray<String> msgA =
                new AtomicReferenceArray<>(2);// client A's message storage
        AtomicReferenceArray<String> msgB =
                new AtomicReferenceArray<>(2); // client B's message storage

        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;

        Socket a = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket b = io.socket.client.IO.socket("http://localhost:" + port2, opts);

        // Connection/Join Listeners
        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        a.on("join-ok", data -> joinLatch.countDown());
        b.on("join-ok", data -> joinLatch.countDown());


        a.connect();
        b.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        a.emit("join-room", "roomA");
        b.emit("join-room", "roomB");
        awaitRoomSync("roomA", 1);
        awaitRoomSync("roomB", 1);
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        //Thread.sleep(500); // Give adapter time to sync room state

        // ---- Broadcast to roomA ----
        CountDownLatch latchA = new CountDownLatch(1);
        a.off("room-event");
        a.on("room-event", args -> {
            msgA.set(0, (String) args[0]);
            latchA.countDown();
        });
        b.off("room-event"); // ensure B is listening but for a different room

        node1.getRoomOperations("roomA").sendEvent("room-event", "a");
        assertTrue(latchA.await(2, TimeUnit.SECONDS), "Client A did not receive roomA message");

        assertEquals("a", msgA.get(0));
        assertNull(msgB.get(0), "Client B received message from roomA!");

        // ---- Broadcast to roomB ----
        msgA.set(0, null); // reset A
        CountDownLatch latchB = new CountDownLatch(1);
        b.off("room-event");
        b.on("room-event", args -> {
            msgB.set(0, (String) args[0]);
            latchB.countDown();
        });

        node2.getRoomOperations("roomB").sendEvent("room-event", "b");
        assertTrue(latchB.await(2, TimeUnit.SECONDS), "Client B did not receive roomB message");

        assertEquals("b", msgB.get(0));
        assertNull(msgA.get(0), "Client A received message from roomB!");

        a.disconnect();
        b.disconnect();
    }

    // ===================================================================
    //   7. PURE BROADCAST — ALL CLIENTS ON ALL NODES MUST RECEIVE (Cleaned up unsafe array)
    // ===================================================================
    @Test
    public void testPureBroadcastFromBothNodes() throws Exception {

        final int clientCount = 4;
        final int expectedBroadcasts = 2;

        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch joinLatch = new CountDownLatch(clientCount);
        CountDownLatch msgLatch =
                new CountDownLatch(clientCount * expectedBroadcasts); // 8

        IO.Options opts = new IO.Options();
        opts.forceNew = true;

        Socket a1 = IO.socket("http://localhost:" + port1, opts);
        Socket a2 = IO.socket("http://localhost:" + port1, opts);
        Socket b1 = IO.socket("http://localhost:" + port2, opts);
        Socket b2 = IO.socket("http://localhost:" + port2, opts);

        Set<String> a1Data = ConcurrentHashMap.newKeySet();
        Set<String> a2Data = ConcurrentHashMap.newKeySet();
        Set<String> b1Data = ConcurrentHashMap.newKeySet();
        Set<String> b2Data = ConcurrentHashMap.newKeySet();

        a1.on("room-event", args -> {
            a1Data.add((String) args[0]);
            msgLatch.countDown();
        });

        a2.on("room-event", args -> {
            a2Data.add((String) args[0]);
            msgLatch.countDown();
        });

        b1.on("room-event", args -> {
            b1Data.add((String) args[0]);
            msgLatch.countDown();
        });

        b2.on("room-event", args -> {
            b2Data.add((String) args[0]);
            msgLatch.countDown();
        });

        List<Socket> allClients = Arrays.asList(a1, a2, b1, b2);

        allClients.forEach(c ->
                c.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown())
        );

        allClients.forEach(c ->
                c.on("join-ok", args -> joinLatch.countDown())
        );

        a1.connect();
        a2.connect();
        b1.connect();
        b2.connect();

        assertTrue(connectLatch.await(5, TimeUnit.SECONDS),
                "Clients failed to connect");

        a1.emit("join-room", "room1");
        a2.emit("join-room", "room1");
        b1.emit("join-room", "room1");
        b2.emit("join-room", "room1");

        assertTrue(joinLatch.await(5, TimeUnit.SECONDS),
                "Clients failed to join room");

        // 🔒 Deterministic adapter barrier
        awaitRoomSync("room1", 4);
        Thread.sleep(200); // allow cross-node adapter propagation

        node1.getBroadcastOperations()
                .sendEvent("room-event", "m1");

        node2.getBroadcastOperations()
                .sendEvent("room-event", "m2");

        assertTrue(msgLatch.await(5, TimeUnit.SECONDS),
                "Did not receive all 8 events");
        assertEquals(8, a1Data.size() + a2Data.size() + b1Data.size() + b2Data.size(), "Each client must receive 2 messages");


        Set<String> expected = new HashSet<>(Arrays.asList("m1", "m2"));

        assertEquals(expected, a1Data, "a1 mismatch");
        assertEquals(expected, a2Data, "a2 mismatch");
        assertEquals(expected, b1Data, "b1 mismatch");
        assertEquals(expected, b2Data, "b2 mismatch");

        a1.disconnect();
        a2.disconnect();
        b1.disconnect();
        b2.disconnect();
    }


    // ===================================================================
    //   8) PURE BROADCAST — NODE1 THEN NODE2 — NO ROOMS (Cleaned up listener logic)
    // ===================================================================
    @Test
    public void testPureBroadcastFromNodes() throws Exception {
        final int clientCount = 4;
        CountDownLatch connectLatch = new CountDownLatch(clientCount);

        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;

        Socket c1 = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket c2 = io.socket.client.IO.socket("http://localhost:" + port1, opts);
        Socket c3 = io.socket.client.IO.socket("http://localhost:" + port2, opts);
        Socket c4 = io.socket.client.IO.socket("http://localhost:" + port2, opts);

        List<Socket> allClients = Arrays.asList(c1, c2, c3, c4);
        allClients.forEach(c -> c.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown()));

        c1.connect();
        c2.connect();
        c3.connect();
        c4.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        // ---------------------------
        // 1) BROADCAST FROM NODE 1
        // ---------------------------
        CountDownLatch latch1 = new CountDownLatch(clientCount);
        AtomicReferenceArray<String> msg1 =
                new AtomicReferenceArray<>(4);
        c1.off("room-event").on("room-event", args -> {
            msg1.set(0, (String) args[0]);
            latch1.countDown();
        });
        c2.off("room-event").on("room-event", args -> {
            msg1.set(1, (String) args[0]);
            latch1.countDown();
        });
        c3.off("room-event").on("room-event", args -> {
            msg1.set(2, (String) args[0]);
            latch1.countDown();
        });
        c4.off("room-event").on("room-event", args -> {
            msg1.set(3, (String) args[0]);
            latch1.countDown();
        });

        node1.getBroadcastOperations().sendEvent("room-event", "m1");

        assertTrue(latch1.await(5, TimeUnit.SECONDS), "Phase 1 broadcast failed");

        assertEquals("m1", msg1.get(0));
        assertEquals("m1", msg1.get(1));
        assertEquals("m1", msg1.get(2));
        assertEquals("m1", msg1.get(3));

        // ---------------------------
        // 2) BROADCAST FROM NODE 2
        // ---------------------------
        CountDownLatch latch2 = new CountDownLatch(clientCount);
        AtomicReferenceArray<String> msg2 =
                new AtomicReferenceArray<>(4);


        c1.off("room-event").on("room-event", args -> {
            msg2.set(0, (String) args[0]);
            latch2.countDown(); });
        c2.off("room-event").on("room-event", args -> {
            msg2.set(1, (String) args[0]);
            latch2.countDown();
        });
        c3.off("room-event").on("room-event", args -> {
            msg2.set(2, (String) args[0]);
            latch2.countDown();
        });
        c4.off("room-event").on("room-event", args -> {
            msg2.set(3, (String) args[0]);
            latch2.countDown();
        });

        node2.getBroadcastOperations().sendEvent("room-event", "m2");

        assertTrue(latch2.await(5, TimeUnit.SECONDS), "Phase 2 broadcast failed");

        assertEquals("m2", msg2.get(0));
        assertEquals("m2", msg2.get(1));
        assertEquals("m2", msg2.get(2));
        assertEquals("m2", msg2.get(3));

        c1.disconnect();
        c2.disconnect();
        c3.disconnect();
        c4.disconnect();
    }

    @Test
    public void testConnectAndJoinDifferentRoomTest() throws Exception {
        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;
        String room = "room-" + UUID.randomUUID();
        String room2 = "room-" + UUID.randomUUID();
        Socket a = io.socket.client.IO.socket("http://localhost:" + port1 + "?join="+room, opts);
        Socket b = io.socket.client.IO.socket("http://localhost:" + port2 + "?join="+room2, opts);


        CountDownLatch joinLatch = new CountDownLatch(2);
        CountDownLatch connectLatch = new CountDownLatch(2);
        a.on(Socket.EVENT_CONNECT, args -> {
            connectLatch.countDown();
        });
        b.on(Socket.EVENT_CONNECT, args -> {
            connectLatch.countDown();
        });
        a.connect();
        b.connect();
        awaitRoomSync(room, 1);
        awaitRoomSync(room2, 1);
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Join timed out");

        CompletableFuture<Void> f1 = new CompletableFuture<>();
        CompletableFuture<Void> f2 = new CompletableFuture<>();

        a.emit("get-my-rooms", "anything", (Ack) ackArgs -> {
            try {
                JSONAssert.assertEquals(
                        new JSONArray(Arrays.asList("", room)),
                        (JSONArray) ackArgs[0],
                        false
                );
                f1.complete(null);
                joinLatch.countDown();
            } catch (Exception t) {
                f1.completeExceptionally(t);
            }
        });

        b.emit("get-my-rooms", "anything", (Ack) ackArgs -> {
            try {
                JSONAssert.assertEquals(
                        new JSONArray(Arrays.asList("", room2)),
                        (JSONArray) ackArgs[0],
                        false
                );
                f2.complete(null);
                joinLatch.countDown();
            } catch (Exception t) {
                f2.completeExceptionally(t);
            }
        });

        assertDoesNotThrow(() ->
                CompletableFuture.allOf(f1, f2).get(5, TimeUnit.SECONDS)
        );

        assertTrue(joinLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConnectAndJoinSameRoomTest() throws Exception {
        io.socket.client.IO.Options opts = new io.socket.client.IO.Options();
        opts.forceNew = true;
        String room = "room-" + UUID.randomUUID();
        Socket a = io.socket.client.IO.socket("http://localhost:" + port1 + "?join=" + room, opts);
        Socket b = IO.socket("http://localhost:" + port2 + "?join=" + room, opts);

        a.connect();
        b.connect();
        CountDownLatch joinLatch = new CountDownLatch(2);
        CountDownLatch connectLatch = new CountDownLatch(2);
        a.on(Socket.EVENT_CONNECT, args -> {
            connectLatch.countDown();
        });
        b.on(Socket.EVENT_CONNECT, args -> {
            connectLatch.countDown();
        });
       awaitRoomSync(room, 2);
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Join timed out");
        CompletableFuture<Void> f1 = new CompletableFuture<>();
        CompletableFuture<Void> f2 = new CompletableFuture<>();

        a.emit("get-my-rooms", "anything", (Ack) ackArgs -> {
            try {
                JSONAssert.assertEquals(
                        new JSONArray(Arrays.asList("", room)),
                        (JSONArray) ackArgs[0],
                        false
                );
                f1.complete(null);
                joinLatch.countDown();
            } catch (Exception t) {
                f1.completeExceptionally(t);
            }
        });

        b.emit("get-my-rooms", "anything", (Ack) ackArgs -> {
            try {
                JSONAssert.assertEquals(
                        new JSONArray(Arrays.asList("", room)),
                        (JSONArray) ackArgs[0],
                        false
                );
                f2.complete(null);
                joinLatch.countDown();
            } catch (Exception t) {
                f2.completeExceptionally(t);
            }
        });

        assertDoesNotThrow(() ->
                CompletableFuture.allOf(f1, f2).get(5, TimeUnit.SECONDS)
        );

        assertTrue(joinLatch.await(5, TimeUnit.SECONDS));
    }
}
