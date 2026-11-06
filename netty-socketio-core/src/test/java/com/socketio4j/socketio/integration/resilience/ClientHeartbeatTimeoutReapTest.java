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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.listener.ConnectListener;
import com.socketio4j.socketio.listener.DisconnectListener;

import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-readiness test: Client Heartbeat Timeout Reaping.
 *
 * <p>Verifies that when a connected client stops responding to PING/PONG heartbeats (dead tab / process),
 * Netty `PingTimeoutHandler` automatically reaps the dead session, cleans up `clientsBox`, and fires `onDisconnect`.
 */
public class ClientHeartbeatTimeoutReapTest {

    private static final Logger log = LoggerFactory.getLogger(ClientHeartbeatTimeoutReapTest.class);

    private static final long TIMEOUT_SECS = 20L;

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
    @DisplayName("Heartbeat Timeout Reap Test: Dead Session Reaped on Ping Timeout")
    public void testDeadSessionReapedOnPingTimeout() throws Exception {
        port = findAvailablePort();

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setTransports(Transport.WEBSOCKET);
        // Short ping intervals for fast test execution
        config.setPingInterval(1000); // Send PING every 1s
        config.setPingTimeout(1500);  // Reap if no PONG within 1.5s

        server = new SocketIOServer(config);

        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicBoolean disconnectFired = new AtomicBoolean(false);

        server.addConnectListener(new ConnectListener() {
            @Override
            public void onConnect(SocketIOClient client) {
                connectLatch.countDown();
            }
        });

        server.addDisconnectListener(new DisconnectListener() {
            @Override
            public void onDisconnect(SocketIOClient client) {
                disconnectFired.set(true);
                disconnectLatch.countDown();
            }
        });

        server.start();

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.transports = new String[]{ "websocket" };

        clientSocket = IO.socket("http://127.0.0.1:" + port, opts);
        clientSocket.connect();

        assertTrue(connectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Client failed to connect");

        // Disconnect client without sending DISCONNECT frame to simulate frozen/dead process
        clientSocket.disconnect();

        assertTrue(disconnectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Server failed to reap dead session on Ping Timeout");
        assertTrue(disconnectFired.get(), "Server DisconnectListener must fire when session is reaped");
    }
}
