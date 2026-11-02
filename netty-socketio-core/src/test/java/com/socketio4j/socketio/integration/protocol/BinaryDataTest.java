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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BinaryDataTest extends AbstractSharedSocketIOIntegrationTest {
    @Test
    void binaryEventAndAckPreserveAllByteValues() throws Exception {
        String event = generateEventName("binary");
        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<Object[]> response = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch acked = new CountDownLatch(1);
        getServer().addEventListener(event, byte[].class, (client, data, ack) -> {
            received.set(data);
            ack.sendAckData(data);
        });
        Socket client = createClient();
        try {
            client.on(Socket.EVENT_CONNECT, args -> connected.countDown());
            client.connect();
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            client.emit(event, new Object[]{payload}, args -> {
                response.set(args);
                acked.countDown();
            });
            assertTrue(acked.await(5, TimeUnit.SECONDS), "Actual binary ACK must arrive");
            assertArrayEquals(payload, received.get());
            assertEquals(1, response.get().length);
            assertTrue(response.get()[0] instanceof byte[]);
            assertArrayEquals(payload, (byte[]) response.get()[0]);
        } finally {
            client.disconnect();
            client.close();
        }
    }
}
