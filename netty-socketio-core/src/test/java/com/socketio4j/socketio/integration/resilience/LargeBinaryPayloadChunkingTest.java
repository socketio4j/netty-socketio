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

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.listener.DataListener;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-readiness test: Large Binary Payload Chunking & Streaming.
 *
 * <p>Verifies transmitting multi-megabyte binary payloads (1MB - 2MB byte arrays)
 * across Netty channels, guaranteeing zero data corruption and exact byte-for-byte matching.
 */
public class LargeBinaryPayloadChunkingTest {

    private static final Logger log = LoggerFactory.getLogger(LargeBinaryPayloadChunkingTest.class);

    private static final long TIMEOUT_SECS = 30L;
    private static final int PAYLOAD_SIZE = 1 * 1024 * 1024; // 1 MB payload

    private SocketIOServer server;
    private int port;
    private Socket clientSocket;

    @AfterEach
    public void tearDown() {
        if (clientSocket != null) {
            try {
                clientSocket.off();
                clientSocket.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting client: {}", e.getMessage());
            }
        }

        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("Error stopping server: {}", e.getMessage());
            }
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find available TCP port", e);
        }
    }

    @Test
    @DisplayName("Large Binary Payload Test: 1MB Byte Array Framing & Verification")
    public void testLargeBinaryPayloadStreaming() throws Exception {
        port = findAvailablePort();

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setTransports(Transport.WEBSOCKET);
        config.setMaxFramePayloadLength(10 * 1024 * 1024); // 10MB limit

        server = new SocketIOServer(config);

        byte[] originalPayload = new byte[PAYLOAD_SIZE];
        new Random(42).nextBytes(originalPayload); // Deterministic binary pattern

        AtomicInteger serverReceivedSize = new AtomicInteger(0);
        CountDownLatch serverLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        server.addEventListener("binary-chunk", byte[].class, new DataListener<byte[]>() {
            @Override
            public void onData(SocketIOClient client, byte[] data, AckRequest ackSender) {
                serverReceivedSize.set(data.length);
                boolean matches = Arrays.equals(originalPayload, data);
                if (ackSender.isAckRequested()) {
                    ackSender.sendAckData(matches ? "MATCH" : "MISMATCH");
                }
                serverLatch.countDown();
            }
        });

        server.start();

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.transports = new String[]{ "websocket" };

        clientSocket = IO.socket("http://127.0.0.1:" + port, opts);
        CountDownLatch connectLatch = new CountDownLatch(1);

        clientSocket.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        clientSocket.connect();

        assertTrue(connectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Client failed to connect");

        clientSocket.emit("binary-chunk", new Object[]{ originalPayload }, new Ack() {
            @Override
            public void call(Object... args) {
                if (args.length > 0 && "MATCH".equals(args[0])) {
                    ackLatch.countDown();
                }
            }
        });

        assertTrue(serverLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Server failed to receive binary payload");
        assertTrue(ackLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Client ACK failed or returned MISMATCH");

        assertEquals(PAYLOAD_SIZE, serverReceivedSize.get(), "Server received binary size mismatch");
    }
}
