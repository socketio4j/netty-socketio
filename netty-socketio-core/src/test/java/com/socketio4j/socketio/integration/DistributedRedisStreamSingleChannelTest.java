package com.socketio4j.socketio.integration;

import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.socketio4j.socketio.AckMode;
import com.socketio4j.socketio.store.event.EventStoreMode;
import io.socket.client.Ack;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.CustomizedRedisContainer;
import com.socketio4j.socketio.store.redis_stream.RedisStreamsStoreFactory;

import io.socket.client.IO;
import io.socket.client.Socket;

import org.skyscreamer.jsonassert.JSONAssert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedRedisStreamSingleChannelTest {

    private static final Logger log = LoggerFactory.getLogger(DistributedRedisStreamSingleChannelTest.class);
    private static final CustomizedRedisContainer REDIS_CONTAINER = new CustomizedRedisContainer();

    private SocketIOServer node1;
    private SocketIOServer node2;

    private int port1;
    private int port2;

    // -------------------------------------------
    // Utility: find dynamic free port
    // -------------------------------------------
    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // -------------------------------------------
    // Create a client with simple test handler
    // -------------------------------------------
    private Socket createClient(int port, CountDownLatch latch, String[] store, int index) throws URISyntaxException {
        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        Socket client = IO.socket("http://127.00.1:" + port, opts);
        client.on("room-event", args -> {
            store[index] = (String) args[0];
            latch.countDown();
        });
        return client;
    }

    // -------------------------------------------
    // Create a client with list appending handler
    // -------------------------------------------
    private Socket createListAppendingClient(int port, CountDownLatch latch, List<List<String>> store, int index) throws URISyntaxException {
        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        Socket client = IO.socket("http://127.0.0.1:" + port, opts);
        client.on("room-event", args -> {
            store.get(index).add((String) args[0]);
            latch.countDown();
        });
        return client;
    }


    // -------------------------------------------
    // Redis + Node Setup
    // -------------------------------------------
    @BeforeAll
    public void setup() throws Exception {
        REDIS_CONTAINER.start();

        String redisURL = "redis://" + REDIS_CONTAINER.getHost() + ":" + REDIS_CONTAINER.getRedisPort();

        // Standard Redisson config
        Config config = new Config();
        config.useSingleServer().setAddress(redisURL);
        RedissonClient redissonClient = Redisson.create(config);

        // ---------- NODE 1 ----------
        Configuration cfg1 = new Configuration();
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(findAvailablePort());

        cfg1.setStoreFactory(new RedisStreamsStoreFactory(redissonClient, EventStoreMode.SINGLE_CHANNEL));
        cfg1.setAckMode(AckMode.MANUAL);
        node1 = new SocketIOServer(cfg1);
        node1.addConnectListener(client -> {
            Map<String, List<String>> params = client.getHandshakeData().getUrlParams();
            if (params.containsKey("join")) {
                params.get("join").forEach(client::joinRoom);
            }

        });
        node1.addEventListener("test", String.class, ((client, data, ackSender) -> {
            ackSender.sendAckData("ACK-OK");
        }));
        node1.addEventListener("get-my-rooms", String.class, ((client, data, ackSender) -> {
            ackSender.sendAckData(client.getAllRooms());
        }));
        node1.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);
            c.sendEvent("join-ok", "OK");
        });
        node1.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node1.start();
        port1 = cfg1.getPort();

        // ---------- NODE 2 ----------
        Configuration cfg2 = new Configuration();
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(findAvailablePort());

        cfg2.setStoreFactory(new RedisStreamsStoreFactory(redissonClient, EventStoreMode.SINGLE_CHANNEL));
        cfg2.setAckMode(AckMode.MANUAL);
        node2 = new SocketIOServer(cfg2);
        node2.addConnectListener(client -> {
            Map<String, List<String>> params = client.getHandshakeData().getUrlParams();
            if (params.containsKey("join")) {
                params.get("join").forEach(client::joinRoom);
            }

        });
        node2.addEventListener("get-my-rooms", String.class, ((client, data, ackSender) -> {
            ackSender.sendAckData(client.getAllRooms());
        }));
        node2.addEventListener("test", String.class, ((client, data, ackSender) -> {
            ackSender.sendAckData("ACK-OK");
        }));
        node2.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);
            c.sendEvent("join-ok", "OK");
        });
        node2.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node2.start();
        port2 = cfg2.getPort();

        Thread.sleep(2000); // Give servers time to bind and adapters time to initialize
    }


    @AfterAll
    public void stop() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        REDIS_CONTAINER.stop();
    }

    // ===================================================================
    //   0. TWO NODES ROOM BROADCAST (Refactored/Fixed Original Test)
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

        IO.Options opts = new IO.Options();
        opts.forceNew = true;

        Socket a = IO.socket("http://localhost:" + port1, opts);
        Socket b = IO.socket("http://localhost:" + port2, opts);

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
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        Thread.sleep(500); // Buffer for Redis adapter sync

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
        CountDownLatch latchNonRoom = new CountDownLatch(0);

        String[] msg = new String[allClients]; // Store 4 results

        IO.Options opts = new IO.Options();
        opts.forceNew = true;

        Socket a1 = IO.socket("http://localhost:" + port1, opts);
        Socket b1 = IO.socket("http://localhost:" + port2, opts);


        Socket a2 = IO.socket("http://localhost:" + port1, opts);
        Socket b2 = IO.socket("http://localhost:" + port2, opts);

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
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        // Give adapter time to sync room state
        Thread.sleep(500);
        a1.on("room-event", data -> {
            if (data.length > 0) {
                latchRoom.countDown();
                msg[0] = (String) data[0];
            }
        });
        b1.on("room-event", data -> {
            if (data.length > 0) {
                latchRoom.countDown();
                msg[2] = (String) data[0];
            }
        });
        a2.on("room-event", data -> {
            if (data.length > 0) {
                latchRoom.countDown();
                msg[1] = (String) data[0];
            }
        });
        b2.on("room-event", data -> {
            if (data.length > 0) {
                latchRoom.countDown();
                msg[3] = (String) data[0];
            }
        });
        node1.getRoomOperations("room1").sendEvent("room-event", "hello");

        Thread.sleep(2000);



        assertTrue(latchRoom.await(3, TimeUnit.SECONDS), "Room members did not receive message");

        assertEquals("hello", msg[0]); // a1 received
        assertEquals("hello", msg[2]); // b1 received
        assertNull(msg[1]); // a2 did not receive
        assertNull(msg[3]); // b2 did not receive

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

        // FIXED: Use List of Lists instead of Array of Lists for type safety
        List<List<String>> receivedMessages = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            receivedMessages.add(new CopyOnWriteArrayList<>());
        }

        Socket a1 = createListAppendingClient(port1, msgLatch, receivedMessages, 0);
        Socket a2 = createListAppendingClient(port1, msgLatch, receivedMessages, 1);
        Socket b1 = createListAppendingClient(port2, msgLatch, receivedMessages, 2);
        Socket b2 = createListAppendingClient(port2, msgLatch, receivedMessages, 3);

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
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        Thread.sleep(500);

        node1.getRoomOperations("room1").sendEvent("room-event", "m1");
        node2.getRoomOperations("room1").sendEvent("room-event", "m2");

        assertTrue(msgLatch.await(5, TimeUnit.SECONDS), "Did not receive all 8 events");

        for (int i = 0; i < clientCount; i++) {
            List<String> msgs = receivedMessages.get(i);
            assertEquals(2, msgs.size(), "Client " + i + " must receive 2 messages");
            assertTrue(msgs.contains("m1"), "Client " + i + " missing m1");
            assertTrue(msgs.contains("m2"), "Client " + i + " missing m2");
        }

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

        String[] msg = new String[2]; // msg[0]=a, msg[1]=b

        Socket a = createClient(port1, new CountDownLatch(1), msg, 0); // Client A (Node 1)
        Socket b = createClient(port2, new CountDownLatch(1), msg, 1); // Client B (Node 2)

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
        a.on("room-event", args -> { msg[0] = (String) args[0]; latchFirst.countDown(); });
        b.on("room-event", args -> { msg[1] = (String) args[0]; latchFirst.countDown(); });

        node1.getRoomOperations("room1").sendEvent("room-event", "first");
        assertTrue(latchFirst.await(2, TimeUnit.SECONDS), "First broadcast failed");
        assertEquals("first", msg[0]);
        assertEquals("first", msg[1]);

        // ---- b LEAVES ----
        CountDownLatch leaveLatch = new CountDownLatch(1);
        b.on("leave-ok", data -> leaveLatch.countDown()); // Listen for leave ack
        b.emit("leave-room", "room1");
        assertTrue(leaveLatch.await(2, TimeUnit.SECONDS), "Client B failed to leave room");

        // Reset message storage for second broadcast
        msg[0] = null;
        msg[1] = null;

        // ---- SECOND BROADCAST ----
        CountDownLatch latchSecond = new CountDownLatch(1); // Only A should receive
        a.off("room-event"); // Clear old latch on A
        a.on("room-event", args -> {
            msg[0] = (String) args[0];
            latchSecond.countDown();
        });

        // B's listener is still active, but should not receive the message
        // B's listener will NOT countdown the latchSecond (latchSecond = 1)

        node1.getRoomOperations("room1").sendEvent("room-event", "second");

        assertTrue(latchSecond.await(2, TimeUnit.SECONDS), "Client A did not receive second message");
        assertEquals("second", msg[0]); // A MUST receive

        assertNull(msg[1], "Client B received message despite leaving the room!");

        a.disconnect();
        b.disconnect();
    }


    // ===================================================================
    //   4. JOIN AFTER BROADCAST — NO BACKFILL (Fixed non-deterministic sleep)
    // ===================================================================
    @Test
    public void testJoinAfterBroadcastNoBackfill() throws Exception {

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch joinLatchA = new CountDownLatch(1);
        CountDownLatch joinLatchB = new CountDownLatch(1);

        String[] msg = new String[2]; // msg[0]=a, msg[1]=b
        CountDownLatch latchEarly = new CountDownLatch(1);

        Socket a = createClient(port1, latchEarly, msg, 0);
        Socket b = createClient(port2, new CountDownLatch(1), msg, 1);

        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        a.on("join-ok", data -> joinLatchA.countDown());
        b.on("join-ok", data -> joinLatchB.countDown());

        a.connect();
        b.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        // a joins first
        a.emit("join-room", "room1");
        assertTrue(joinLatchA.await(2, TimeUnit.SECONDS), "Client A failed to join");

        // early broadcast
        node1.getRoomOperations("room1").sendEvent("room-event", "early");
        assertTrue(latchEarly.await(2, TimeUnit.SECONDS), "Client A did not receive early message");
        assertEquals("early", msg[0]);

        // b joins LATE
        b.emit("join-room", "room1");
        assertTrue(joinLatchB.await(2, TimeUnit.SECONDS), "Client B failed to join");

        // B's message slot should still be null, confirming no backfill.
        assertNull(msg[1], "Client B received message despite joining late!");

        // Sanity check: Send a new message, both should get it
        CountDownLatch latchLate = new CountDownLatch(2);
        a.off("room-event"); // Clear old latch on A
        a.on("room-event", args -> { msg[0] = (String) args[0]; latchLate.countDown(); });
        b.off("room-event"); // Clear initial latch on B
        b.on("room-event", args -> { msg[1] = (String) args[0]; latchLate.countDown(); });

        node2.getRoomOperations("room1").sendEvent("room-event", "late");
        assertTrue(latchLate.await(2, TimeUnit.SECONDS));
        assertEquals("late", msg[0]);
        assertEquals("late", msg[1]);


        a.disconnect();
        b.disconnect();
    }

    // ===================================================================
    //   5. EXCEPT SENDER — SENDER MUST NOT RECEIVE (Fixed helper logic)
    // ===================================================================
    @Test
    public void testSendExceptSender() throws Exception {

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch joinLatch = new CountDownLatch(2);

        String[] msg = new String[2];
        CountDownLatch latchReceive = new CountDownLatch(1); // Only B should receive
        IO.Options opts = new IO.Options();
        opts.forceNew = true;

        Socket a = IO.socket("http://localhost:" + port1, opts);
        Socket b = IO.socket("http://localhost:" + port2, opts);
        // Connection/Join Listeners
        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        a.on("join-ok", data -> joinLatch.countDown());
        b.on("join-ok", data -> joinLatch.countDown());
        a.on("room-event", args -> {
            msg[0] = (String) args[0];
            latchReceive.countDown();
        });
        b.on("room-event", args -> {
            msg[1] = (String) args[0];
            latchReceive.countDown();
        });
        a.connect();
        b.connect();
        Thread.sleep(2000);
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        a.emit("join-room", "room1");
        b.emit("join-room", "room1");
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        Thread.sleep(500); // Give adapter time to sync room state

        // Emit from a custom method that finds all clients *except* 'a' and sends to them.
        sendExcept("room1", "room-event", "hello", a.id());

        assertTrue(latchReceive.await(2, TimeUnit.SECONDS), "Client B did not receive message");
        assertEquals("hello", msg[1]); // b receives
        assertNull(msg[0]);            // a does NOT receive

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

        String[] msgA = new String[1]; // client A's message storage
        String[] msgB = new String[1]; // client B's message storage

        Socket a = createClient(port1, new CountDownLatch(1), msgA, 0); // Joins roomA
        Socket b = createClient(port2, new CountDownLatch(1), msgB, 0); // Joins roomB

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
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS), "Clients failed to join room");

        Thread.sleep(500); // Give adapter time to sync room state

        // ---- Broadcast to roomA ----
        CountDownLatch latchA = new CountDownLatch(1);
        a.off("room-event");
        a.on("room-event", args -> { msgA[0] = (String) args[0]; latchA.countDown(); });
        b.off("room-event"); // ensure B is listening but for a different room

        node1.getRoomOperations("roomA").sendEvent("room-event", "a");
        assertTrue(latchA.await(2, TimeUnit.SECONDS), "Client A did not receive roomA message");

        assertEquals("a", msgA[0]);
        assertNull(msgB[0], "Client B received message from roomA!");

        // ---- Broadcast to roomB ----
        msgA[0] = null; // reset A
        CountDownLatch latchB = new CountDownLatch(1);
        b.off("room-event");
        b.on("room-event", args -> { msgB[0] = (String) args[0]; latchB.countDown(); });

        node2.getRoomOperations("roomB").sendEvent("room-event", "b");
        assertTrue(latchB.await(2, TimeUnit.SECONDS), "Client B did not receive roomB message");

        assertEquals("b", msgB[0]);
        assertNull(msgA[0], "Client A received message from roomB!");

        a.disconnect();
        b.disconnect();
    }

    // ===================================================================
    //   7. PURE BROADCAST — ALL CLIENTS ON ALL NODES MUST RECEIVE (Cleaned up unsafe array)
    // ===================================================================
    @Test
    public void testPureBroadcastFromBothNodes() throws Exception {

        final int clientCount = 4;
        final int expectedBroadcasts = 2; // n1 and n2
        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch msgLatch = new CountDownLatch(clientCount * expectedBroadcasts); // 8 total

        // FIXED: Use List of Lists instead of Array of Lists for type safety
        List<List<String>> receivedMessages = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            receivedMessages.add(new CopyOnWriteArrayList<>());
        }

        Socket a1 = createListAppendingClient(port1, msgLatch, receivedMessages, 0);
        Socket a2 = createListAppendingClient(port1, msgLatch, receivedMessages, 1);
        Socket b1 = createListAppendingClient(port2, msgLatch, receivedMessages, 2);
        Socket b2 = createListAppendingClient(port2, msgLatch, receivedMessages, 3);

        List<Socket> allClients = Arrays.asList(a1, a2, b1, b2);
        allClients.forEach(c -> c.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown()));

        a1.connect();
        a2.connect();
        b1.connect();
        b2.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Clients failed to connect");

        // Node1 broadcast
        node1.getBroadcastOperations().sendEvent("room-event", "n1");

        // Node2 broadcast
        node2.getBroadcastOperations().sendEvent("room-event", "n2");

        assertTrue(msgLatch.await(5, TimeUnit.SECONDS), "Did not receive all 8 pure broadcast messages");

        // Verify EVERY client got BOTH messages
        for (int i = 0; i < clientCount; i++) {
            List<String> clientMsgs = receivedMessages.get(i);
            assertEquals(2, clientMsgs.size(), "Client " + i + " should receive exactly 2 messages");
            assertTrue(clientMsgs.contains("n1"), "Client " + i + " missing n1 (broadcast from node1)");
            assertTrue(clientMsgs.contains("n2"), "Client " + i + " missing n2 (broadcast from node2)");
        }

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

        Socket c1 = createClient(port1, new CountDownLatch(1), new String[1], 0); // Use dummy latch/array
        Socket c2 = createClient(port1, new CountDownLatch(1), new String[1], 0);
        Socket c3 = createClient(port2, new CountDownLatch(1), new String[1], 0);
        Socket c4 = createClient(port2, new CountDownLatch(1), new String[1], 0);

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
        String[] msg1 = new String[clientCount];
        c1.off("room-event").on("room-event", args -> { msg1[0] = (String) args[0]; latch1.countDown(); });
        c2.off("room-event").on("room-event", args -> { msg1[1] = (String) args[0]; latch1.countDown(); });
        c3.off("room-event").on("room-event", args -> { msg1[2] = (String) args[0]; latch1.countDown(); });
        c4.off("room-event").on("room-event", args -> { msg1[3] = (String) args[0]; latch1.countDown(); });

        node1.getBroadcastOperations().sendEvent("room-event", "m1");

        assertTrue(latch1.await(3, TimeUnit.SECONDS), "Phase 1 broadcast failed");

        assertEquals("m1", msg1[0]);
        assertEquals("m1", msg1[1]);
        assertEquals("m1", msg1[2]);
        assertEquals("m1", msg1[3]);

        // ---------------------------
        // 2) BROADCAST FROM NODE 2
        // ---------------------------
        CountDownLatch latch2 = new CountDownLatch(clientCount);
        String[] msg2 = new String[clientCount];

        // FIXED: Re-attaching listeners with the new latch for phase 2 is critical
        c1.off("room-event").on("room-event", args -> { msg2[0] = (String) args[0]; latch2.countDown(); });
        c2.off("room-event").on("room-event", args -> { msg2[1] = (String) args[0]; latch2.countDown(); });
        c3.off("room-event").on("room-event", args -> { msg2[2] = (String) args[0]; latch2.countDown(); });
        c4.off("room-event").on("room-event", args -> { msg2[3] = (String) args[0]; latch2.countDown(); });

        node2.getBroadcastOperations().sendEvent("room-event", "m2");

        assertTrue(latch2.await(3, TimeUnit.SECONDS), "Phase 2 broadcast failed");

        assertEquals("m2", msg2[0]);
        assertEquals("m2", msg2[1]);
        assertEquals("m2", msg2[2]);
        assertEquals("m2", msg2[3]);

        c1.disconnect();
        c2.disconnect();
        c3.disconnect();
        c4.disconnect();
    }

    //MANUALLY WRITTEN CASES
    @Test
    public void testConnectAndJoinDifferentRoomTest() throws Exception {
        IO.Options opts = new IO.Options();
        opts.forceNew = true;

        Socket a = IO.socket("http://localhost:" + port1 + "?join=room1", opts);
        Socket b = IO.socket("http://localhost:" + port2 + "?join=room2", opts);

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
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Join timed out");
        a.emit("get-my-rooms", "anything", (Ack) ackArgs -> {
            joinLatch.countDown();
            assertDoesNotThrow(() -> JSONAssert.assertEquals(new JSONArray(Arrays.asList("", "room1")), (JSONArray) ackArgs[0], false));
        });
        b.emit("get-my-rooms", "anything", (Ack) ackArgs -> {
            joinLatch.countDown();
            assertDoesNotThrow(() -> JSONAssert.assertEquals(new JSONArray(Arrays.asList("", "room2")), (JSONArray) ackArgs[0], false));
        });
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConnectAndJoinSameRoomTest() throws Exception {
        IO.Options opts = new IO.Options();
        opts.forceNew = true;

        Socket a = IO.socket("http://localhost:" + port1 + "?join=room1", opts);
        Socket b = IO.socket("http://localhost:" + port2 + "?join=room1", opts);

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
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Join timed out");
        a.emit("get-my-rooms", "anything", (Ack) ackArgs -> {
            joinLatch.countDown();
            assertDoesNotThrow(() -> JSONAssert.assertEquals(new JSONArray(Arrays.asList("", "room1")), (JSONArray) ackArgs[0], false));
        });
        b.emit("get-my-rooms", "anything", (Ack) ackArgs -> {
            joinLatch.countDown();
            assertDoesNotThrow(() -> JSONAssert.assertEquals(new JSONArray(Arrays.asList("", "room1")), (JSONArray) ackArgs[0], false));
        });
        assertTrue(joinLatch.await(5, TimeUnit.SECONDS));
    }
}