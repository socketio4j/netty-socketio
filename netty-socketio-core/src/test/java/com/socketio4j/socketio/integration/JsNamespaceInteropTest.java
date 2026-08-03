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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIONamespace;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.namespace.Namespace;

import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author https://github.com/sanjomo
 * @date 03/08/26 3:59 pm
 */
public class JsNamespaceInteropTest extends AbstractSocketIOIntegrationTest {

    private void runNamespaceJsTest(
            String version,
            String transport,
            String scenario,
            String namespace) throws Exception {

        File jsDir = new File("src/test/resources/js-interop");
        if (!jsDir.exists()) {
            jsDir = new File("netty-socketio-core/src/test/resources/js-interop");
        }

        ProcessBuilder pb = new ProcessBuilder(
                "node",
                "test-clients-namespace.js",
                "--version=" + version,
                "--port=" + getServerPort(),
                "--transport=" + transport,
                "--scenario=" + scenario,
                "--namespace=" + namespace);

        pb.directory(jsDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();

        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;

                while ((line = r.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                    System.out.println("[NS-JS] " + line);
                }

            } catch (Exception ignored) {
            }
        });

        t.setDaemon(true);
        t.start();

        try {

            boolean completed =
                    process.waitFor(20, TimeUnit.SECONDS);

            if (!completed) {
                fail(getOutput(output));
            }

            assertEquals(0,
                    process.exitValue(),
                    getOutput(output));

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
    private SocketIONamespace chat;

    @Override
    protected void configureNamespaces(SocketIOServer server) {
        System.out.println("configureNamespaces called");
        chat = server.addNamespace("/chat");
    }

    //
    // Namespace tests start here
    //

    @ParameterizedTest(name = "[NS-001] Client v{0} over {1} - Connect Custom Namespace")
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
    void testConnectCustomNamespace(String version, String transport) throws Exception {

        AtomicInteger connected = new AtomicInteger();
        AtomicInteger helloReceived = new AtomicInteger();

        chat.addConnectListener(client -> {
            connected.incrementAndGet();
        });

        getServer().addEventListener("helloEvent", String.class,
                (client, data, ackSender) -> {

                });

        chat.addEventListener("helloEvent", String.class,
                (client, data, ackSender) -> {



                    helloReceived.incrementAndGet();

                    client.sendEvent("helloResponse", "Hello back!");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_connect",
                "/chat");

        assertEquals(1, connected.get());
        assertEquals(1, helloReceived.get());
    }
    @ParameterizedTest(name = "[NS-002] Client v{0} over {1} - Reject Unknown Namespace")
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
    void testRejectUnknownNamespace(String version, String transport) throws Exception {

        AtomicInteger connected = new AtomicInteger();

        chat.addConnectListener(client ->
                connected.incrementAndGet());

        runNamespaceJsTest(
                version,
                transport,
                "namespace_reject",
                "/unknown");

        assertEquals(0, connected.get());
    }
    @ParameterizedTest(name = "[NS-003] Client v{0} over {1} - Namespace Isolation")
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
    void testNamespaceIsolation(String version, String transport) throws Exception {

        AtomicInteger defaultEvents = new AtomicInteger();
        AtomicInteger chatEvents = new AtomicInteger();

        getServer().addEventListener("helloEvent", String.class,
                (client, data, ackSender) ->
                        defaultEvents.incrementAndGet());

        chat.addEventListener("helloEvent", String.class,
                (client, data, ackSender) -> {

                    chatEvents.incrementAndGet();

                    client.sendEvent("helloResponse", "Hello back!");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_isolation",
                "/chat");

        assertEquals(0, defaultEvents.get(),
                "Default namespace must not receive the event");

        assertEquals(1, chatEvents.get(),
                "Chat namespace should receive exactly one event");
    }

    @ParameterizedTest(name = "[NS-004] Client v{0} over {1} - Multiple Namespace Connections")
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
    void testMultipleNamespaceConnections(String version, String transport) throws Exception {

        AtomicInteger defaultConnected = new AtomicInteger();
        AtomicInteger chatConnected = new AtomicInteger();

        getServer().addConnectListener(client -> {
            System.out.println("DEFAULT CONNECT  session=" + client.getSessionId()
                    + " namespace=" + client.getNamespace().getName());
            defaultConnected.incrementAndGet();
        });

        chat.addConnectListener(client -> {
            System.out.println("CHAT CONNECT     session=" + client.getSessionId()
                    + " namespace=" + client.getNamespace().getName());
            chatConnected.incrementAndGet();
        });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_multiple",
                "");

        assertEquals(1, defaultConnected.get(),
                "Default namespace should receive one connection");

        assertEquals(1, chatConnected.get(),
                "Chat namespace should receive one connection");
    }

    @ParameterizedTest(name = "[NS-005] Client v{0} over {1} - Force New Creates Separate Connections")
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
    void testForceNewCreatesSeparateConnections(String version, String transport) throws Exception {

        AtomicInteger connected = new AtomicInteger();

        getServer().addConnectListener(client ->
                connected.incrementAndGet());

        runNamespaceJsTest(
                version,
                transport,
                "namespace_force_new",
                "");

        assertEquals(2, connected.get(),
                "forceNew=true should create two independent connections");
    }

    @ParameterizedTest(name = "[NS-006A] Client v{0} over {1} - Client Disconnect Namespace")
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
    void testClientDisconnectNamespace(String version, String transport) throws Exception {

        AtomicInteger defaultEvents = new AtomicInteger();
        AtomicInteger chatDisconnects = new AtomicInteger();

        getServer().addEventListener("defaultPing", String.class,
                (client, data, ackSender) -> {
                    defaultEvents.incrementAndGet();
                    ackSender.sendAckData("ALIVE");
                });

        chat.addDisconnectListener(client ->
                chatDisconnects.incrementAndGet());

        runNamespaceJsTest(
                version,
                transport,
                "namespace_client_disconnect",
                "");

        assertEquals(1, chatDisconnects.get());
        assertEquals(1, defaultEvents.get());
    }

    @ParameterizedTest(name = "[NS-006B] Client v{0} over {1} - Server Disconnect Namespace")
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
    void testServerDisconnectNamespace(String version, String transport) throws Exception {

        AtomicInteger leaveRequests = new AtomicInteger();
        AtomicInteger confirmRequests = new AtomicInteger();
        AtomicInteger defaultEvents = new AtomicInteger();
        AtomicInteger chatDisconnects = new AtomicInteger();

        chat.addEventListener("leaveNamespace", String.class,
                (client, data, ackSender) -> {

                    leaveRequests.incrementAndGet();

                    client.sendEvent("prepareDisconnect");
                });

        chat.addEventListener("confirmDisconnect", String.class,
                (client, data, ackSender) -> {

                    confirmRequests.incrementAndGet();

                    client.disconnect();
                });

        getServer().addEventListener("defaultPing", String.class,
                (client, data, ackSender) -> {

                    defaultEvents.incrementAndGet();

                    ackSender.sendAckData("ALIVE");
                });

        chat.addDisconnectListener(client ->
                chatDisconnects.incrementAndGet());

        runNamespaceJsTest(
                version,
                transport,
                "namespace_server_disconnect",
                "");

        assertEquals(1, leaveRequests.get());
        assertEquals(1, confirmRequests.get());
        assertEquals(1, chatDisconnects.get());
        assertEquals(1, defaultEvents.get());
    }

    @ParameterizedTest(name = "[NS-007] Client v{0} over {1} - Namespace Event Isolation")
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
    void testNamespaceEventIsolation(String version, String transport) throws Exception {

        AtomicInteger defaultEvents = new AtomicInteger();
        AtomicInteger chatEvents = new AtomicInteger();
        for (SocketIONamespace ns : getServer().getAllNamespaces()) {
            System.out.println(
                    "NAMESPACE " + ns.getName()
                            + " object=" + System.identityHashCode(ns));
        }
        Namespace defaultNamespace = (Namespace) getServer().getAllNamespaces().stream()
                .filter(ns -> ns.getName().equals(""))
                .findFirst().get();

        defaultNamespace.addEventListener("fireDefault", String.class,
                (client, data, ackSender) -> {
                    defaultEvents.incrementAndGet();

                    defaultNamespace.getBroadcastOperations()
                            .sendEvent("defaultMessage");
                });

        chat.addEventListener("fireChat", String.class,
                (client, data, ackSender) -> {
                    chatEvents.incrementAndGet();

                    chat.getBroadcastOperations()
                            .sendEvent("chatMessage");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_event_isolation",
                "");

        assertEquals(1, defaultEvents.get());
        assertEquals(1, chatEvents.get());
    }
    @ParameterizedTest(name = "[NS-008] Client v{0} over {1} - Namespace ACK Isolation")
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
    void testNamespaceAckIsolation(String version, String transport) throws Exception {

        AtomicInteger defaultAckRequests = new AtomicInteger();
        AtomicInteger chatAckRequests = new AtomicInteger();

        //
        // Default namespace
        //
        getServer().addEventListener("defaultAck", String.class,
                (client, data, ackSender) -> {
                    defaultAckRequests.incrementAndGet();
                    ackSender.sendAckData("DEFAULT");
                });

        //
        // Chat namespace
        //
        chat.addEventListener("chatAck", String.class,
                (client, data, ackSender) -> {
                    chatAckRequests.incrementAndGet();
                    ackSender.sendAckData("CHAT");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_ack_isolation",
                "");

        assertEquals(1, defaultAckRequests.get(),
                "Default namespace ACK handler should be invoked once");

        assertEquals(1, chatAckRequests.get(),
                "Chat namespace ACK handler should be invoked once");
    }
    @ParameterizedTest(name = "[NS-010] Client v{0} over {1} - Binary Event Isolation")
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
    void testNamespaceBinaryIsolation(String version, String transport) throws Exception {

        AtomicInteger defaultBinaryEvents = new AtomicInteger();
        AtomicInteger chatBinaryEvents = new AtomicInteger();

        getServer().addEventListener("fireDefaultBinary", byte[].class,
                (client, data, ackSender) -> {
                    defaultBinaryEvents.incrementAndGet();

                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("defaultBinary", data);
                });

        chat.addEventListener("fireChatBinary", byte[].class,
                (client, data, ackSender) -> {
                    chatBinaryEvents.incrementAndGet();

                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("chatBinary", data);
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_binary_isolation",
                "");

        assertEquals(1, defaultBinaryEvents.get());
        assertEquals(1, chatBinaryEvents.get());
    }
    @ParameterizedTest(name = "[NS-011] Client v{0} over {1} - Concurrent Binary Events")
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
    void testNamespaceConcurrentBinaryEvents(String version, String transport) throws Exception {

        AtomicInteger defaultBinaryEvents = new AtomicInteger();
        AtomicInteger chatBinaryEvents = new AtomicInteger();

        getServer().addEventListener("fireDefaultBinary", byte[].class,
                (client, data, ackSender) -> {
                    defaultBinaryEvents.incrementAndGet();

                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("defaultBinary", data);
                });

        chat.addEventListener("fireChatBinary", byte[].class,
                (client, data, ackSender) -> {
                    chatBinaryEvents.incrementAndGet();

                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("chatBinary", data);
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_concurrent_binary",
                "");

        assertEquals(1, defaultBinaryEvents.get());
        assertEquals(1, chatBinaryEvents.get());
    }

    @ParameterizedTest(name = "[NS-012] Client v{0} over {1} - Cross Namespace Event Ordering")
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
    void testNamespaceEventOrdering(String version, String transport) throws Exception {

        List<String> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger received = new AtomicInteger();

        Consumer<SocketIOClient> complete = client -> {
            if (received.incrementAndGet() == 5) {
                client.sendEvent("orderingComplete");
            }
        };

        getServer().addEventListener("sequence", Integer.class,
                (client, value, ackSender) -> {
                    order.add("default:" + value);
                    complete.accept(client);
                });

        chat.addEventListener("sequence", Integer.class,
                (client, value, ackSender) -> {
                    order.add("chat:" + value);
                    complete.accept(client);
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_event_ordering",
                "");

        assertEquals(
                Arrays.asList(
                        "default:1",
                        "chat:2",
                        "default:3",
                        "chat:4",
                        "default:5"
                ),
                order
        );
    }
    @ParameterizedTest(name = "[NS-013] Client v{0} over {1} - Room Isolation")
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
    void testNamespaceRoomIsolation(String version, String transport) throws Exception {

        AtomicInteger defaultJoin = new AtomicInteger();
        AtomicInteger chatJoin = new AtomicInteger();

        getServer().addEventListener("joinDefaultRoom", String.class,
                (client, room, ackSender) -> {
                    defaultJoin.incrementAndGet();

                    client.joinRoom(room);

                    client.getNamespace()
                            .getRoomOperations(room)
                            .sendEvent("defaultRoomMessage");
                });

        chat.addEventListener("joinChatRoom", String.class,
                (client, room, ackSender) -> {
                    chatJoin.incrementAndGet();

                    client.joinRoom(room);

                    client.getNamespace()
                            .getRoomOperations(room)
                            .sendEvent("chatRoomMessage");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_room_isolation",
                "");

        assertEquals(1, defaultJoin.get());
        assertEquals(1, chatJoin.get());
    }

    @ParameterizedTest(name = "[NS-014] Client v{0} over {1} - Room Join/Leave Isolation")
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
    void testNamespaceRoomJoinLeaveIsolation(String version, String transport) throws Exception {

        AtomicInteger defaultJoins = new AtomicInteger();
        AtomicInteger chatJoins = new AtomicInteger();
        AtomicInteger defaultLeaves = new AtomicInteger();

        getServer().addEventListener("joinDefaultRoom", String.class,
                (client, room, ackSender) -> {
                    defaultJoins.incrementAndGet();
                    client.joinRoom(room);
                });

        chat.addEventListener("joinChatRoom", String.class,
                (client, room, ackSender) -> {
                    chatJoins.incrementAndGet();
                    client.joinRoom(room);
                });

        getServer().addEventListener("leaveDefaultRoom", String.class,
                (client, room, ackSender) -> {

                    defaultLeaves.incrementAndGet();

                    client.leaveRoom(room);

                    client.getNamespace()
                            .getRoomOperations(room)
                            .sendEvent("defaultRoomMessage");

                    chat.getRoomOperations(room)
                            .sendEvent("chatRoomMessage");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_room_join_leave_isolation",
                "");

        assertEquals(1, defaultJoins.get());
        assertEquals(1, chatJoins.get());
        assertEquals(1, defaultLeaves.get());
    }
    @ParameterizedTest(name = "[NS-015] Client v{0} over {1} - Broadcast Excluding Sender")
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
    void testNamespaceBroadcastExcludeSender(String version, String transport) throws Exception {

        AtomicInteger defaultRequests = new AtomicInteger();
        AtomicInteger chatRequests = new AtomicInteger();

        getServer().addEventListener("broadcastDefault", String.class,
                (client, data, ackSender) -> {

                    defaultRequests.incrementAndGet();

                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("defaultBroadcast");
                });

        chat.addEventListener("broadcastChat", String.class,
                (client, data, ackSender) -> {

                    chatRequests.incrementAndGet();

                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("chatBroadcast");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_broadcast_exclude_sender",
                "");

        assertEquals(1, defaultRequests.get());
        assertEquals(1, chatRequests.get());
    }
    @ParameterizedTest(name = "[NS-016] Client v{0} over {1} - Namespace Reconnection Isolation")
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
    void testNamespaceReconnectIsolation(String version, String transport) throws Exception {

        AtomicInteger defaultPings = new AtomicInteger();
        AtomicInteger reconnectRequests = new AtomicInteger();

        getServer().addEventListener("defaultPing", String.class,
                (client, data, ackSender) -> {
                    defaultPings.incrementAndGet();
                    ackSender.sendAckData("ALIVE");
                });

        chat.addEventListener("reconnectNamespace", String.class,
                (client, data, ackSender) -> {
                    reconnectRequests.incrementAndGet();

                    client.disconnect();
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_reconnect_isolation",
                "");

        assertEquals(1, reconnectRequests.get());
        assertEquals(1, defaultPings.get());
    }
    @ParameterizedTest(name = "[NS-017] Client v{0} over {1} - Mixed Packet Isolation")
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
    void testNamespaceMixedPacketIsolation(String version, String transport) throws Exception {

        AtomicInteger textEvents = new AtomicInteger();
        AtomicInteger binaryEvents = new AtomicInteger();
        AtomicInteger ackEvents = new AtomicInteger();

        getServer().addEventListener("textEvent", String.class,
                (client, data, ackSender) -> {
                    textEvents.incrementAndGet();
                    client.sendEvent("textResponse", data);
                });

        chat.addEventListener("binaryEvent", byte[].class,
                (client, data, ackSender) -> {
                    binaryEvents.incrementAndGet();
                    client.sendEvent("binaryResponse", data);
                });

        getServer().addEventListener("ackEvent", String.class,
                (client, data, ackSender) -> {
                    ackEvents.incrementAndGet();
                    ackSender.sendAckData("ACK_OK");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_mixed_packets",
                "");

        assertEquals(1, textEvents.get());
        assertEquals(1, binaryEvents.get());
        assertEquals(1, ackEvents.get());
    }
    @ParameterizedTest(name = "[NS-018] Client v{0} over {1} - Namespace Volatile Event Isolation")
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
    void testNamespaceVolatileIsolation(String version, String transport) throws Exception {

        AtomicInteger defaultEvents = new AtomicInteger();
        AtomicInteger chatEvents = new AtomicInteger();

        getServer().addEventListener("fireDefaultVolatile", String.class,
                (client, data, ackSender) -> {

                    defaultEvents.incrementAndGet();

                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("defaultVolatile");
                });

        chat.addEventListener("fireChatVolatile", String.class,
                (client, data, ackSender) -> {

                    chatEvents.incrementAndGet();

                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("chatVolatile");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_volatile_isolation",
                "");

        assertEquals(1, defaultEvents.get());
        assertEquals(1, chatEvents.get());
    }
    @ParameterizedTest(name = "[NS-019] Client v{0} over {1} - Mixed ACK/Binary/Broadcast")
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
    void testNamespaceMixedMultiplexing(String version, String transport) throws Exception {

        AtomicInteger ackEvents = new AtomicInteger();
        AtomicInteger binaryEvents = new AtomicInteger();
        AtomicInteger broadcastEvents = new AtomicInteger();

        getServer().addEventListener("ackEvent", String.class,
                (client, data, ackSender) -> {
                    ackEvents.incrementAndGet();
                    ackSender.sendAckData("ACK_OK");
                });

        chat.addEventListener("binaryEvent", byte[].class,
                (client, data, ackSender) -> {
                    binaryEvents.incrementAndGet();
                    client.sendEvent("binaryResponse", data);
                });

        getServer().addEventListener("broadcastEvent", String.class,
                (client, data, ackSender) -> {
                    broadcastEvents.incrementAndGet();
                    client.getNamespace()
                            .getBroadcastOperations()
                            .sendEvent("broadcastResponse");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_mixed_multiplexing",
                "");

        assertEquals(1, ackEvents.get());
        assertEquals(1, binaryEvents.get());
        assertEquals(1, broadcastEvents.get());
    }

    @ParameterizedTest(name = "[NS-020] Client v{0} over {1} - Namespace Stress Multiplexing")
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
    void testNamespaceStressMultiplexing(String version, String transport) throws Exception {

        AtomicInteger textEvents = new AtomicInteger();
        AtomicInteger binaryEvents = new AtomicInteger();
        AtomicInteger ackEvents = new AtomicInteger();

        getServer().addEventListener("text", String.class,
                (client, data, ackSender) -> {
                    textEvents.incrementAndGet();
                    client.sendEvent("textResponse", data);
                });

        chat.addEventListener("binary", byte[].class,
                (client, data, ackSender) -> {
                    binaryEvents.incrementAndGet();
                    client.sendEvent("binaryResponse", data);
                });

        getServer().addEventListener("ack", String.class,
                (client, data, ackSender) -> {
                    ackEvents.incrementAndGet();
                    ackSender.sendAckData("ACK");
                });

        runNamespaceJsTest(
                version,
                transport,
                "namespace_stress_multiplexing",
                "");

        assertEquals(10, textEvents.get());
        assertEquals(10, binaryEvents.get());
        assertEquals(10, ackEvents.get());
    }

}