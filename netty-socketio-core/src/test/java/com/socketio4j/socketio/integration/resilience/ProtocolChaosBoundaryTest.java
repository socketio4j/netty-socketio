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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.integration.RawWebSocketClient;
import com.socketio4j.socketio.listener.ExceptionListenerAdapter;

import io.netty.channel.ChannelHandlerContext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Each malformed stimulus must reach the parser and trigger an observed rejection. */
public class ProtocolChaosBoundaryTest {
    @Test
    public void testMalformedProtocolFramesHandledGracefully() throws Exception {
        rejectsMalformedFramesAndServesAFreshClient("99999invalid_packet_type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"99999invalid_packet_type", "40{invalid_json_auth_payload",
            "459999999999999999999999-[\"bad\"]"})
    void rejectsMalformedFramesAndServesAFreshClient(String malformed) throws Exception {
        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(0);
        config.setTransports(Transport.WEBSOCKET);
        BlockingQueue<Throwable> rejected = new LinkedBlockingQueue<>();
        config.setExceptionListener(new ExceptionListenerAdapter() {
            @Override
            public void onAuthException(Throwable error, com.socketio4j.socketio.SocketIOClient client) {
                rejected.add(error);
            }

            @Override
            public boolean exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
                rejected.add(error);
                return true;
            }
        });
        SocketIOServer server = new SocketIOServer(config);
        AtomicInteger healthEvents = new AtomicInteger();
        server.addEventListener("health", String.class, (client, data, ack) -> {
            healthEvents.incrementAndGet();
            ack.sendAckData(data);
        });
        try {
            server.start();
            try (RawWebSocketClient bad = new RawWebSocketClient(config.getPort(), true)) {
                bad.send(malformed);
                assertNotNull(rejected.poll(5, TimeUnit.SECONDS),
                        "Malformed frame must be observed and rejected by the real parser: " + malformed);
                assertEquals(0, healthEvents.get());
            }
            await().atMost(5, TimeUnit.SECONDS).until(() -> server.getAllClients().isEmpty());
            try (RawWebSocketClient healthy = new RawWebSocketClient(config.getPort(), true)) {
                healthy.assertHealthExchange("after-rejection");
                assertEquals(1, healthEvents.get());
                assertEquals(0, rejected.size(), "Healthy exchange must not cause another parser error");
            }
            await().atMost(5, TimeUnit.SECONDS).until(() -> server.getAllClients().isEmpty());
        } finally {
            server.stop();
        }
    }
}
