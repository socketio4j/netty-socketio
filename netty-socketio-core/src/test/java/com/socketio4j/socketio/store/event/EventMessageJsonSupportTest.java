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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;

public class EventMessageJsonSupportTest {

    private static class EmptyBean {
        // No public fields or getters
    }

    @Test
    public void testSerializeEmptyBeanPayload() {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        Packet packet = new Packet(PacketType.MESSAGE);
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
    @Test
    void shouldRoundTripUntypedByteArray() throws Exception {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        Map<String, Object> payload = new HashMap<>();
        byte[] bytes = {1, 2, 3, 4, 5};
        payload.put("data", bytes);

        String json = mapper.writeValueAsString(payload);

        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = mapper.readValue(json, Map.class);

        assertInstanceOf(byte[].class, decoded.get("data"));
        assertArrayEquals(bytes, (byte[]) decoded.get("data"));
    }
    @Test
    void shouldRoundTripDispatchMessageWithBinaryPayload() throws Exception {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        Packet packet = new Packet(PacketType.BINARY_EVENT);
        packet.setData(new Object[] {
                "text",
                new byte[] {1, 2, 3, 4, 5}
        });

        DispatchMessage message = new DispatchMessage("room", packet, "/");

        String json = mapper.writeValueAsString(message);

        DispatchMessage decoded = mapper.readValue(json, DispatchMessage.class);

        assertInstanceOf(java.util.List.class, decoded.getPacket().getData());

        @SuppressWarnings("unchecked")
        java.util.List<Object> data = (java.util.List<Object>) decoded.getPacket().getData();

        assertEquals(2, data.size());
        assertEquals("text", data.get(0));
        assertInstanceOf(byte[].class, data.get(1));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, (byte[]) data.get(1));
    }
    @Test
    void shouldRoundTripNestedBinaryPayload() throws Exception {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        Map<String, Object> nested = new HashMap<>();
        nested.put("bytes", new byte[] {9, 8, 7});

        Map<String, Object> root = new HashMap<>();
        root.put("nested", nested);

        String json = mapper.writeValueAsString(root);

        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = mapper.readValue(json, Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> decodedNested =
                (Map<String, Object>) decoded.get("nested");

        assertInstanceOf(byte[].class, decodedNested.get("bytes"));
        assertArrayEquals(new byte[] {9, 8, 7}, (byte[]) decodedNested.get("bytes"));
    }
    @Test
    void shouldRoundTripBinaryPayloadInsideList() throws Exception {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        Map<String, Object> payload = new HashMap<>();
        payload.put("list", java.util.Arrays.asList(
                "text",
                new byte[] {1, 2, 3}
        ));

        String json = mapper.writeValueAsString(payload);

        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = mapper.readValue(json, Map.class);

        @SuppressWarnings("unchecked")
        java.util.List<Object> list =
                (java.util.List<Object>) decoded.get("list");

        assertEquals("text", list.get(0));
        assertInstanceOf(byte[].class, list.get(1));
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) list.get(1));
    }
    private static class TypedBytesHolder {
        private byte[] data;

        public byte[] getData() {
            return data;
        }

        public void setData(byte[] data) {
            this.data = data;
        }
    }

    @Test
    void shouldRoundTripTypedByteArray() throws Exception {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        TypedBytesHolder holder = new TypedBytesHolder();
        holder.setData(new byte[] {1, 2, 3});

        String json = mapper.writeValueAsString(holder);

        TypedBytesHolder decoded = mapper.readValue(json, TypedBytesHolder.class);

        assertArrayEquals(holder.getData(), decoded.getData());
    }

    @Test
    void shouldDeserializeTypedByteArrayFromBase64String() throws Exception {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        String json = "{\"data\":\"AQID\"}";

        TypedBytesHolder decoded = mapper.readValue(json, TypedBytesHolder.class);

        assertNotNull(decoded.getData());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.getData());
    }

    @Test
    void shouldDeserializeTypedByteArrayFromNumericArray() throws Exception {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();

        String json = "{\"data\":[1,2,3]}";

        TypedBytesHolder decoded = mapper.readValue(json, TypedBytesHolder.class);

        assertNotNull(decoded.getData());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.getData());
    }

    @Test
    void shouldNotSerializePartitionKeyPropertyIntoJson() throws Exception {
        ObjectMapper mapper = EventMessageJsonSupport.createObjectMapper();
        java.util.UUID uid = java.util.UUID.randomUUID();

        JoinMessage join = new JoinMessage(uid, "chat-room-101", "/chat");
        String joinJson = mapper.writeValueAsString(join);
        assertTrue(!joinJson.contains("partitionKey"), "partitionKey must not be serialized into JSON for JoinMessage");
        assertEquals("chat-room-101", join.getPartitionKey());

        LeaveMessage leave = new LeaveMessage(uid, "chat-room-101", "/chat");
        String leaveJson = mapper.writeValueAsString(leave);
        assertTrue(!leaveJson.contains("partitionKey"), "partitionKey must not be serialized into JSON for LeaveMessage");
        assertEquals("chat-room-101", leave.getPartitionKey());

        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setData("hello");
        DispatchMessage dispatch = new DispatchMessage("chat-room-101", packet, "/chat");
        String dispatchJson = mapper.writeValueAsString(dispatch);
        assertTrue(!dispatchJson.contains("partitionKey"), "partitionKey must not be serialized into JSON for DispatchMessage");
        assertEquals("chat-room-101", dispatch.getPartitionKey());

        BulkJoinMessage bulkJoin = new BulkJoinMessage(uid, new java.util.HashSet<>(java.util.Arrays.asList("roomA", "roomB")), "/chat");
        String bulkJoinJson = mapper.writeValueAsString(bulkJoin);
        assertTrue(!bulkJoinJson.contains("partitionKey"), "partitionKey must not be serialized into JSON for BulkJoinMessage");
        assertEquals("roomA", bulkJoin.getPartitionKey());

        BulkLeaveMessage bulkLeave = new BulkLeaveMessage(uid, new java.util.HashSet<>(java.util.Arrays.asList("roomA", "roomB")), "/chat");
        String bulkLeaveJson = mapper.writeValueAsString(bulkLeave);
        assertTrue(!bulkLeaveJson.contains("partitionKey"), "partitionKey must not be serialized into JSON for BulkLeaveMessage");
        assertEquals("roomA", bulkLeave.getPartitionKey());
    }
}
