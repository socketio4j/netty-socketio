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
package com.socketio4j.socketio.store.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;

public class EventMessageJsonSupportTest {

    private static class EmptyBean {
        // No public fields or getters
    }

    @Test
    public void testSerializeEmptyBeanPayload() {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setName("emptyEvent");
        packet.setData(new EmptyBean());

        DispatchMessage msg = new DispatchMessage("room1", packet, "node1");

        assertDoesNotThrow(() -> {
            byte[] bytes = mapper.writeValueAsBytes(msg);
            assertNotNull(bytes);
            assertTrue(bytes.length > 0);
        });
    }
}
