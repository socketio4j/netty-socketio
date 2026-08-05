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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.socketio4j.socketio.integration.AbstractSocketIOIntegrationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author https://github.com/sanjomo
 * @date 03/08/26 3:05 pm
 */
public class JsMultiClientInteropTest  extends AbstractSocketIOIntegrationTest {
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

        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                    System.out.println("[JS-v" + version + "-" + transport + "] " + line);
                }
            } catch (Exception ignored) {}
        });
        outputThread.setDaemon(true);
        outputThread.start();

        try {
            boolean completed = process.waitFor(20, TimeUnit.SECONDS);
            if (!completed) {
                fail(String.format("JS client process timed out after 20s (v%s, %s, scenario=%s, port=%d).\nOutput logs:\n%s",
                        version, transport, scenario, getServerPort(), getOutput(output)));
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
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    void testBroadcastToAllClients(String version, String transport) throws Exception {

        AtomicInteger startedClients = new AtomicInteger();

        getServer().addEventListener("start", String.class,
                (client, ignored, ackSender) -> {

                    if (startedClients.incrementAndGet() == 3) {

                        getServer()
                                .getBroadcastOperations()
                                .sendEvent("broadcastMessage", "hello_everyone");
                    }
                });

        runMultiJsTest(version, transport, "broadcast_all", 3);

        assertEquals(3, startedClients.get());
    }
    @ParameterizedTest(name = "[BCAST-002] Client v{0} over {1} - Broadcast Excluding Client")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    void testBroadcastExcludeClient(String version, String transport) throws Exception {

        AtomicInteger startEvents = new AtomicInteger();

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

        runMultiJsTest(version, transport, "broadcast_exclude_client", 3);

        assertEquals(1, startEvents.get(),
                "Only one client should initiate the broadcast");
    }

    @ParameterizedTest(name = "[BCAST-003] Client v{0} over {1} - Broadcast Excluding Predicate")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    void testBroadcastExcludePredicate(String version, String transport) throws Exception {

        AtomicInteger startEvents = new AtomicInteger();

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

        runMultiJsTest(version, transport, "broadcast_exclude_predicate", 3);

        assertEquals(1, startEvents.get(),
                "Only one client should initiate the broadcast");
    }
    @ParameterizedTest(name = "[BCAST-004] Client v{0} over {1} - Broadcast To Room")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    void testBroadcastToRoom(String version, String transport) throws Exception {

        AtomicInteger started = new AtomicInteger();
        AtomicInteger joinedRoom = new AtomicInteger();
        AtomicInteger notJoinedRoom = new AtomicInteger();

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

        runMultiJsTest(version, transport, "broadcast_room", 3);

        assertEquals(2, joinedRoom.get(),
                "Exactly two clients should join roomA");

        assertEquals(1, notJoinedRoom.get(),
                "Exactly one client should not join roomA");

        assertEquals(3, started.get());
    }
    @ParameterizedTest(name = "[BCAST-005] Client v{0} over {1} - Broadcast To Empty Room")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    void testBroadcastToEmptyRoom(String version, String transport) throws Exception {

        AtomicInteger started = new AtomicInteger();
        AtomicInteger leftRoom = new AtomicInteger();

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

        runMultiJsTest(version, transport, "broadcast_empty_room", 3);

        assertEquals(3, leftRoom.get(),
                "All clients should have left roomA");

        assertEquals(3, started.get());
    }
    @ParameterizedTest(name = "[BCAST-006] Client v{0} over {1} - Broadcast To Non-Existent Room")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    void testBroadcastToNonExistentRoom(String version, String transport) throws Exception {

        AtomicInteger started = new AtomicInteger();

        getServer().addEventListener("start", String.class,
                (client, ignored, ackSender) -> {

                    if (started.incrementAndGet() == 3) {

                        getServer()
                                .getRoomOperations("does_not_exist")
                                .sendEvent("roomMessage", "hello_room");
                    }
                });

        runMultiJsTest(version, transport, "broadcast_nonexistent_room", 3);

        assertEquals(3, started.get());
    }

}
