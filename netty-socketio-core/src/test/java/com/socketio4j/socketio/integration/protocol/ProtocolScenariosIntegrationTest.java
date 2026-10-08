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
package com.socketio4j.socketio.integration.protocol;
import com.socketio4j.socketio.integration.protocol.AbstractSocketIOIntegrationTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIONamespace;
import com.socketio4j.socketio.listener.ConnectListener;
import com.socketio4j.socketio.listener.DataListener;
import com.socketio4j.socketio.listener.DisconnectListener;

import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@DisplayName("Comprehensive Protocol Integration Scenarios Test")
public class ProtocolScenariosIntegrationTest extends AbstractSharedSocketIOIntegrationTest {

    @Override
    protected SharedServerFixtureProfile sharedServerFixtureProfile() {
        return SharedServerFixtureProfile.FAST_DISCONNECT_NIO;
    }

    @ParameterizedTest(name = "Scenario 1 [{0}]")
    @ValueSource(strings = {"polling", "websocket"})
    @DisplayName("Scenario 1: Connection and Disconnection lifecycle")
    public void testConnectAndDisconnectLifecycle(String transport) throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicReference<SocketIOClient> connectedClientRef = new AtomicReference<>();

        getServer().addConnectListener(new ConnectListener() {
            @Override
            public void onConnect(SocketIOClient client) {
                connectedClientRef.set(client);
                connectLatch.countDown();
            }
        });

        getServer().addDisconnectListener(new DisconnectListener() {
            @Override
            public void onDisconnect(SocketIOClient client) {
                disconnectLatch.countDown();
            }
        });

        Socket client = createClient(new String[]{transport});
        connectAndAwait(client, transport);

        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Client should connect to default namespace over " + transport);
        assertNotNull(connectedClientRef.get());

        client.disconnect();
        assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS), "Client should disconnect cleanly over " + transport);
    }

    @ParameterizedTest(name = "Scenario 2 [{0}]")
    @ValueSource(strings = {"polling", "websocket"})
    @DisplayName("Scenario 2: Custom Namespace Connect and Event Processing")
    public void testCustomNamespaceConnectAndEvents(String transport) throws Exception {
        String nsName = "/custom_ns_" + transport;
        SocketIONamespace customNs = getServer().addNamespace(nsName);

        CountDownLatch nsConnectLatch = new CountDownLatch(1);
        CountDownLatch nsEventLatch = new CountDownLatch(1);
        AtomicReference<String> receivedMsg = new AtomicReference<>();

        customNs.addConnectListener(client -> nsConnectLatch.countDown());
        customNs.addEventListener("customEvent", String.class, (client, data, ackRequest) -> {
            receivedMsg.set(data);
            nsEventLatch.countDown();
        });

        Socket client = createClient(nsName, new String[]{transport});
        connectAndAwait(client, transport);

        assertTrue(nsConnectLatch.await(5, TimeUnit.SECONDS), "Client should connect to custom namespace over " + transport);

        client.emit("customEvent", "hello_custom");
        assertTrue(nsEventLatch.await(5, TimeUnit.SECONDS), "Event should be received in custom namespace over " + transport);
        assertEquals("hello_custom", receivedMsg.get());

        client.disconnect();
    }

    @ParameterizedTest(name = "Scenario 3 [{0}]")
    @ValueSource(strings = {"polling", "websocket"})
    @DisplayName("Scenario 3: Send & Receive Event with and without Ack")
    public void testSendReceiveEventWithAndWithoutAck(String transport) throws Exception {
        CountDownLatch noAckLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);
        AtomicReference<String> noAckData = new AtomicReference<>();

        getServer().addEventListener("noAckEvent_" + transport, String.class, (client, data, ackRequest) -> {
            noAckData.set(data);
            noAckLatch.countDown();
        });

        getServer().addEventListener("ackEvent_" + transport, String.class, (client, data, ackRequest) -> {
            ackRequest.sendAckData("ack_reply_" + data);
        });

        Socket client = createClient(new String[]{transport});
        connectAndAwait(client, transport);

        // 1. Event without Ack
        client.emit("noAckEvent_" + transport, "payload_no_ack");
        assertTrue(noAckLatch.await(5, TimeUnit.SECONDS), "No-ack event should be received over " + transport);
        assertEquals("payload_no_ack", noAckData.get());

        // 2. Event with Ack
        AtomicReference<Object[]> clientAckResult = new AtomicReference<>();
        client.emit("ackEvent_" + transport, new Object[]{"test_ack"}, args -> {
            clientAckResult.set(args);
            ackLatch.countDown();
        });

        assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "Ack response should be received by client over " + transport);
        assertNotNull(clientAckResult.get());
        assertEquals("ack_reply_test_ack", clientAckResult.get()[0]);

        client.disconnect();
    }

    @ParameterizedTest(name = "Scenario 4 [{0}]")
    @ValueSource(strings = {"polling", "websocket"})
    @DisplayName("Scenario 4: Server-initiated Event to Client with Ack")
    public void testServerToClientEventWithAck(String transport) throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch serverAckLatch = new CountDownLatch(1);
        AtomicReference<SocketIOClient> serverClientRef = new AtomicReference<>();
        AtomicReference<String> serverAckData = new AtomicReference<>();

        getServer().addConnectListener(client -> {
            serverClientRef.set(client);
            connectLatch.countDown();
        });

        Socket client = createClient(new String[]{transport});

        CountDownLatch clientReceiveLatch = new CountDownLatch(1);
        client.on("serverReq_" + transport, args -> {
            clientReceiveLatch.countDown();
            if (args.length > 0 && args[args.length - 1] instanceof io.socket.client.Ack) {
                io.socket.client.Ack ack = (io.socket.client.Ack) args[args.length - 1];
                ack.call("client_response_ack");
            }
        });

        connectAndAwait(client, transport);
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Client must connect over " + transport);

        serverClientRef.get().sendEvent("serverReq_" + transport, new com.socketio4j.socketio.AckCallback<String>(String.class) {
            @Override
            public void onSuccess(String result) {
                serverAckData.set(result);
                serverAckLatch.countDown();
            }
        }, "ping_from_server");

        assertTrue(clientReceiveLatch.await(5, TimeUnit.SECONDS), "Client should receive server event over " + transport);
        assertTrue(serverAckLatch.await(5, TimeUnit.SECONDS), "Server should receive client ack response over " + transport);
        assertEquals("client_response_ack", serverAckData.get());

        client.disconnect();
    }

    @ParameterizedTest(name = "Scenario 5 [{0}]")
    @ValueSource(strings = {"polling", "websocket"})
    @DisplayName("Scenario 5: Binary Attachments (byte[]) Transmission with and without Ack")
    public void testBinaryAttachmentsTransmission(String transport) throws Exception {
        CountDownLatch binaryEventLatch = new CountDownLatch(1);
        AtomicReference<byte[]> receivedBinary = new AtomicReference<>();

        getServer().addEventListener("binaryEvent_" + transport, byte[].class, (client, data, ackRequest) -> {
            receivedBinary.set(data);
            if (ackRequest.isAckRequested()) {
                byte[] responseBinary = new byte[]{100, 101, 102};
                ackRequest.sendAckData(responseBinary);
            }
            binaryEventLatch.countDown();
        });

        Socket client = createClient(new String[]{transport});
        connectAndAwait(client, transport);

        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        CountDownLatch binaryAckLatch = new CountDownLatch(1);
        AtomicReference<Object[]> clientBinaryAck = new AtomicReference<>();

        client.emit("binaryEvent_" + transport, new Object[]{payload}, args -> {
            clientBinaryAck.set(args);
            binaryAckLatch.countDown();
        });

        assertTrue(binaryEventLatch.await(5, TimeUnit.SECONDS), "Server should receive binary event over " + transport);
        assertTrue(binaryAckLatch.await(5, TimeUnit.SECONDS), "Client should receive binary ack response over " + transport);

        assertArrayEquals(payload, receivedBinary.get(), "Received binary data on server should match over " + transport);
        assertNotNull(clientBinaryAck.get());
        assertTrue(clientBinaryAck.get()[0] instanceof byte[]);
        assertArrayEquals(new byte[]{100, 101, 102}, (byte[]) clientBinaryAck.get()[0]);

        client.disconnect();
    }

    private void connectAndAwait(Socket client, String transport) throws InterruptedException {
        CountDownLatch clientConnectLatch = new CountDownLatch(1);
        AtomicReference<Object> connectError = new AtomicReference<Object>();
        client.on(Socket.EVENT_CONNECT, args -> clientConnectLatch.countDown());
        client.on(Socket.EVENT_CONNECT_ERROR, args -> {
            if (args.length > 0) {
                connectError.set(args[0]);
            }
        });
        client.connect();

        assertTrue(clientConnectLatch.await(5, TimeUnit.SECONDS),
                "Client must complete the Socket.IO handshake over " + transport
                        + (connectError.get() == null ? "" : ": " + connectError.get()));
        assertTrue(client.connected(), "Client must be connected over " + transport);
    }
}
