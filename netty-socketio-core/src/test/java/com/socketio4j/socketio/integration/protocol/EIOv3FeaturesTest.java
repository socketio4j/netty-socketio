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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import com.socketio4j.socketio.SocketIOClient;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@DisplayName("Engine.IO v3 Generic Features Integration Tests")
public class EIOv3FeaturesTest extends AbstractSharedSocketIOIntegrationTest {

    @Test
    @DisplayName("Should successfully handle connection, disconnection, text messaging, room join, room leave, and broadcasting for EIOv3 clients")
    public void testEIOv3GenericFeatures() throws Exception {
        final AtomicInteger serverConnections = new AtomicInteger(0);
        final AtomicInteger serverDisconnections = new AtomicInteger(0);
        final AtomicReference<String> receivedTextVal = new AtomicReference<String>();
        final AtomicReference<UUID> client1SessionId = new AtomicReference<UUID>();
        final AtomicReference<UUID> client2SessionId = new AtomicReference<UUID>();
        
        final List<String> client1Messages = new CopyOnWriteArrayList<String>();
        final List<String> client2Messages = new CopyOnWriteArrayList<String>();

        // 1. Configure server listeners
        getServer().addConnectListener(client -> {
            int currentConn = serverConnections.incrementAndGet();
            client.joinRoom("testRoom");
            if (currentConn == 1) {
                client1SessionId.set(client.getSessionId());
            } else if (currentConn == 2) {
                client2SessionId.set(client.getSessionId());
            }
        });

        getServer().addDisconnectListener(client -> {
            serverDisconnections.incrementAndGet();
        });

        getServer().addEventListener("testText", String.class, (client, data, ackRequest) -> {
            receivedTextVal.set(data);
        });

        // 2. Connect Client 1 using WebSocket EIO=3
        OkHttpClient httpClient = new OkHttpClient();
        
        Request request1 = new Request.Builder()
                .url("ws://" + getServerHost() + ":" + getServerPort() + "/socket.io/?EIO=3&transport=websocket")
                .build();

        WebSocket webSocket1 = httpClient.newWebSocket(request1, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                client1Messages.add(text);
            }
        });

        // Wait for Client 1 connection on server
        await().atMost(5, SECONDS).until(() -> serverConnections.get() == 1);
        assertNotNull(client1SessionId.get());

        // 3. Connect Client 2 using WebSocket EIO=3
        Request request2 = new Request.Builder()
                .url("ws://" + getServerHost() + ":" + getServerPort() + "/socket.io/?EIO=3&transport=websocket")
                .build();

        WebSocket webSocket2 = httpClient.newWebSocket(request2, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                client2Messages.add(text);
            }
        });

        // Wait for Client 2 connection on server
        await().atMost(5, SECONDS).until(() -> serverConnections.get() == 2);
        assertNotNull(client2SessionId.get());

        // Both clients need to send namespace connect packet: "40"
        webSocket1.send("40");
        webSocket2.send("40");

        // 4. Test Text Messaging (client to server)
        // Send: "42[\"testText\",\"hello from client 1\"]"
        webSocket1.send("42[\"testText\",\"hello from client 1\"]");

        await().atMost(5, SECONDS).until(() -> receivedTextVal.get() != null);
        assertEquals("hello from client 1", receivedTextVal.get());

        // 5. Test Broadcasting to Room "testRoom" (which both joined)
        getServer().getRoomOperations("testRoom").sendEvent("roomBroadcast", "welcome");

        // Both clients should receive: "42[\"roomBroadcast\",\"welcome\"]"
        await().atMost(5, SECONDS).until(() -> 
            client1Messages.stream().anyMatch(msg -> msg.contains("roomBroadcast")) &&
            client2Messages.stream().anyMatch(msg -> msg.contains("roomBroadcast"))
        );

        // 6. Test Room Leave (Client 2 leaves room)
        SocketIOClient sClient2 = getServer().getClient(client2SessionId.get());
        assertNotNull(sClient2);
        sClient2.leaveRoom("testRoom");

        // Send second broadcast to "testRoom"
        getServer().getRoomOperations("testRoom").sendEvent("roomBroadcast2", "hello again");

        // Client 1 should receive it, Client 2 should NOT
        await().atMost(5, SECONDS).until(() -> 
            client1Messages.stream().anyMatch(msg -> msg.contains("roomBroadcast2"))
        );
        
        // Wait a short duration to ensure Client 2 did not receive the second broadcast
        Thread.sleep(500);
        assertTrue(client2Messages.stream().noneMatch(msg -> msg.contains("roomBroadcast2")));

        // 7. Test Disconnection
        webSocket1.close(1000, "Done");
        webSocket2.close(1000, "Done");

        await().atMost(5, SECONDS).until(() -> serverDisconnections.get() == 2);
    }
}
