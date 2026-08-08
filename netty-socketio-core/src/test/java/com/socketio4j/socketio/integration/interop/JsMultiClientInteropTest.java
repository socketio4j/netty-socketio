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
package com.socketio4j.socketio.integration.interop;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author https://github.com/sanjomo
 * @date 03/08/26 3:05 pm
 */
@ResourceLock("NODE_JS_INTEROP")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JsMultiClientInteropTest extends AbstractReusableSocketIOInteropTest {
    private void runMultiJsTest(String version, String transport, String scenario, int clientCount) throws Exception {
        File jsDir = new File("src/test/resources/js-interop");
        if (!jsDir.exists()) {
            jsDir = new File("netty-socketio-core/src/test/resources/js-interop");
        }

        ProcessBuilder pb = new ProcessBuilder(
                "node",
                "test-clients-multi.js",
                "--version=" + version,
                "--port=" + getServerPort(),
                "--transport=" + transport,
                "--scenario=" + scenario,
                "--clients=" + clientCount);
        pb.directory(jsDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        AtomicReference<Throwable> outputFailure = new AtomicReference<>();

        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                }
            } catch (Throwable error) {
                outputFailure.set(error);
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        try {
            boolean completed = process.waitFor(20, TimeUnit.SECONDS);
            if (!completed) {
                fail(String.format("JS client process timed out after 20s (v%s, %s, scenario=%s, port=%d).\nOutput logs:\n%s",
                        version, transport, scenario, getServerPort(), getOutput(output)));
            }
            outputThread.join(TimeUnit.SECONDS.toMillis(1));
            if (outputThread.isAlive()) {
                fail("JS client output reader did not terminate\n" + getOutput(output));
            }
            if (outputFailure.get() != null) {
                throw new AssertionError("Unable to read JS client output", outputFailure.get());
            }

            assertEquals(0, process.exitValue(),
                    String.format("JS client process exited with non-zero status %d (v%s, %s, scenario=%s, port=%d).\nOutput logs:\n%s",
                            process.exitValue(), version, transport, scenario, getServerPort(), getOutput(output)));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String getOutput(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }
    @ParameterizedTest(name = "[BCAST-001] Client v{0} over {1} - Broadcast To All Clients")
    @MethodSource("com.socketio4j.socketio.integration.interop.JsClientInteropMatrix#clientTransports")
    void testBroadcastToAllClients(String version, String transport) throws Exception {

        AtomicInteger startedClients = new AtomicInteger();
        java.util.List<String> receivedMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        getServer().addMultiTypeEventListener("clientReceivedBroadcast", (client, data, ackSender) -> {
            receivedMessages.add(data.get(1));
        }, Integer.class, String.class);

        getServer().addEventListener("start", String.class,
                (client, ignored, ackSender) -> {

                    if (startedClients.incrementAndGet() == 3) {

                        getServer()
                                .getBroadcastOperations()
                                .sendEvent("broadcastMessage", "hello_everyone");
                    }
                });

        try {
            runMultiJsTest(version, transport, "broadcast_all", 3);

            assertEquals(3, startedClients.get());
            assertEquals(3, receivedMessages.size(), "Server verified: Exactly 3 clients received the broadcast");
            for (String msg : receivedMessages) {
                assertEquals("hello_everyone", msg, "Server verified: Received broadcast message content");
            }
        } finally {
            getServer().removeAllListeners("start");
            getServer().removeAllListeners("clientReceivedBroadcast");
        }
    }
    @ParameterizedTest(name = "[BCAST-002] Client v{0} over {1} - Broadcast Excluding Client")
    @MethodSource("com.socketio4j.socketio.integration.interop.JsClientInteropMatrix#clientTransports")
    void testBroadcastExcludeClient(String version, String transport) throws Exception {

        AtomicInteger startEvents = new AtomicInteger();
        java.util.List<String> receivedMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        getServer().addMultiTypeEventListener("clientReceivedBroadcast", (client, data, ackSender) -> {
            receivedMessages.add(data.get(1));
        }, Integer.class, String.class);

        getServer().addEventListener("start", String.class,
                (client, ignored, ackSender) -> {

                    startEvents.incrementAndGet();

                    getServer()
                            .getBroadcastOperations()
                            .sendEvent(
                                    "broadcastMessage",
                                    client,
                                    "hello_everyone");
                });

        try {
            runMultiJsTest(version, transport, "broadcast_exclude_client", 3);

            assertEquals(1, startEvents.get(),
                    "Only one client should initiate the broadcast");
            assertEquals(2, receivedMessages.size(), "Server verified: Exactly 2 clients (excluding sender) received the broadcast");
            for (String msg : receivedMessages) {
                assertEquals("hello_everyone", msg, "Server verified: Received broadcast message content");
            }
        } finally {
            getServer().removeAllListeners("start");
            getServer().removeAllListeners("clientReceivedBroadcast");
        }
    }

    @ParameterizedTest(name = "[BCAST-003] Client v{0} over {1} - Broadcast Excluding Predicate")
    @MethodSource("com.socketio4j.socketio.integration.interop.JsClientInteropMatrix#clientTransports")
    void testBroadcastExcludePredicate(String version, String transport) throws Exception {

        AtomicInteger startEvents = new AtomicInteger();
        java.util.List<String> receivedMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        getServer().addMultiTypeEventListener("clientReceivedBroadcast", (client, data, ackSender) -> {
            receivedMessages.add(data.get(1));
        }, Integer.class, String.class);

        getServer().addEventListener("start", String.class,
                (client, ignored, ackSender) -> {

                    startEvents.incrementAndGet();

                    getServer()
                            .getBroadcastOperations()
                            .sendEvent(
                                    "broadcastMessage",
                                    c -> c.getSessionId().equals(client.getSessionId()),
                                    "hello_everyone");
                });

        try {
            runMultiJsTest(version, transport, "broadcast_exclude_predicate", 3);

            assertEquals(1, startEvents.get(),
                    "Only one client should initiate the broadcast");
            assertEquals(2, receivedMessages.size(), "Server verified: Exactly 2 clients (predicate excluded) received the broadcast");
            for (String msg : receivedMessages) {
                assertEquals("hello_everyone", msg, "Server verified: Received broadcast message content");
            }
        } finally {
            getServer().removeAllListeners("start");
            getServer().removeAllListeners("clientReceivedBroadcast");
        }
    }
    @ParameterizedTest(name = "[BCAST-004] Client v{0} over {1} - Broadcast To Room")
    @MethodSource("com.socketio4j.socketio.integration.interop.JsClientInteropMatrix#clientTransports")
    void testBroadcastToRoom(String version, String transport) throws Exception {

        AtomicInteger started = new AtomicInteger();
        AtomicInteger joinedRoom = new AtomicInteger();
        AtomicInteger notJoinedRoom = new AtomicInteger();
        java.util.List<String> receivedRoomMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        getServer().addMultiTypeEventListener("clientReceivedRoomMessage", (client, data, ackSender) -> {
            receivedRoomMessages.add(data.get(1));
        }, Integer.class, String.class);

        getServer().addEventListener("start", String.class,
                (client, room, ackSender) -> {

                    if ("roomA".equals(room)) {
                        client.joinRoom("roomA");
                    }

                    if (client.getAllRooms().contains("roomA")) {
                        joinedRoom.incrementAndGet();
                    } else {
                        notJoinedRoom.incrementAndGet();
                    }

                    if (started.incrementAndGet() == 3) {
                        getServer()
                                .getRoomOperations("roomA")
                                .sendEvent("roomMessage", "hello_room");
                    }
                });

        try {
            runMultiJsTest(version, transport, "broadcast_room", 3);

            assertEquals(2, joinedRoom.get(),
                    "Exactly two clients should join roomA");

            assertEquals(1, notJoinedRoom.get(),
                    "Exactly one client should not join roomA");

            assertEquals(3, started.get());
            assertEquals(2, receivedRoomMessages.size(), "Server verified: Exactly 2 clients in roomA received room broadcast");
            for (String msg : receivedRoomMessages) {
                assertEquals("hello_room", msg, "Server verified: Received room message content");
            }
        } finally {
            getServer().removeAllListeners("start");
            getServer().removeAllListeners("clientReceivedRoomMessage");
        }
    }
    @ParameterizedTest(name = "[BCAST-005] Client v{0} over {1} - Broadcast To Empty Room")
    @MethodSource("com.socketio4j.socketio.integration.interop.JsClientInteropMatrix#clientTransports")
    void testBroadcastToEmptyRoom(String version, String transport) throws Exception {

        AtomicInteger started = new AtomicInteger();
        AtomicInteger leftRoom = new AtomicInteger();
        java.util.List<String> receivedRoomMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        getServer().addMultiTypeEventListener("clientReceivedRoomMessage", (client, data, ackSender) -> {
            receivedRoomMessages.add(data.get(1));
        }, Integer.class, String.class);

        getServer().addEventListener("start", String.class,
                (client, room, ackSender) -> {

                    client.joinRoom("roomA");
                    client.leaveRoom("roomA");

                    if (!client.getAllRooms().contains("roomA")) {
                        leftRoom.incrementAndGet();
                    }

                    if (started.incrementAndGet() == 3) {

                        getServer()
                                .getRoomOperations("roomA")
                                .sendEvent("roomMessage", "hello_room");
                    }
                });

        try {
            runMultiJsTest(version, transport, "broadcast_empty_room", 3);

            assertEquals(3, leftRoom.get(),
                    "All clients should have left roomA");

            assertEquals(3, started.get());
            assertEquals(0, receivedRoomMessages.size(), "Server verified: Zero messages delivered to empty room");
        } finally {
            getServer().removeAllListeners("start");
            getServer().removeAllListeners("clientReceivedRoomMessage");
        }
    }
    @ParameterizedTest(name = "[BCAST-006] Client v{0} over {1} - Broadcast To Non-Existent Room")
    @MethodSource("com.socketio4j.socketio.integration.interop.JsClientInteropMatrix#clientTransports")
    void testBroadcastToNonExistentRoom(String version, String transport) throws Exception {

        AtomicInteger started = new AtomicInteger();
        java.util.List<String> receivedRoomMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        getServer().addMultiTypeEventListener("clientReceivedRoomMessage", (client, data, ackSender) -> {
            receivedRoomMessages.add(data.get(1));
        }, Integer.class, String.class);

        getServer().addEventListener("start", String.class,
                (client, ignored, ackSender) -> {

                    if (started.incrementAndGet() == 3) {

                        getServer()
                                .getRoomOperations("does_not_exist")
                                .sendEvent("roomMessage", "hello_room");
                    }
                });

        try {
            runMultiJsTest(version, transport, "broadcast_nonexistent_room", 3);

            assertEquals(3, started.get());
            assertEquals(0, receivedRoomMessages.size(), "Server verified: Zero messages delivered to non-existent room");
        } finally {
            getServer().removeAllListeners("start");
            getServer().removeAllListeners("clientReceivedRoomMessage");
        }
    }

}
