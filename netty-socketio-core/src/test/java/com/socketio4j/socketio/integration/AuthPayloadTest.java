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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.AuthTokenResult;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.listener.DataListener;

import io.socket.client.IO;
import io.socket.client.Socket;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for SocketIO authentication payload functionality.
 * Tests authentication payload handling during connection as specified in SocketIO protocol v5.
 */
@DisplayName("Authentication Payload Tests - SocketIO Protocol CONNECT with Auth")
public class AuthPayloadTest extends AbstractSocketIOIntegrationTest {
    private static final String authUserIdKey = "userId";
    private static final String authUserId = "itest-auth-user";
    private static final String authUserPasswordKey = "password";
    private static final String authUserPassword = "itest-auth-secret";

    private static void configureAuthTestClient(IO.Options options) {
        options.forceNew = true;
        options.reconnection = false;
        options.transports = new String[] {"websocket"};
    }

    @Override
    protected void additionalSetup() throws Exception {
        super.additionalSetup();
        getServer().getAllNamespaces().forEach(ns -> {
            ns.addAuthTokenListener((authToken, client) -> {
                if (authToken instanceof Map) {
                    Map<?, ?> authMap = (Map<?, ?>) authToken;
                    Object userId = authMap.get(authUserIdKey);
                    Object password = authMap.get(authUserPasswordKey);
                    if (authUserId.equals(userId == null ? null : String.valueOf(userId))
                            && authUserPassword.equals(password == null ? null : String.valueOf(password))) {
                        return AuthTokenResult.AUTH_TOKEN_RESULT_SUCCESS;
                    }
                }
                return new AuthTokenResult(false, "Invalid authentication payload");
            });
        });
    }

    @Test
    @DisplayName("Should connect successfully with authentication payload")
    public void testConnectionWithAuthPayload() throws Exception {
        // Test connection with authentication payload
        AtomicReference<SocketIOClient> connectedClient = new AtomicReference<>();
        AtomicBoolean receivedEvent = new AtomicBoolean(false);

        getServer().addConnectListener(connectedClient::set);

        String testEventName = generateEventName();

        getServer().addEventListener(testEventName, String.class, new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String data, AckRequest ackSender) throws Exception {
                receivedEvent.set(true);
            }
        });

        // Create client with auth payload
        Socket client;
        try {
            IO.Options options = new IO.Options();
            configureAuthTestClient(options);
            options.auth = new HashMap<>();
            options.auth.put(authUserIdKey, authUserId);
            options.auth.put(authUserPasswordKey, authUserPassword);

            client = IO.socket("http://localhost:" + getServerPort(), options);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket client", e);
        }

        client.connect();
        await().atMost(15, SECONDS).until(() -> connectedClient.get() != null);
        await().atMost(15, SECONDS).until(client::connected);

        // Emit after the client handshake completes so the packet is not dropped
        client.emit(testEventName, faker.address().fullAddress());

        // Wait for event reception
        await().atMost(15, SECONDS).until(receivedEvent::get);

        // Verify connection succeeded
        assertNotNull(connectedClient.get(), "Client should be connected");
        // Verify event was received
        assertTrue(receivedEvent.get(), "Should receive event with valid auth");
        // Verify client connection state
        assertTrue(client.connected());

        client.disconnect();
    }

    @Test
    @DisplayName("Should handle connection with empty authentication payload")
    public void testConnectionWithEmptyAuthPayload() throws Exception {
        AtomicBoolean receivedEvent = new AtomicBoolean(false);

        String testEventName = generateEventName();

        getServer().addEventListener(
                testEventName, String.class,
                (client, data, ackSender) -> receivedEvent.set(true)
        );

        // Create client with empty auth payload
        Socket client;
        try {
            IO.Options options = new IO.Options();
            configureAuthTestClient(options);
            options.auth = new HashMap<>();
            client = IO.socket("http://localhost:" + getServerPort(), options);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket client", e);
        }

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.on(Socket.EVENT_CONNECT, args -> handshakeLatch.countDown());
        client.on(Socket.EVENT_CONNECT_ERROR, args -> handshakeLatch.countDown());
        client.connect();
        assertTrue(
                handshakeLatch.await(15, SECONDS),
                "Socket.IO handshake should settle (connect or connect_error)");

        client.emit(testEventName, generateTestData());

        await().atMost(15, SECONDS).until(() -> !client.connected());
        assertFalse(receivedEvent.get(), "Should not receive event with empty auth");
        assertFalse(client.connected());

        client.disconnect();
    }
}
