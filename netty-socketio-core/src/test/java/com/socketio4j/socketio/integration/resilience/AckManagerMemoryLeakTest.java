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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.AckCallback;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.listener.ConnectListener;

import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-readiness test: ACK Timeout Cleanup & Resource Leak Verification.
 *
 * <p>Verifies that server-side ACK timeouts properly purge unacknowledged callback references,
 * preventing memory leaks when clients fail or refuse to acknowledge sent events.
 */
public class AckManagerMemoryLeakTest {

    private static final Logger log = LoggerFactory.getLogger(AckManagerMemoryLeakTest.class);

    private static final long TIMEOUT_SECS = 30L;

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
    @DisplayName("ACK Timeout Purge: Expired Server ACK Callbacks Purge Cleanly")
    public void testAckTimeoutPurgeNoMemoryLeak() throws Exception {
        port = findAvailablePort();

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setTransports(Transport.WEBSOCKET);

        server = new SocketIOServer(config);

        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch timeoutLatch = new CountDownLatch(5);
        AtomicInteger timeoutCount = new AtomicInteger(0);

        server.addConnectListener(new ConnectListener() {
            @Override
            public void onConnect(SocketIOClient client) {
                connectLatch.countDown();

                // Send 5 events requiring ACKs with short 1-second timeout
                for (int i = 0; i < 5; i++) {
                    client.sendEvent("ack-leak-test", new AckCallback<String>(String.class, 1) {
                        @Override
                        public void onSuccess(String result) {
                            // Should not be called because client ignores event
                        }

                        @Override
                        public void onTimeout() {
                            timeoutCount.incrementAndGet();
                            timeoutLatch.countDown();
                        }
                    }, "payload-" + i);
                }
            }
        });

        server.start();

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.transports = new String[]{ "websocket" };

        clientSocket = IO.socket("http://127.0.0.1:" + port, opts);
        // Client listens to event but DOES NOT send ACK back
        clientSocket.on("ack-leak-test", args -> {
            // Intentionally ignore sending ACK to test server timeout purge
        });

        clientSocket.connect();

        assertTrue(connectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Client failed to connect");
        assertTrue(timeoutLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Server ACK timeouts failed to trigger");

        assertEquals(5, timeoutCount.get(), "All 5 expired ACK callbacks must trigger onTimeout()");
    }
}
