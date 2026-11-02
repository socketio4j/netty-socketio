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

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.integration.RawWebSocketClient;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A connected raw peer withholds PONG; no client disconnect API is called. */
public class ClientHeartbeatTimeoutReapTest {
    @Test
    public void testDeadSessionReapedOnPingTimeout() throws Exception {
        reapsOnlyThePeerThatWithholdsPong();
    }

    @Test
    void reapsOnlyThePeerThatWithholdsPong() throws Exception {
        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(0);
        config.setTransports(Transport.WEBSOCKET);
        config.setPingInterval(200);
        config.setPingTimeout(600);
        SocketIOServer server = new SocketIOServer(config);
        java.util.concurrent.CountDownLatch reaped = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<UUID> disconnected = new AtomicReference<>();
        server.addDisconnectListener(client -> { disconnected.set(client.getSessionId()); reaped.countDown(); });
        server.addConnectListener(client -> client.joinRoom("heartbeat-room"));
        server.addEventListener("health", String.class, (client, data, ack) -> ack.sendAckData(data));
        try {
            server.start();
            try (RawWebSocketClient healthy = new RawWebSocketClient(config.getPort(), true);
                    RawWebSocketClient stalled = new RawWebSocketClient(config.getPort(), false)) {
                UUID stalledId = UUID.fromString(stalled.sessionId());
                SocketIOClient stalledClient = server.getClient(stalledId);
                assertTrue(stalledClient.getAllRooms().contains("heartbeat-room"));
                healthy.assertHealthExchange("before-timeout");
                stalled.awaitFrame("2"::equals);
                long pingObserved = System.nanoTime();
                assertTrue(reaped.await(5, TimeUnit.SECONDS), "Unresponsive live peer must be reaped by the heartbeat deadline");
                assertEquals(stalledId, disconnected.get());
                assertTrue(System.nanoTime() - pingObserved >= TimeUnit.MILLISECONDS.toNanos(400),
                        "Peer must remain connected until the heartbeat deadline, not close immediately");
                assertNull(server.getClient(stalledId));
                assertTrue(stalledClient.getAllRooms().isEmpty());
                assertEquals(1, server.getAllClients().size(), "Healthy peer must survive the stalled peer timeout");
                healthy.assertHealthExchange("after-timeout");
            }
            await().atMost(5, TimeUnit.SECONDS).until(() -> server.getAllClients().isEmpty());
        } finally {
            server.stop();
        }
    }
}
