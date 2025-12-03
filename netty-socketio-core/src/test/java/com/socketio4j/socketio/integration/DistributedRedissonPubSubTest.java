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

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.CustomizedRedisContainer;
import com.socketio4j.socketio.store.RedissonStoreFactory;
import com.socketio4j.socketio.store.SingleChannelRedisStreamsStoreFactory;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.config.Config;

import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedRedissonPubSubTest {

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
    // Create a client with test event handlers
    // -------------------------------------------
    private Socket createClient(int port, CountDownLatch latch, String[] store, int index) throws URISyntaxException {
        Socket client = IO.socket("http://127.0.0.1:" + port);
        client.on("room-event", args -> {
            store[index] = (String) args[0];
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

        // ---------- NODE 1 ----------
        Configuration cfg1 = new Configuration();
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(findAvailablePort());

        cfg1.setStoreFactory(new RedissonStoreFactory(
                Redisson.create(redisConfig(redisURL))
        ));

        node1 = new SocketIOServer(cfg1);
        node1.addEventListener("join-room", String.class, (c, room, ack) -> c.joinRoom(room));
        node1.addEventListener("leave-room", String.class, (c, room, ack) -> c.leaveRoom(room));
        node1.start();
        port1 = cfg1.getPort();

        // ---------- NODE 2 ----------
        Configuration cfg2 = new Configuration();
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(findAvailablePort());

        cfg2.setStoreFactory(new RedissonStoreFactory(
                Redisson.create(redisConfig(redisURL))
        ));

        node2 = new SocketIOServer(cfg2);
        node2.addEventListener("join-room", String.class, (c, room, ack) -> c.joinRoom(room));
        node2.addEventListener("leave-room", String.class, (c, room, ack) -> c.leaveRoom(room));
        node2.start();
        port2 = cfg2.getPort();

        Thread.sleep(600);
    }

    private Config redisConfig(String url) {
        Config c = new Config();
        c.useSingleServer().setAddress(url);
        return c;
    }

    @AfterAll
    public void stop() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        REDIS_CONTAINER.stop();
    }

    // ===================================================================
    //   1. MULTIPLE CLIENTS — ROOM MEMBERS RECEIVE, NON-MEMBERS DO NOT
    // ===================================================================
    @Test
    public void testRoomBroadcastMultipleClients() throws Exception {

        CountDownLatch latchRoom = new CountDownLatch(2);
        CountDownLatch latchNonRoom = new CountDownLatch(0);

        String[] msg = new String[4];

        Socket a1 = createClient(port1, latchRoom, msg, 0);  // room
        Socket a2 = createClient(port1, latchNonRoom, msg, 1); // not room
        Socket b1 = createClient(port2, latchRoom, msg, 2);  // room
        Socket b2 = createClient(port2, latchNonRoom, msg, 3); // not room

        a1.connect();
        a2.connect();
        b1.connect();
        b2.connect();
        Thread.sleep(300);

        a1.emit("join-room", "room1");
        b1.emit("join-room", "room1");
        Thread.sleep(300);

        node1.getRoomOperations("room1").sendEvent("room-event", "hello");

        assertTrue(latchRoom.await(3, TimeUnit.SECONDS));

        assertEquals("hello", msg[0]); // a1
        assertEquals("hello", msg[2]); // b1
        assertNull(msg[1]); // a2 no receive
        assertNull(msg[3]); // b2 no receive

        a1.disconnect();
        a2.disconnect();
        b1.disconnect();
        b2.disconnect();
    }

    // ===================================================================
    //   2. BROADCAST FROM BOTH NODES
    // ===================================================================
    @Test
    public void testRoomBroadcastFromBothNodes() throws Exception {

        CountDownLatch latch = new CountDownLatch(4);
        String[] msg = new String[4];

        Socket a1 = createClient(port1, latch, msg, 0);
        Socket a2 = createClient(port1, latch, msg, 1);
        Socket b1 = createClient(port2, latch, msg, 2);
        Socket b2 = createClient(port2, latch, msg, 3);

        a1.connect();
        a2.connect();
        b1.connect();
        b2.connect();
        Thread.sleep(300);

        a1.emit("join-room", "room1");
        a2.emit("join-room", "room1");
        b1.emit("join-room", "room1");
        b2.emit("join-room", "room1");
        Thread.sleep(300);

        node1.getRoomOperations("room1").sendEvent("room-event", "m1");
        node2.getRoomOperations("room1").sendEvent("room-event", "m2");

        assertTrue(latch.await(3, TimeUnit.SECONDS));

        for (String s : msg) {
            assertTrue(Arrays.asList("m1", "m2").contains(s));
        }

        a1.disconnect();
        a2.disconnect();
        b1.disconnect();
        b2.disconnect();
    }

    // ===================================================================
    //   3. LEAVE ROOM — MUST NOT RECEIVE
    // ===================================================================
    @Test
    public void testRoomLeave() throws Exception {

        CountDownLatch latchA = new CountDownLatch(1);

        String[] msg = new String[2]; // msg[0]=a, msg[1]=b

        Socket a = createClient(port1, latchA, msg, 0);
        Socket b = createClient(port2, new CountDownLatch(0), msg, 1);

        a.connect();
        b.connect();
        Thread.sleep(300);

        a.emit("join-room", "room1");
        b.emit("join-room", "room1");
        Thread.sleep(1000);  // ensure JOIN propagates

        // ---- FIRST BROADCAST ----
        node1.getRoomOperations("room1").sendEvent("room-event", "first");
        assertTrue(latchA.await(2, TimeUnit.SECONDS));
        Thread.sleep(1000);
        assertEquals("first", msg[0]);
        assertEquals("first", msg[1]);

        // ---- b LEAVES ----
        b.emit("leave-room", "room1");

        // reset b state
        msg[1] = null;
        clearListeners(b);

        Thread.sleep(1000);

        // ---- SECOND BROADCAST ----
        CountDownLatch latchAgain = new CountDownLatch(1);
        a.on("room-event", args -> {
            msg[0] = (String) args[0];
            latchAgain.countDown();
        });

        node1.getRoomOperations("room1").sendEvent("room-event", "second");

        assertTrue(latchAgain.await(2, TimeUnit.SECONDS));
        assertEquals("second", msg[0]);   // a MUST receive
        assertNull(msg[1]);               // b MUST NOT receive

        a.disconnect();
        b.disconnect();
    }
    private void clearListeners(Socket client) {
        client.off("room-event");
    }


    // ===================================================================
    //   4. JOIN AFTER BROADCAST — NO BACKFILL
    // ===================================================================
    @Test
    public void testJoinAfterBroadcastNoBackfill() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        String[] msg = new String[2];

        Socket a = createClient(port1, latch, msg, 0);
        Socket b = createClient(port2, latch, msg, 1);

        a.connect();
        b.connect();
        Thread.sleep(300);

        // a joins first
        a.emit("join-room", "room1");
        Thread.sleep(200);

        // early broadcast
        node1.getRoomOperations("room1").sendEvent("room-event", "early");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("early", msg[0]);
        Thread.sleep(300);

        // b joins LATE
        b.emit("join-room", "room1");
        Thread.sleep(300);

        assertNull(msg[1]); // must NOT backfill old messages

        a.disconnect();
        b.disconnect();
    }

    // ===================================================================
    //   5. EXCEPT SENDER — SENDER MUST NOT RECEIVE
    // ===================================================================
    @Test
    public void testSendExceptSender() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        String[] msg = new String[2];

        Socket a = createClient(port1, latch, msg, 0);
        Socket b = createClient(port2, latch, msg, 1);

        a.connect();
        b.connect();
        Thread.sleep(300);

        a.emit("join-room", "room1");
        b.emit("join-room", "room1");


        Thread.sleep(1000);

        sendExcept("room1", "room-event", "hello", a.id());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("hello", msg[1]);
        assertNull(msg[0]);

        a.disconnect();
        b.disconnect();
    }
    private void sendExcept(String room, String event, String data, String senderId) {
        // enough to check both nodes
        for (SocketIOServer s : Arrays.asList(node1, node2)) {
            for (SocketIOClient c : s.getRoomOperations(room).getClients()) {
                if (!c.getSessionId().toString().equals(senderId)) {
                    c.sendEvent(event, data);
                }
            }
        }
    }





    // ===================================================================
    //   6. MULTIPLE ROOMS — NO CROSS TALK
    // ===================================================================
    @Test
    public void testMultipleRoomsNoLeakage() throws Exception {

        CountDownLatch latchA = new CountDownLatch(1);
        CountDownLatch latchB = new CountDownLatch(1);

        String[] msgA = new String[1];
        String[] msgB = new String[1];

        Socket a = createClient(port1, latchA, msgA, 0);
        Socket b = createClient(port2, latchB, msgB, 0);

        a.connect();
        b.connect();
        Thread.sleep(300);

        a.emit("join-room", "roomA");
        b.emit("join-room", "roomB");
        Thread.sleep(200);

        // broadcast to roomA
        node1.getRoomOperations("roomA").sendEvent("room-event", "a");
        assertTrue(latchA.await(2, TimeUnit.SECONDS));

        assertEquals("a", msgA[0]);
        assertNull(msgB[0]);

        // broadcast to roomB
        msgA[0] = null;

        node2.getRoomOperations("roomB").sendEvent("room-event", "b");
        assertTrue(latchB.await(2, TimeUnit.SECONDS));

        assertEquals("b", msgB[0]);
        assertNull(msgA[0]);

        a.disconnect();
        b.disconnect();
    }
    // ===================================================================
//   7. PURE BROADCAST — ALL CLIENTS ON ALL NODES MUST RECEIVE
// ===================================================================
    @Test
    public void testPureBroadcastFromBothNodes() throws Exception {

        CountDownLatch latch = new CountDownLatch(4);
        String[] msg = new String[4];

        Socket a1 = createClient(port1, latch, msg, 0);
        Socket a2 = createClient(port1, latch, msg, 1);
        Socket b1 = createClient(port2, latch, msg, 2);
        Socket b2 = createClient(port2, latch, msg, 3);

        a1.connect();
        a2.connect();
        b1.connect();
        b2.connect();
        Thread.sleep(300);

        // Node1 broadcast
        node1.getBroadcastOperations().sendEvent("room-event", "n1");

        // Node2 broadcast
        node2.getBroadcastOperations().sendEvent("room-event", "n2");

        assertTrue(latch.await(3, TimeUnit.SECONDS));

        // All clients must receive either n1 or n2 (or both depending on race)
        for (String s : msg) {
            assertTrue(
                    Arrays.asList("n1", "n2").contains(s),
                    "Client must receive n1 or n2, but got: " + s
            );
        }

        a1.disconnect();
        a2.disconnect();
        b1.disconnect();
        b2.disconnect();
    }

    // ===================================================================
//   8) PURE BROADCAST — NODE1 THEN NODE2 — NO ROOMS
// ===================================================================
    @Test
    public void testPureBroadcastFromNodes() throws Exception {

        CountDownLatch latch1 = new CountDownLatch(4);
        String[] msg1 = new String[4];

        Socket c1 = createClient(port1, latch1, msg1, 0);
        Socket c2 = createClient(port1, latch1, msg1, 1);
        Socket c3 = createClient(port2, latch1, msg1, 2);
        Socket c4 = createClient(port2, latch1, msg1, 3);

        c1.connect();
        c2.connect();
        c3.connect();
        c4.connect();
        Thread.sleep(300);

        // ---------------------------
        // 1) BROADCAST FROM NODE 1
        // ---------------------------
        node1.getBroadcastOperations().sendEvent("room-event", "m1");

        assertTrue(latch1.await(3, TimeUnit.SECONDS));

        // Verify all clients got m1
        assertEquals("m1", msg1[0]);
        assertEquals("m1", msg1[1]);
        assertEquals("m1", msg1[2]);
        assertEquals("m1", msg1[3]);


        // ---------------------------
        // 2) BROADCAST FROM NODE 2
        // ---------------------------
        CountDownLatch latch2 = new CountDownLatch(4);
        String[] msg2 = new String[4];

        // re-attach listeners for second broadcast
        c1.on("room-event", args -> { msg2[0] = (String) args[0]; latch2.countDown(); });
        c2.on("room-event", args -> { msg2[1] = (String) args[0]; latch2.countDown(); });
        c3.on("room-event", args -> { msg2[2] = (String) args[0]; latch2.countDown(); });
        c4.on("room-event", args -> { msg2[3] = (String) args[0]; latch2.countDown(); });

        node2.getBroadcastOperations().sendEvent("room-event", "m2");

        assertTrue(latch2.await(3, TimeUnit.SECONDS));

        // Verify all clients got m2
        assertEquals("m2", msg2[0]);
        assertEquals("m2", msg2[1]);
        assertEquals("m2", msg2[2]);
        assertEquals("m2", msg2[3]);


        c1.disconnect();
        c2.disconnect();
        c3.disconnect();
        c4.disconnect();
    }



}
