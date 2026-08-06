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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


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
public class ProtocolScenariosIntegrationTest extends AbstractSocketIOIntegrationTest {

    @Test
    @DisplayName("Scenario 1: Connection and Disconnection lifecycle (Default & Custom Namespace)")
    public void testConnectAndDisconnectLifecycle() throws Exception {
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

        Socket client = createClient();
        client.connect();

        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Client should connect to default namespace");
        assertNotNull(connectedClientRef.get());

        Thread.sleep(500);
        client.disconnect();
        client.close();
        assertTrue(disconnectLatch.await(10, TimeUnit.SECONDS), "Client should disconnect cleanly");
    }

    @Test
    @DisplayName("Scenario 2: Custom Namespace Connect and Event Processing")
    public void testCustomNamespaceConnectAndEvents() throws Exception {
        String nsName = "/custom_ns";
        SocketIONamespace customNs = getServer().addNamespace(nsName);

        CountDownLatch nsConnectLatch = new CountDownLatch(1);
        CountDownLatch nsEventLatch = new CountDownLatch(1);
        AtomicReference<String> receivedMsg = new AtomicReference<>();

        customNs.addConnectListener(client -> nsConnectLatch.countDown());
        customNs.addEventListener("customEvent", String.class, (client, data, ackRequest) -> {
            receivedMsg.set(data);
            nsEventLatch.countDown();
        });

        Socket client = createClient(nsName);
        client.connect();

        assertTrue(nsConnectLatch.await(5, TimeUnit.SECONDS), "Client should connect to custom namespace");

        client.emit("customEvent", "hello_custom");
        assertTrue(nsEventLatch.await(5, TimeUnit.SECONDS), "Event should be received in custom namespace");
        assertEquals("hello_custom", receivedMsg.get());

        client.disconnect();
    }

    @Test
    @DisplayName("Scenario 3: Send & Receive Event with and without Ack")
    public void testSendReceiveEventWithAndWithoutAck() throws Exception {
        CountDownLatch noAckLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);
        AtomicReference<String> noAckData = new AtomicReference<>();

        getServer().addEventListener("noAckEvent", String.class, (client, data, ackRequest) -> {
            noAckData.set(data);
            noAckLatch.countDown();
        });

        getServer().addEventListener("ackEvent", String.class, (client, data, ackRequest) -> {
            ackRequest.sendAckData("ack_reply_" + data);
        });

        Socket client = createClient();
        client.connect();

        // 1. Event without Ack
        client.emit("noAckEvent", "payload_no_ack");
        assertTrue(noAckLatch.await(5, TimeUnit.SECONDS), "No-ack event should be received");
        assertEquals("payload_no_ack", noAckData.get());

        // 2. Event with Ack
        AtomicReference<Object[]> clientAckResult = new AtomicReference<>();
        client.emit("ackEvent", new Object[]{"test_ack"}, args -> {
            clientAckResult.set(args);
            ackLatch.countDown();
        });

        assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "Ack response should be received by client");
        assertNotNull(clientAckResult.get());
        assertEquals("ack_reply_test_ack", clientAckResult.get()[0]);

        client.disconnect();
    }

    @Test
    @DisplayName("Scenario 4: Server-initiated Event to Client with Ack")
    public void testServerToClientEventWithAck() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch serverAckLatch = new CountDownLatch(1);
        AtomicReference<SocketIOClient> serverClientRef = new AtomicReference<>();
        AtomicReference<String> serverAckData = new AtomicReference<>();

        getServer().addConnectListener(client -> {
            serverClientRef.set(client);
            connectLatch.countDown();
        });

        Socket client = createClient();

        CountDownLatch clientReceiveLatch = new CountDownLatch(1);
        client.on("serverReq", args -> {
            clientReceiveLatch.countDown();
            if (args.length > 0 && args[args.length - 1] instanceof io.socket.client.Ack) {
                io.socket.client.Ack ack = (io.socket.client.Ack) args[args.length - 1];
                ack.call("client_response_ack");
            }
        });

        client.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Client must connect");

        serverClientRef.get().sendEvent("serverReq", new com.socketio4j.socketio.AckCallback<String>(String.class) {
            @Override
            public void onSuccess(String result) {
                serverAckData.set(result);
                serverAckLatch.countDown();
            }
        }, "ping_from_server");

        assertTrue(clientReceiveLatch.await(5, TimeUnit.SECONDS), "Client should receive server event");
        assertTrue(serverAckLatch.await(5, TimeUnit.SECONDS), "Server should receive client ack response");
        assertEquals("client_response_ack", serverAckData.get());

        client.disconnect();
    }

    @Test
    @DisplayName("Scenario 5: Binary Attachments (byte[]) Transmission with and without Ack")
    public void testBinaryAttachmentsTransmission() throws Exception {
        CountDownLatch binaryEventLatch = new CountDownLatch(1);
        AtomicReference<byte[]> receivedBinary = new AtomicReference<>();

        getServer().addEventListener("binaryEvent", byte[].class, (client, data, ackRequest) -> {
            receivedBinary.set(data);
            if (ackRequest.isAckRequested()) {
                byte[] responseBinary = new byte[]{100, 101, 102};
                ackRequest.sendAckData(responseBinary);
            }
            binaryEventLatch.countDown();
        });

        Socket client = createClient();
        client.connect();

        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        CountDownLatch binaryAckLatch = new CountDownLatch(1);
        AtomicReference<Object[]> clientBinaryAck = new AtomicReference<>();

        client.emit("binaryEvent", new Object[]{payload}, args -> {
            clientBinaryAck.set(args);
            binaryAckLatch.countDown();
        });

        assertTrue(binaryEventLatch.await(5, TimeUnit.SECONDS), "Server should receive binary event");
        assertTrue(binaryAckLatch.await(5, TimeUnit.SECONDS), "Client should receive binary ack response");

        assertArrayEquals(payload, receivedBinary.get(), "Received binary data on server should match");
        assertNotNull(clientBinaryAck.get());
        assertTrue(clientBinaryAck.get()[0] instanceof byte[]);
        assertArrayEquals(new byte[]{100, 101, 102}, (byte[]) clientBinaryAck.get()[0]);

        client.disconnect();
    }

    @Test
    @DisplayName("Scenario 6: Polling transport connect, event send/receive and disconnect")
    public void testPollingTransportScenario() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<String> receivedData = new AtomicReference<>();

        getServer().addConnectListener(client -> connectLatch.countDown());
        getServer().addEventListener("pollingEvent", String.class, (client, data, ackRequest) -> {
            receivedData.set(data);
            eventLatch.countDown();
        });

        IO.Options options = new IO.Options();
        options.transports = new String[]{"polling"};
        Socket client = IO.socket("http://" + getServerHost() + ":" + getServerPort(), options);
        client.connect();

        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Client should connect over polling");

        client.emit("pollingEvent", "hello_polling");
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "Event should be received over polling");
        assertEquals("hello_polling", receivedData.get());

        client.disconnect();
    }
}
