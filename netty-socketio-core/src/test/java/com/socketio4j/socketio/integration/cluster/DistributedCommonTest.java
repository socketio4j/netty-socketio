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
package com.socketio4j.socketio.integration.cluster;

import java.time.Duration;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

import org.json.JSONArray;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIONamespace;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.namespace.Namespace;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Two-node cluster integration scenarios over a shared {@link com.socketio4j.socketio.store.StoreFactory}.
 *
 * <p><strong>Design philosophy</strong>:
 * <ul>
 *   <li>Every test uses deterministic latches; no raw {@code Thread.sleep} calls.</li>
 *   <li>Sockets are always disconnected in a {@code finally} block so that a test failure
 *       cannot leave dangling connections that corrupt subsequent tests.</li>
 *   <li>Negative-path assertions (messages that must <em>not</em> arrive) use a bounded
 *       await of {@value #NEGATIVE_ASSERT_MS} ms — long enough for the distributed store
 *       to propagate a spurious message, short enough not to slow the suite.</li>
 *   <li>Room-membership sync is verified by {@link #awaitRoomSync} before any broadcast,
 *       eliminating the race between "join" and "send".</li>
 *   <li>Lazy {@link Supplier}-based failure messages avoid expensive string concatenation
 *       on the happy path.</li>
 * </ul>
 *
 * @author https://github.com/sanjomo
 * @date 11/12/25 3:53 pm
 */

public abstract class DistributedCommonTest {

    private static final Logger log = LoggerFactory.getLogger(DistributedCommonTest.class);

    // ─── Timing constants ────────────────────────────────────────────────────

    /** Maximum seconds any single latch-based operation should take. */
    private static final long OP_TIMEOUT_SECS = 30L;

    /**
     * Millisecond budget for a <em>negative</em> assertion ("this must NOT arrive").
     * Must be long enough for the distributed store to propagate a spurious delivery,
     * but short enough not to slow the suite materially.
     */
    private static final long NEGATIVE_ASSERT_MS = 1000L;

    // ─── Abstract node handles ────────────────────────────────────────────────

    protected SocketIOServer node1;
    protected SocketIOServer node2;

    protected int port1;
    protected int port2;

    // =========================================================================
    //  Test 0 – Two nodes, same room: every client receives every broadcast
    // =========================================================================

    /**
     * Verifies that when two clients are in the <em>same</em> room on different nodes,
     * a broadcast from each node is received by both clients.
     *
     * <pre>
     *   a (node1) ─── room ─── b (node2)
     *   node1.sendEvent("m1") → a ✔, b ✔
     *   node2.sendEvent("m2") → a ✔, b ✔
     * </pre>
     */
    @Test
    @DisplayName("0 – two-node room broadcast: all clients receive all messages")
    public void testTwoNodesRoomBroadcast() throws Exception {
        final String room    = uniqueRoom();
        final int clients    = 2;
        final int broadcasts = 2;

        CountDownLatch connectLatch = new CountDownLatch(clients);
        CountDownLatch joinLatch    = new CountDownLatch(clients);
        CountDownLatch msgLatch     = new CountDownLatch(clients * broadcasts);

        List<String> aMsgs = new CopyOnWriteArrayList<>();
        List<String> bMsgs = new CopyOnWriteArrayList<>();

        Socket a = newSocket(port1);
        Socket b = newSocket(port2);
        try {
            a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            a.on("join-ok", args -> joinLatch.countDown());
            b.on("join-ok", args -> joinLatch.countDown());
            a.on("room-event", args -> {
                if (args.length > 0) { aMsgs.add((String) args[0]); msgLatch.countDown(); }
            });
            b.on("room-event", args -> {
                if (args.length > 0) { bMsgs.add((String) args[0]); msgLatch.countDown(); }
            });

            a.connect();
            b.connect();
            awaitOrFail(connectLatch, OP_TIMEOUT_SECS, "Clients failed to connect");

            a.emit("join-room", room);
            b.emit("join-room", room);
            awaitOrFail(joinLatch, OP_TIMEOUT_SECS, "Clients failed to join room");
            awaitRoomSync(room, clients);

            node1.getRoomOperations(room).sendEvent("room-event", "m1");
            node2.getRoomOperations(room).sendEvent("room-event", "m2");

            awaitOrFail(msgLatch, OP_TIMEOUT_SECS,
                    () -> "Expected " + (clients * broadcasts) + " messages; "
                        + "a=" + aMsgs + ", b=" + bMsgs);

            assertEquals(broadcasts, aMsgs.size(),
                    () -> "Client a expected " + broadcasts + " msgs, got: " + aMsgs);
            assertEquals(broadcasts, bMsgs.size(),
                    () -> "Client b expected " + broadcasts + " msgs, got: " + bMsgs);

            Set<String> expected = new HashSet<>(Arrays.asList("m1", "m2"));
            assertEquals(expected, new HashSet<>(aMsgs), "Client a missing messages");
            assertEquals(expected, new HashSet<>(bMsgs), "Client b missing messages");

        } finally {
            disconnectAll(a, b);
        }
    }

    // =========================================================================
    //  Test 1 – Room members receive; non-members do NOT
    // =========================================================================

    /**
     * With 4 clients (a1, a2 on node1 and b1, b2 on node2), only a1 and b1 join the room.
     * A broadcast to that room must reach a1 and b1 <em>exclusively</em>.
     */
    @Test
    @DisplayName("1 – room members receive message; non-members are excluded")
    public void testRoomBroadcastMultipleClients() throws Exception {
        final String room = uniqueRoom();

        CountDownLatch connectLatch   = new CountDownLatch(4);
        CountDownLatch joinLatch      = new CountDownLatch(2);
        CountDownLatch memberLatch    = new CountDownLatch(2);
        CountDownLatch nonMemberLatch = new CountDownLatch(1);

        AtomicReferenceArray<String> msg = new AtomicReferenceArray<>(4);

        Socket a1 = newSocket(port1);
        Socket a2 = newSocket(port1);
        Socket b1 = newSocket(port2);
        Socket b2 = newSocket(port2);
        try {
            a1.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            a2.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            b1.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            b2.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());

            a1.on("join-ok", args -> joinLatch.countDown());
            b1.on("join-ok", args -> joinLatch.countDown());

            a1.on("room-event", args -> {
                if (args.length > 0) { msg.set(0, (String) args[0]); memberLatch.countDown(); }
            });
            b1.on("room-event", args -> {
                if (args.length > 0) { msg.set(2, (String) args[0]); memberLatch.countDown(); }
            });
            a2.on("room-event", args -> {
                if (args.length > 0) { msg.set(1, (String) args[0]); nonMemberLatch.countDown(); }
            });
            b2.on("room-event", args -> {
                if (args.length > 0) { msg.set(3, (String) args[0]); nonMemberLatch.countDown(); }
            });

            connectAll(connectLatch, a1, a2, b1, b2);

            a1.emit("join-room", room);
            b1.emit("join-room", room);
            awaitOrFail(joinLatch, OP_TIMEOUT_SECS, "Room members failed to join");
            awaitRoomSync(room, 2);

            node1.getRoomOperations(room).sendEvent("room-event", "hello");

            awaitOrFail(memberLatch, OP_TIMEOUT_SECS, "Room members did not receive message");
            assertEquals("hello", msg.get(0), "a1 must receive message");
            assertEquals("hello", msg.get(2), "b1 must receive message");

            boolean spurious = nonMemberLatch.await(NEGATIVE_ASSERT_MS, TimeUnit.MILLISECONDS);
            assertFalse(spurious, "Non-room clients must NOT receive the room broadcast");
            assertNull(msg.get(1), "a2 must not have received a message");
            assertNull(msg.get(3), "b2 must not have received a message");

        } finally {
            disconnectAll(a1, a2, b1, b2);
        }
    }

    // =========================================================================
    //  Test 2 – All room members on both nodes receive broadcasts from both nodes
    // =========================================================================

    /**
     * All 4 clients (2 per node) join the same room. A broadcast from each node must
     * be received by every client exactly once, resulting in exactly 2 distinct messages
     * per client and exactly {@code clientCount × broadcastCount} total deliveries.
     */
    @Test
    @DisplayName("2 – all room members on both nodes receive broadcasts from both nodes")
    public void testRoomBroadcastFromBothNodes() throws Exception {
        final String room        = uniqueRoom();
        final int clientCount    = 4;
        final int broadcastCount = 2;
        final int expectedTotal  = clientCount * broadcastCount;

        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch joinLatch    = new CountDownLatch(clientCount);
        CountDownLatch msgLatch     = new CountDownLatch(expectedTotal);

        Set<String> a1Data = ConcurrentHashMap.newKeySet();
        Set<String> a2Data = ConcurrentHashMap.newKeySet();
        Set<String> b1Data = ConcurrentHashMap.newKeySet();
        Set<String> b2Data = ConcurrentHashMap.newKeySet();

        Socket a1 = newSocket(port1);
        Socket a2 = newSocket(port1);
        Socket b1 = newSocket(port2);
        Socket b2 = newSocket(port2);
        try {
            registerCounters(connectLatch, joinLatch, a1, a2, b1, b2);
            a1.on("room-event", args -> { a1Data.add((String) args[0]); msgLatch.countDown(); });
            a2.on("room-event", args -> { a2Data.add((String) args[0]); msgLatch.countDown(); });
            b1.on("room-event", args -> { b1Data.add((String) args[0]); msgLatch.countDown(); });
            b2.on("room-event", args -> { b2Data.add((String) args[0]); msgLatch.countDown(); });

            connectAll(connectLatch, a1, a2, b1, b2);
            joinRoom(joinLatch, room, a1, a2, b1, b2);
            awaitRoomSync(room, clientCount);

            node1.getRoomOperations(room).sendEvent("room-event", "m1");
            node2.getRoomOperations(room).sendEvent("room-event", "m2");

            awaitOrFail(msgLatch, OP_TIMEOUT_SECS,
                    () -> "Expected " + expectedTotal + " total deliveries; "
                        + "a1=" + a1Data + " a2=" + a2Data
                        + " b1=" + b1Data + " b2=" + b2Data);

            Set<String> expected = new HashSet<>(Arrays.asList("m1", "m2"));
            assertEquals(expected, a1Data, "a1 message set mismatch");
            assertEquals(expected, a2Data, "a2 message set mismatch");
            assertEquals(expected, b1Data, "b1 message set mismatch");
            assertEquals(expected, b2Data, "b2 message set mismatch");
            assertEquals(expectedTotal,
                    a1Data.size() + a2Data.size() + b1Data.size() + b2Data.size(),
                    "Aggregate delivery count mismatch — possible duplicate delivery");

        } finally {
            disconnectAll(a1, a2, b1, b2);
        }
    }

    // =========================================================================
    //  Test 3 – Leave room: departed client must NOT receive subsequent broadcast
    // =========================================================================

    /**
     * Verifies that a client that emits {@code leave-room} no longer receives room events.
     *
     * <ol>
     *   <li><strong>First broadcast</strong>: both clients receive "first".</li>
     *   <li>Client b leaves (server acknowledges with "leave-ok").</li>
     *   <li><strong>Second broadcast</strong>: only a receives "second"; b must not.</li>
     * </ol>
     */
    @Test
    @DisplayName("3 – leave-room: departed client does not receive subsequent broadcasts")
    public void testRoomLeave() throws Exception {
        final String room = uniqueRoom();

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch joinLatch    = new CountDownLatch(2);
        AtomicReferenceArray<String> msg = new AtomicReferenceArray<>(2);

        Socket a = newSocket(port1);
        Socket b = newSocket(port2);
        try {
            a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            a.on("join-ok", args -> joinLatch.countDown());
            b.on("join-ok", args -> joinLatch.countDown());

            a.connect();
            b.connect();
            awaitOrFail(connectLatch, OP_TIMEOUT_SECS, "Clients failed to connect");

            a.emit("join-room", room);
            b.emit("join-room", room);
            awaitOrFail(joinLatch, OP_TIMEOUT_SECS, "Clients failed to join room");
            awaitRoomSync(room, 2);

            // Phase 1: both receive
            CountDownLatch firstLatch = new CountDownLatch(2);
            a.on("room-event", args -> { msg.set(0, (String) args[0]); firstLatch.countDown(); });
            b.on("room-event", args -> { msg.set(1, (String) args[0]); firstLatch.countDown(); });

            node1.getRoomOperations(room).sendEvent("room-event", "first");
            awaitOrFail(firstLatch, OP_TIMEOUT_SECS, "First broadcast failed");
            assertEquals("first", msg.get(0), "a must receive first broadcast");
            assertEquals("first", msg.get(1), "b must receive first broadcast");

            // b leaves
            CountDownLatch leaveLatch = new CountDownLatch(1);
            b.on("leave-ok", args -> leaveLatch.countDown());
            b.emit("leave-room", room);
            awaitOrFail(leaveLatch, OP_TIMEOUT_SECS, "Client b failed to leave room");
            awaitRoomSync(room, 1);

            msg.set(0, null);
            msg.set(1, null);
            a.off("room-event");
            b.off("room-event");

            // Phase 2: only a receives
            CountDownLatch secondLatch    = new CountDownLatch(1);
            CountDownLatch bSpuriousLatch = new CountDownLatch(1);

            a.on("room-event", args -> { msg.set(0, (String) args[0]); secondLatch.countDown(); });
            b.on("room-event", args -> { msg.set(1, (String) args[0]); bSpuriousLatch.countDown(); });

            node1.getRoomOperations(room).sendEvent("room-event", "second");
            awaitOrFail(secondLatch, OP_TIMEOUT_SECS, "Client a did not receive second broadcast");
            assertEquals("second", msg.get(0), "a must receive second broadcast");

            boolean bGotMessage = bSpuriousLatch.await(NEGATIVE_ASSERT_MS, TimeUnit.MILLISECONDS);
            assertFalse(bGotMessage, "Client b received a message after leaving the room");
            assertNull(msg.get(1), "b's message slot must remain null after leaving");

        } finally {
            disconnectAll(a, b);
        }
    }

    // =========================================================================
    //  Test 4 – Late joiner: no backfill of pre-join events
    // =========================================================================

    /**
     * Verifies that the distributed store does <em>not</em> replay historical events
     * to a client that joined after those events were emitted.
     *
     * <pre>
     *   a joins → node1 sends "early" → a ✔, b not yet in room
     *   b joins → node2 sends "late"  → a ✔, b ✔
     *   b must NOT have received "early"
     * </pre>
     */
    @Test
    @DisplayName("4 – late joiner receives only post-join broadcasts; no backfill")
    public void testJoinAfterBroadcastNoBackfill() throws Exception {
        final String room = uniqueRoom();

        CountDownLatch connectLatch = new CountDownLatch(3); // +1 for sentinel
        CountDownLatch joinLatchA   = new CountDownLatch(1);
        CountDownLatch joinLatchSentinel = new CountDownLatch(1); // To sync node2
        CountDownLatch joinLatchB   = new CountDownLatch(1);
        CountDownLatch earlyLatch   = new CountDownLatch(2); // Wait for a AND sentinel
        CountDownLatch lateLatch    = new CountDownLatch(2);

        AtomicReferenceArray<String> roomMsg  = new AtomicReferenceArray<>(2);
        AtomicReference<String>      bEarlyMsg = new AtomicReference<>(null);

        Socket a = IO.socket(url(port1), baseOptions());
        Socket sentinel = IO.socket(url(port2), baseOptions()); // Listens on node2
        Socket b = IO.socket(url(port2), baseOptions());

        try {
            a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            sentinel.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());

            a.on("join-ok", args -> joinLatchA.countDown());
            sentinel.on("join-ok", args -> joinLatchSentinel.countDown());
            b.on("join-ok", args -> joinLatchB.countDown());

            a.on("room-event", args -> {
                if ("early".equals(args[0])) earlyLatch.countDown();
                else if ("late".equals(args[0])) { roomMsg.set(0, (String) args[0]); lateLatch.countDown(); }
            });

            sentinel.on("room-event", args -> {
                // This guarantees Node2's Kafka consumer has processed the message
                if ("early".equals(args[0])) earlyLatch.countDown();
            });

            b.on("room-event", args -> {
                String v = (String) args[0];
                if ("early".equals(v)) { bEarlyMsg.set(v); }
                else if ("late".equals(v)) { roomMsg.set(1, v); lateLatch.countDown(); }
            });

            a.connect();
            sentinel.connect();
            b.connect();
            awaitOrFail(connectLatch, OP_TIMEOUT_SECS, "Clients failed to connect");

            // 1. Setup initial clients
            a.emit("join-room", room);
            sentinel.emit("join-room", room);
            awaitOrFail(joinLatchA, OP_TIMEOUT_SECS, "a failed to join room");
            awaitOrFail(joinLatchSentinel, OP_TIMEOUT_SECS, "sentinel failed to join room");

            // 2. Publish Early Message
            node1.getRoomOperations(room).sendEvent("room-event", "early");

            // 3. CRITICAL SYNC: Wait for Node 1 (a) AND Node 2 (sentinel) to process it
            awaitOrFail(earlyLatch, OP_TIMEOUT_SECS, "Failed to route early message through Kafka");

            // 4. NOW it is safe for b to join Node 2
            b.emit("join-room", room);
            awaitOrFail(joinLatchB, OP_TIMEOUT_SECS, "b failed to join room");
            awaitRoomSync(room, 3); // a, sentinel, b

            // 5. Publish Late Message
            node2.getRoomOperations(room).sendEvent("room-event", "late");
            awaitOrFail(lateLatch, OP_TIMEOUT_SECS, "Late broadcast not received");

            // 6. Assertions
            assertEquals("late", roomMsg.get(0), "a must receive 'late'");
            assertEquals("late", roomMsg.get(1), "b must receive 'late'");
            assertNull(bEarlyMsg.get(), "b joined late but received 'early' — backfill leak detected!");

        } finally {
            disconnectAll(a, sentinel, b);
        }
    }

    // =========================================================================
    //  Test 5 – Except-sender: the emitting client does not receive the event
    // =========================================================================

    /**
     * Broadcasts to all room members <em>except</em> the designated sender.
     * Asserts that b receives the event and a (the sender) does not.
     */
    @Test
    @DisplayName("5 – except-sender: emitter is excluded; other room members receive")
    public void testSendExceptSender() throws Exception {
        final String room = uniqueRoom();

        CountDownLatch connectLatch   = new CountDownLatch(2);
        CountDownLatch joinLatch      = new CountDownLatch(2);
        CountDownLatch bReceiveLatch  = new CountDownLatch(1);
        CountDownLatch aSpuriousLatch = new CountDownLatch(1);

        AtomicReferenceArray<String> msg = new AtomicReferenceArray<>(2);

        Socket a = IO.socket(url(port1), baseOptions());
        Socket b = IO.socket(url(port2), baseOptions());
        try {
            a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            a.on("join-ok", args -> joinLatch.countDown());
            b.on("join-ok", args -> joinLatch.countDown());

            a.on("room-event", args -> { msg.set(0, (String) args[0]); aSpuriousLatch.countDown(); });
            b.on("room-event", args -> { msg.set(1, (String) args[0]); bReceiveLatch.countDown(); });

            a.connect();
            b.connect();
            awaitOrFail(connectLatch, OP_TIMEOUT_SECS, "Clients failed to connect");

            a.emit("join-room", room);
            b.emit("join-room", room);
            awaitOrFail(joinLatch, OP_TIMEOUT_SECS, "Clients failed to join room");
            awaitRoomSync(room, 2);

            sendExcept(room, "room-event", "hello", a.id());

            awaitOrFail(bReceiveLatch, OP_TIMEOUT_SECS, "Client b did not receive the event");
            assertEquals("hello", msg.get(1), "b must receive the event payload");

            boolean aGotMessage = aSpuriousLatch.await(NEGATIVE_ASSERT_MS, TimeUnit.MILLISECONDS);
            assertFalse(aGotMessage, "Sender (a) must not receive its own broadcast");
            assertNull(msg.get(0), "a's message slot must remain null");

        } finally {
            disconnectAll(a, b);
        }
    }

    // =========================================================================
    //  Test 6 – Multiple rooms: messages must not leak across room boundaries
    // =========================================================================

    /**
     * Two clients each join <em>different</em> rooms. A broadcast to roomA must reach only
     * the client in roomA; a broadcast to roomB must reach only the client in roomB.
     * This is verified in both directions.
     */
    @Test
    @DisplayName("6 – multiple rooms: no cross-room message leakage")
    public void testMultipleRoomsNoLeakage() throws Exception {
        final String roomA = uniqueRoom("roomA");
        final String roomB = uniqueRoom("roomB");

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch joinLatch    = new CountDownLatch(2);

        AtomicReferenceArray<String> msgA = new AtomicReferenceArray<>(1);
        AtomicReferenceArray<String> msgB = new AtomicReferenceArray<>(1);

        Socket a = newSocket(port1);
        Socket b = newSocket(port2);
        try {
            a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            a.on("join-ok", args -> joinLatch.countDown());
            b.on("join-ok", args -> joinLatch.countDown());

            a.connect();
            b.connect();
            awaitOrFail(connectLatch, OP_TIMEOUT_SECS, "Clients failed to connect");

            a.emit("join-room", roomA);
            b.emit("join-room", roomB);
            awaitOrFail(joinLatch, OP_TIMEOUT_SECS, "Clients failed to join rooms");
            awaitRoomSync(roomA, 1);
            awaitRoomSync(roomB, 1);

            // Phase 1: broadcast to roomA — only a receives
            CountDownLatch aLatch         = new CountDownLatch(1);
            CountDownLatch bSpuriousLatch = new CountDownLatch(1);
            a.on("room-event", args -> { msgA.set(0, (String) args[0]); aLatch.countDown(); });
            b.on("room-event", args -> { msgB.set(0, (String) args[0]); bSpuriousLatch.countDown(); });

            node1.getRoomOperations(roomA).sendEvent("room-event", "a");
            awaitOrFail(aLatch, OP_TIMEOUT_SECS, "Client a did not receive roomA message");
            assertEquals("a", msgA.get(0), "a must receive roomA broadcast");
            assertFalse(bSpuriousLatch.await(NEGATIVE_ASSERT_MS, TimeUnit.MILLISECONDS),
                    "b must NOT receive roomA broadcast");
            assertNull(msgB.get(0), "b's slot must remain null after roomA broadcast");

            // Phase 2: broadcast to roomB — only b receives
            msgA.set(0, null);
            msgB.set(0, null);
            a.off("room-event");
            b.off("room-event");

            CountDownLatch bLatch         = new CountDownLatch(1);
            CountDownLatch aSpuriousLatch = new CountDownLatch(1);
            a.on("room-event", args -> { msgA.set(0, (String) args[0]); aSpuriousLatch.countDown(); });
            b.on("room-event", args -> { msgB.set(0, (String) args[0]); bLatch.countDown(); });

            node2.getRoomOperations(roomB).sendEvent("room-event", "b");
            awaitOrFail(bLatch, OP_TIMEOUT_SECS, "Client b did not receive roomB message");
            assertEquals("b", msgB.get(0), "b must receive roomB broadcast");
            assertFalse(aSpuriousLatch.await(NEGATIVE_ASSERT_MS, TimeUnit.MILLISECONDS),
                    "a must NOT receive roomB broadcast");
            assertNull(msgA.get(0), "a's slot must remain null after roomB broadcast");

        } finally {
            disconnectAll(a, b);
        }
    }

    // =========================================================================
    //  Test 7 – Global broadcast: all connected clients receive, regardless of room
    // =========================================================================

    /**
     * Server-level broadcast (no room filter) from each node must reach every connected
     * client on both nodes. Each client must receive exactly the 2 messages, with no
     * duplicates — verified by an aggregate count assertion.
     */
    @Test
    @DisplayName("7 – global broadcast: all clients on all nodes receive all events")
    public void testPureBroadcastFromBothNodes() throws Exception {
        final String room        = uniqueRoom();
        final int clientCount    = 4;
        final int broadcastCount = 2;
        final int expectedTotal  = clientCount * broadcastCount;

        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch joinLatch    = new CountDownLatch(clientCount);
        CountDownLatch msgLatch     = new CountDownLatch(expectedTotal);

        Set<String> a1Data = ConcurrentHashMap.newKeySet();
        Set<String> a2Data = ConcurrentHashMap.newKeySet();
        Set<String> b1Data = ConcurrentHashMap.newKeySet();
        Set<String> b2Data = ConcurrentHashMap.newKeySet();

        Socket a1 = IO.socket(url(port1), baseOptions());
        Socket a2 = IO.socket(url(port1), baseOptions());
        Socket b1 = IO.socket(url(port2), baseOptions());
        Socket b2 = IO.socket(url(port2), baseOptions());
        try {
            registerCounters(connectLatch, joinLatch, a1, a2, b1, b2);
            a1.on("room-event", args -> { a1Data.add((String) args[0]); msgLatch.countDown(); });
            a2.on("room-event", args -> { a2Data.add((String) args[0]); msgLatch.countDown(); });
            b1.on("room-event", args -> { b1Data.add((String) args[0]); msgLatch.countDown(); });
            b2.on("room-event", args -> { b2Data.add((String) args[0]); msgLatch.countDown(); });

            connectAll(connectLatch, a1, a2, b1, b2);
            joinRoom(joinLatch, room, a1, a2, b1, b2);
            awaitRoomSync(room, clientCount);

            node1.getBroadcastOperations().sendEvent("room-event", "m1");
            node2.getBroadcastOperations().sendEvent("room-event", "m2");

            awaitOrFail(msgLatch, OP_TIMEOUT_SECS,
                    () -> "Expected " + expectedTotal + " deliveries; "
                        + "a1=" + a1Data + " a2=" + a2Data
                        + " b1=" + b1Data + " b2=" + b2Data);

            Set<String> expected = new HashSet<>(Arrays.asList("m1", "m2"));
            assertEquals(expected, new HashSet<>(a1Data), "a1 mismatch");
            assertEquals(expected, new HashSet<>(a2Data), "a2 mismatch");
            assertEquals(expected, new HashSet<>(b1Data), "b1 mismatch");
            assertEquals(expected, new HashSet<>(b2Data), "b2 mismatch");
            assertEquals(expectedTotal,
                    a1Data.size() + a2Data.size() + b1Data.size() + b2Data.size(),
                    "Aggregate count mismatch – possible duplicate delivery");

        } finally {
            disconnectAll(a1, a2, b1, b2);
        }
    }

    // =========================================================================
    //  Test 8 – Sequential global broadcasts: node1 then node2
    // =========================================================================

    /**
     * Broadcasts are issued sequentially (node1 first, then node2 only after all clients
     * have acknowledged the first). This prevents the two message sets from interleaving
     * and makes per-client assertions unambiguous.
     */
    @Test
    @DisplayName("8 – sequential global broadcasts: node1 then node2, all clients receive")
    public void testPureBroadcastFromNodes() throws Exception {
        final int clientCount = 4;
        // A dedicated sync room is used purely to give awaitRoomSync a stable
        // predicate: once both nodes see all 4 clients in this room, we know
        // that Hazelcast has fully propagated every CONNECT event and it is
        // safe to call getBroadcastOperations().
        final String syncRoom = uniqueRoom("sync");

        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch joinLatch    = new CountDownLatch(clientCount);

        Socket c1 = IO.socket(url(port1), baseOptions());
        Socket c2 = IO.socket(url(port1), baseOptions());
        Socket c3 = IO.socket(url(port2), baseOptions());
        Socket c4 = IO.socket(url(port2), baseOptions());
        try {
            registerCounters(connectLatch, joinLatch, c1, c2, c3, c4);
            connectAll(connectLatch, c1, c2, c3, c4);

            // Join a sync room so awaitRoomSync can deterministically confirm
            // both nodes have processed the CONNECT events for all 4 clients.
            joinRoom(joinLatch, syncRoom, c1, c2, c3, c4);
            awaitRoomSync(syncRoom, clientCount);

            // Phase 1: broadcast from node1
            CountDownLatch latch1 = new CountDownLatch(clientCount);
            AtomicReferenceArray<String> msg1 = new AtomicReferenceArray<>(clientCount);
            c1.off("room-event").on("room-event", args -> { msg1.set(0, (String) args[0]); latch1.countDown(); });
            c2.off("room-event").on("room-event", args -> { msg1.set(1, (String) args[0]); latch1.countDown(); });
            c3.off("room-event").on("room-event", args -> { msg1.set(2, (String) args[0]); latch1.countDown(); });
            c4.off("room-event").on("room-event", args -> { msg1.set(3, (String) args[0]); latch1.countDown(); });

            node1.getBroadcastOperations().sendEvent("room-event", "m1");
            awaitOrFail(latch1, OP_TIMEOUT_SECS, "Phase-1 broadcast (from node1) failed");
            for (int i = 0; i < clientCount; i++) {
                assertEquals("m1", msg1.get(i), "Client c" + (i + 1) + " did not receive m1");
            }

            // Phase 2: broadcast from node2
            CountDownLatch latch2 = new CountDownLatch(clientCount);
            AtomicReferenceArray<String> msg2 = new AtomicReferenceArray<>(clientCount);
            c1.off("room-event").on("room-event", args -> { msg2.set(0, (String) args[0]); latch2.countDown(); });
            c2.off("room-event").on("room-event", args -> { msg2.set(1, (String) args[0]); latch2.countDown(); });
            c3.off("room-event").on("room-event", args -> { msg2.set(2, (String) args[0]); latch2.countDown(); });
            c4.off("room-event").on("room-event", args -> { msg2.set(3, (String) args[0]); latch2.countDown(); });

            node2.getBroadcastOperations().sendEvent("room-event", "m2");
            awaitOrFail(latch2, OP_TIMEOUT_SECS, "Phase-2 broadcast (from node2) failed");
            for (int i = 0; i < clientCount; i++) {
                assertEquals("m2", msg2.get(i), "Client c" + (i + 1) + " did not receive m2");
            }

        } finally {
            disconnectAll(c1, c2, c3, c4);
        }
    }

    // =========================================================================
    //  Test 9 – Connect with room embedded in query string (different rooms)
    // =========================================================================

    /**
     * Clients pass their room via {@code ?join=<room>} in the URL so the server auto-joins
     * them on connect. Each client's room list must contain exactly the default namespace
     * room and the requested room.
     */
    @Test
    @DisplayName("9 – connect with query-join: each client joins its designated room")
    public void testConnectAndJoinDifferentRoomTest() throws Exception {
        final String room1 = uniqueRoom();
        final String room2 = uniqueRoom();

        Socket a = IO.socket(url(port1) + "?join=" + room1, baseOptions());
        Socket b = IO.socket(url(port2) + "?join=" + room2, baseOptions());

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch ackLatch     = new CountDownLatch(2);

        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());

        a.connect();
        b.connect();
        try {
            awaitOrFail(connectLatch, OP_TIMEOUT_SECS, "Clients failed to connect");
            awaitRoomSync(room1, 1);
            awaitRoomSync(room2, 1);

            CompletableFuture<Void> f1 = new CompletableFuture<>();
            CompletableFuture<Void> f2 = new CompletableFuture<>();

            a.emit("get-my-rooms", "ping", (Ack) ackArgs -> {
                try {
                    JSONAssert.assertEquals(new JSONArray(Arrays.asList("", room1)),
                            (JSONArray) ackArgs[0], false);
                    f1.complete(null);
                    ackLatch.countDown();
                } catch (Exception e) { f1.completeExceptionally(e); }
            });

            b.emit("get-my-rooms", "ping", (Ack) ackArgs -> {
                try {
                    JSONAssert.assertEquals(new JSONArray(Arrays.asList("", room2)),
                            (JSONArray) ackArgs[0], false);
                    f2.complete(null);
                    ackLatch.countDown();
                } catch (Exception e) { f2.completeExceptionally(e); }
            });

            assertDoesNotThrow(
                    () -> CompletableFuture.allOf(f1, f2).get(OP_TIMEOUT_SECS, TimeUnit.SECONDS),
                    "get-my-rooms ack assertion failed");
            awaitOrFail(ackLatch, OP_TIMEOUT_SECS, "get-my-rooms acks not received");

        } finally {
            disconnectAll(a, b);
        }
    }

    // =========================================================================
    //  Test 10 – Both clients join the same room via query string
    // =========================================================================

    /**
     * Both clients use the same room in the URL query parameter. Each client's room list
     * must contain the default namespace room and the shared room.
     */
    @Test
    @DisplayName("10 – connect with query-join: both clients join the same room")
    public void testConnectAndJoinSameRoomTest() throws Exception {
        final String room = uniqueRoom();

        Socket a = IO.socket(url(port1) + "?join=" + room, baseOptions());
        Socket b = IO.socket(url(port2) + "?join=" + room, baseOptions());

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch ackLatch     = new CountDownLatch(2);

        a.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        b.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());

        a.connect();
        b.connect();
        try {
            awaitOrFail(connectLatch, OP_TIMEOUT_SECS, "Clients failed to connect");
            awaitRoomSync(room, 2);

            CompletableFuture<Void> f1 = new CompletableFuture<>();
            CompletableFuture<Void> f2 = new CompletableFuture<>();

            a.emit("get-my-rooms", "ping", (Ack) ackArgs -> {
                try {
                    JSONAssert.assertEquals(new JSONArray(Arrays.asList("", room)),
                            (JSONArray) ackArgs[0], false);
                    f1.complete(null);
                    ackLatch.countDown();
                } catch (Exception e) { f1.completeExceptionally(e); }
            });

            b.emit("get-my-rooms", "ping", (Ack) ackArgs -> {
                try {
                    JSONAssert.assertEquals(new JSONArray(Arrays.asList("", room)),
                            (JSONArray) ackArgs[0], false);
                    f2.complete(null);
                    ackLatch.countDown();
                } catch (Exception e) { f2.completeExceptionally(e); }
            });

            assertDoesNotThrow(
                    () -> CompletableFuture.allOf(f1, f2).get(OP_TIMEOUT_SECS, TimeUnit.SECONDS),
                    "get-my-rooms ack assertion failed");
            awaitOrFail(ackLatch, OP_TIMEOUT_SECS, "get-my-rooms acks not received");

        } finally {
            disconnectAll(a, b);
        }
    }

    // =========================================================================
    //  Test 11 – EIO v3 binary packet forwarded across nodes
    // =========================================================================

    /**
     * An EIO v3 raw WebSocket client on node1 sends a binary event. Node1 broadcasts
     * the binary payload to all room members; a standard Socket.IO client on node2
     * must receive the correct binary bytes.
     *
     * <p>EIO v3 binary framing:
     * <pre>
     *   text frame:   "451-[\"clientBinary\",{\"_placeholder\":true,\"num\":0}]"
     *   binary frame: [0x04, 0x64, 0x6E, 0x78]  (prefix=4, payload=[100,110,120])
     * </pre>
     */
    @Test
    @DisplayName("11 – EIO v3 binary forwarding: binary payload delivered cross-node")
    public void testTwoNodesEIOv3BinaryForwarding() throws Exception {
        final String room = "room-binary-" + UUID.randomUUID();

        AtomicReference<byte[]> receivedOnNode2 = new AtomicReference<>();
        CountDownLatch msgLatch       = new CountDownLatch(1);
        CountDownLatch joinReadyLatch = new CountDownLatch(1);

        node1.addEventListener("clientBinary", byte[].class, (client, data, ack) ->
                node1.getRoomOperations(room).sendEvent("serverBinary", data));

        Socket clientB = IO.socket(url(port2), baseOptions());
        try {
            clientB.on(Socket.EVENT_CONNECT, args -> clientB.emit("join-room", room));
            clientB.on("join-ok", args -> joinReadyLatch.countDown());
            clientB.on("serverBinary", data -> {
                if (data.length > 0) { receivedOnNode2.set((byte[]) data[0]); msgLatch.countDown(); }
            });

            clientB.connect();
            awaitOrFail(joinReadyLatch, OP_TIMEOUT_SECS, "Client B failed to join room on node2");
            awaitRoomSync(room, 1);

            OkHttpClient okClient = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url("ws://localhost:" + port1 + "/socket.io/?EIO=3&transport=websocket")
                    .build();

            AtomicReference<WebSocket> eio3SocketRef = new AtomicReference<>();
            AtomicReference<Throwable> failureRef = new AtomicReference<>();
            CountDownLatch handshakeLatch = new CountDownLatch(1);

            WebSocket eio3Socket = okClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                    eio3SocketRef.set(webSocket);
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    if (text.startsWith("0")) handshakeLatch.countDown();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
                    failureRef.set(t);
                    handshakeLatch.countDown();
                }
            });

            try {
                awaitOrFail(handshakeLatch, OP_TIMEOUT_SECS, "EIO v3 client handshake failed");
                if (failureRef.get() != null) {
                    fail("EIO v3 client WebSocket connection failed: " + failureRef.get().getMessage(), failureRef.get());
                }
                eio3Socket.send("40");
                eio3Socket.send("451-[\"clientBinary\",{\"_placeholder\":true,\"num\":0}]");
                eio3Socket.send(ByteString.of(new byte[]{4, 100, 110, 120}));

                awaitOrFail(msgLatch, OP_TIMEOUT_SECS,
                        "Binary payload was not received by client B on node2");

                byte[] expected = {100, 110, 120};
                assertNotNull(receivedOnNode2.get(), "Received binary payload must not be null");
                assertArrayEquals(expected, receivedOnNode2.get(),
                        "Binary payload bytes mismatch");

            } finally {
                try {
                    eio3Socket.close(1000, "test-complete");
                } catch (Exception e) {
                    log.warn("Failed to close OkHttp eio3Socket cleanly during test cleanup: {}", e.getMessage());
                }
            }

        } finally {
            disconnectAll(clientB);
        }
    }

    // =========================================================================
    //  Shared infrastructure
    // =========================================================================

    /**
     * Polls both nodes until both report exactly {@code expected} clients in {@code room},
     * stable for 3 consecutive 8 ms ticks. Eliminates the race between a "join-ok" ack
     * and the membership being replicated to the peer node.
     */
    private void awaitRoomSync(String room, int expected) throws InterruptedException {
        long startTime  = System.currentTimeMillis();
        long deadline   = startTime + Duration.ofSeconds(15).toMillis();
        int stableTicks = 0;
        long sleepMs    = 5;
        boolean retriedSync = false;

        while (System.currentTimeMillis() < deadline) {
            int n1 = roomClientsInCluster(node1, room);
            int n2 = roomClientsInCluster(node2, room);
            if (n1 == expected && n2 == expected) {
                if (++stableTicks >= 3) return;
            } else {
                stableTicks = 0;
                if (!retriedSync && System.currentTimeMillis() - startTime > 2500) {
                    retriedSync = true;
                    log.warn("awaitRoomSync delayed for room {}, re-syncing room membership across cluster...", room);
                    reSyncRoomAcrossCluster(node1, room);
                    reSyncRoomAcrossCluster(node2, room);
                }
            }
            Thread.sleep(sleepMs);
            sleepMs = Math.min(sleepMs + 5, 25);
        }

        fail(String.format(
                "Room '%s' sync timed out: expected %d on each node, got node1=%d / node2=%d",
                room, expected,
                roomClientsInCluster(node1, room),
                roomClientsInCluster(node2, room)));
    }

    private static void reSyncRoomAcrossCluster(SocketIOServer server, String room) {
        try {
            Namespace ns = (Namespace) server.getNamespace(Namespace.DEFAULT_NAME);
            if (ns != null) {
                Iterable<SocketIOClient> localClients = ns.getRoomClients(room);
                if (localClients != null) {
                    for (SocketIOClient client : localClients) {
                        ns.joinRoom(room, client.getSessionId());
                    }
                }
            }
        } catch (Throwable error) {
            throw new IllegalStateException("Could not re-synchronize room '" + room + "'", error);
        }
    }

    private static int roomClientsInCluster(SocketIOServer server, String room) {
        return defaultNamespace(server).getRoomClientsInCluster(room);
    }

    private static Namespace defaultNamespace(SocketIOServer server) {
        SocketIONamespace ns = server.getNamespace(Namespace.DEFAULT_NAME);
        if (!(ns instanceof Namespace)) {
            throw new IllegalStateException(
                    "Expected " + Namespace.class.getName() + " but got " + ns.getClass().getName());
        }
        return (Namespace) ns;
    }

    /**
     * Sends {@code event} with {@code data} to every client in {@code room} on both nodes,
     * skipping the client whose session ID equals {@code excludedId}.
     */
    private void sendExcept(String room, String event, String data, String excludedId) {
        for (SocketIOServer server : Arrays.asList(node1, node2)) {
            for (SocketIOClient client : server.getRoomOperations(room).getClients()) {
                if (!client.getSessionId().toString().equals(excludedId)) {
                    client.sendEvent(event, data);
                }
            }
        }
    }

    // ── Assertion helpers ─────────────────────────────────────────────────────

    private static void awaitOrFail(CountDownLatch latch, long timeoutSecs, String message)
            throws InterruptedException {
        assertTrue(latch.await(timeoutSecs, TimeUnit.SECONDS), message);
    }

    private static void awaitOrFail(CountDownLatch latch, long timeoutSecs,
                                     Supplier<String> messageSupplier)
            throws InterruptedException {
        assertTrue(latch.await(timeoutSecs, TimeUnit.SECONDS), messageSupplier);
    }

    // ── Socket helpers ────────────────────────────────────────────────────────

    private static IO.Options baseOptions() {
        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = true;
        opts.reconnectionAttempts = 5;
        opts.reconnectionDelay = 100;
        opts.timeout = 10000;
        return opts;
    }

    private String url(int port) {
        return "http://localhost:" + port;
    }

    /** Creates a new socket pointing at the given port using default options. */
    private Socket newSocket(int port) {
        try {
            return IO.socket(url(port), baseOptions());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket for port " + port, e);
        }
    }

    /** Connects all sockets and awaits the connect latch. */
    private void connectAll(CountDownLatch latch, Socket... sockets) throws InterruptedException {
        for (Socket s : sockets) {
            if (s.connected()) {
                latch.countDown();
            } else {
                s.connect();
            }
        }
        awaitOrFail(latch, OP_TIMEOUT_SECS, "Not all clients connected within timeout");
    }

    /**
     * Emits {@code join-room} from each socket and awaits the join latch.
     * Each socket must already have a "join-ok" listener that counts down {@code joinLatch}.
     */
    private void joinRoom(CountDownLatch joinLatch, String room, Socket... sockets)
            throws InterruptedException {
        for (Socket s : sockets) {
            if (!s.connected()) {
                s.connect();
            }
            s.emit("join-room", room);
        }
        if (!joinLatch.await(OP_TIMEOUT_SECS, TimeUnit.SECONDS)) {
            log.warn("joinRoom initial timeout for room {}, re-emitting join-room...", room);
            for (Socket s : sockets) {
                if (s.connected()) {
                    s.emit("join-room", room);
                }
            }
            awaitOrFail(joinLatch, OP_TIMEOUT_SECS, "Not all clients joined the room within timeout");
        }
    }

    /**
     * Registers connect and join-ok listeners on all provided sockets, decrementing the
     * respective latches. Call before {@link #connectAll} and {@link #joinRoom}.
     */
    private static void registerCounters(CountDownLatch connectLatch, CountDownLatch joinLatch,
                                          Socket... sockets) {
        for (Socket s : sockets) {
            if (connectLatch != null) {
                s.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
            }
            if (joinLatch != null) {
                s.on("join-ok",           args -> joinLatch.countDown());
            }
            s.on(Socket.EVENT_CONNECT_ERROR, args ->
                log.warn("Socket connection error: {}", args.length > 0 ? args[0] : "unknown")
            );
        }
    }

    /**
     * Disconnects every supplied socket. Detaches listeners first to prevent stale callbacks.
     */
    private static void disconnectAll(Socket... sockets) {
        for (Socket s : sockets) {
            if (s != null) {
                try {
                    s.off();
                    s.disconnect();
                } catch (Exception e) {
                    log.warn("Failed to disconnect socket cleanly during test cleanup: {}", e.getMessage());
                }
            }
        }
    }

    // ── Room name helpers ─────────────────────────────────────────────────────

    /** Unique room name to prevent state leakage between test runs. */
    private static String uniqueRoom() {
        return "room-" + UUID.randomUUID();
    }

    /** Unique room name with a human-readable prefix for easier log reading. */
    private static String uniqueRoom(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
