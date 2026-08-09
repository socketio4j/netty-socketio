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
package com.socketio4j.socketio.integration.resilience;
import com.socketio4j.socketio.integration.protocol.AbstractSharedSocketIOIntegrationTest;
import com.socketio4j.socketio.integration.protocol.SharedServerFixtureProfile;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class AbruptDisconnectBinaryUploadIntegrationTest extends AbstractSharedSocketIOIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbruptDisconnectBinaryUploadIntegrationTest.class);

    @Override
    protected SharedServerFixtureProfile sharedServerFixtureProfile() {
        return SharedServerFixtureProfile.FAST_DISCONNECT_NIO;
    }

    @Test
    void testAbruptDisconnectDuringBinaryAttachmentUploadHandledCleanly() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicBoolean secondClientConnected = new AtomicBoolean(false);

        getServer().addConnectListener(client -> {
            log.info("Client connected: {}", client.getSessionId());
            connectLatch.countDown();
        });

        getServer().addDisconnectListener(client -> {
            log.info("Client disconnected: {}", client.getSessionId());
            disconnectLatch.countDown();
        });

        // 1. Establish initial polling client connection
        io.socket.client.Socket client = createClient(new String[]{"polling"});
        CountDownLatch clientConnectLatch = new CountDownLatch(1);
        client.on(io.socket.client.Socket.EVENT_CONNECT, args -> clientConnectLatch.countDown());
        client.connect();
        assertTrue(clientConnectLatch.await(5, TimeUnit.SECONDS),
                "Client failed to complete the Socket.IO handshake");
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Client failed to connect");

        int port = getServerPort();

        // 2. Open a raw TCP socket to send an incomplete binary upload POST payload, then abruptly drop TCP connection
        try (Socket rawSocket = new Socket("127.0.0.1", port)) {
            OutputStream out = rawSocket.getOutputStream();

            // Send Engine.IO v4 POST payload header with binary event expecting attachment (1-), but NO attachment data frame
            String httpPost = "POST /socket.io/?EIO=4&transport=polling&sid=" + client.id() + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: text/plain;charset=UTF-8\r\n"
                    + "Content-Length: 100\r\n" // Claim longer length than sent
                    + "\r\n"
                    + "451-[\"upload\",{\"_placeholder\":true,\"num\":0}]"; // Partial payload without attachment

            out.write(httpPost.getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(100);

            // Abruptly close TCP socket without completing HTTP request body
            rawSocket.close();
        }

        // Trigger client disconnect to release session and pending binary resources
        client.disconnect();

        // Wait for disconnect event to trigger on server
        assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS), "Server did not detect client disconnection");

        // 3. Connect a new second client to verify server remains fully functional
        CountDownLatch secondConnectLatch = new CountDownLatch(1);
        io.socket.client.Socket client2 = createClient(new String[]{"polling"});
        client2.on(io.socket.client.Socket.EVENT_CONNECT, args -> {
            secondClientConnected.set(true);
            secondConnectLatch.countDown();
        });
        client2.connect();

        assertTrue(secondConnectLatch.await(5, TimeUnit.SECONDS), "Second client failed to connect after abrupt disconnect");
        assertTrue(secondClientConnected.get(), "Second client should connect cleanly");

        client2.disconnect();
    }
}
