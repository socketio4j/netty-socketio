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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;

public class EventStorePartitionRoutingTest {

    private final EventStore testStore = new EventStore() {
        @Override
        public void publish0(EventType type, EventMessage msg) {}

        @Override
        public <T extends EventMessage> void subscribe0(EventType type, EventListener<T> listener, Class<T> clazz) {}

        @Override
        public void unsubscribe0(EventType type) {}

        @Override
        public void shutdown0() {}
    };

    @Test
    void testDefaultPartitionCount() {
        assertEquals(16, EventStore.DEFAULT_PARTITION_COUNT);
        assertEquals(16, testStore.getPartitionCount());
    }

    @Test
    void testPartitionedChannelLifecycleRouting() {
        String prefix = "SOCKETIO4J:";
        ConnectMessage connectMsg = new ConnectMessage(UUID.randomUUID());

        String channel = testStore.resolveChannelName(
                prefix, EventType.CONNECT, connectMsg, 16, EventStoreMode.PARTITIONED_CHANNEL);
        assertEquals("SOCKETIO4J:lifecycle", channel);
    }

    @Test
    void testPartitionedChannelRoomDeterministicRouting() {
        String prefix = "SOCKETIO4J:";
        String room = "trading-room-42";
        int p = (room.hashCode() & 0x7FFFFFFF) % 16;
        String expectedChannel = "SOCKETIO4J:room_" + p;

        UUID uid = UUID.randomUUID();
        JoinMessage join = new JoinMessage(uid, room, "/chat");
        LeaveMessage leave = new LeaveMessage(uid, room, "/chat");

        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setData("price_update");
        DispatchMessage dispatch = new DispatchMessage(room, packet, "/chat");

        assertEquals(expectedChannel, testStore.resolveChannelName(
                prefix, EventType.JOIN, join, 16, EventStoreMode.PARTITIONED_CHANNEL));
        assertEquals(expectedChannel, testStore.resolveChannelName(
                prefix, EventType.LEAVE, leave, 16, EventStoreMode.PARTITIONED_CHANNEL));
        assertEquals(expectedChannel, testStore.resolveChannelName(
                prefix, EventType.DISPATCH, dispatch, 16, EventStoreMode.PARTITIONED_CHANNEL));
    }

    @Test
    void testPartitionedChannelDistribution() {
        String prefix = "";
        int partitions = 16;
        Set<String> hitPartitions = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            JoinMessage join = new JoinMessage(UUID.randomUUID(), "room-" + i, "/chat");
            String channel = testStore.resolveChannelName(
                    prefix, EventType.JOIN, join, partitions, EventStoreMode.PARTITIONED_CHANNEL);
            assertTrue(channel.startsWith("room_"));
            hitPartitions.add(channel);
        }

        // With 500 rooms hashed mod 16, every single partition should be populated
        assertEquals(16, hitPartitions.size());
    }

    @Test
    void testCustomPartitionCount() {
        String prefix = "CUSTOM:";
        int customPartitions = 32;

        for (int i = 0; i < 100; i++) {
            String room = "room-" + i;
            int p = (room.hashCode() & 0x7FFFFFFF) % customPartitions;
            JoinMessage msg = new JoinMessage(UUID.randomUUID(), room, "/");
            String channel = testStore.resolveChannelName(
                    prefix, EventType.JOIN, msg, customPartitions, EventStoreMode.PARTITIONED_CHANNEL);
            assertEquals("CUSTOM:room_" + p, channel);
            assertTrue(p >= 0 && p < customPartitions);
        }
    }

    @Test
    void testNegativeHashImmunity() {
        // String with Integer.MIN_VALUE hashCode simulation
        EventMessage msgWithMinHash = new EventMessage() {
            @Override
            public String getType() { return "DISPATCH"; }
            @Override
            public String getPartitionKey() {
                // Return a key that produces negative hashCode or test bitmask behavior
                return "polygenelubricants";
            }
        };

        String channel = testStore.resolveChannelName(
                "APP:", EventType.DISPATCH, msgWithMinHash, 16, EventStoreMode.PARTITIONED_CHANNEL);
        assertTrue(channel.startsWith("APP:room_"), "Channel should start with APP:room_");
        int partition = Integer.parseInt(channel.replace("APP:room_", ""));
        assertTrue(partition >= 0 && partition < 16, "Partition must be non-negative");
    }

    @Test
    void testResolveSubscriptionChannels() {
        String prefix = "APP:";
        int partitionCount = 8;

        List<String> channels = testStore.resolveSubscriptionChannels(
                prefix, EventType.DISPATCH, partitionCount, EventStoreMode.PARTITIONED_CHANNEL);

        assertNotNull(channels);
        // 8 room partitions + 1 lifecycle channel = 9 channels
        assertEquals(9, channels.size());
        for (int i = 0; i < partitionCount; i++) {
            assertTrue(channels.contains("APP:room_" + i));
        }
        assertTrue(channels.contains("APP:lifecycle"));
    }

    @Test
    void testSingleAndMultiChannelResolution() {
        String prefix = "SOCKETIO4J:";
        JoinMessage join = new JoinMessage(UUID.randomUUID(), "r1", "/");

        // Single channel
        String singlePub = testStore.resolveChannelName(
                prefix, EventType.JOIN, join, 16, EventStoreMode.SINGLE_CHANNEL);
        assertEquals("SOCKETIO4J:ALL_SINGLE_CHANNEL", singlePub);

        List<String> singleSub = testStore.resolveSubscriptionChannels(
                prefix, EventType.JOIN, 16, EventStoreMode.SINGLE_CHANNEL);
        assertEquals(1, singleSub.size());
        assertEquals("SOCKETIO4J:ALL_SINGLE_CHANNEL", singleSub.get(0));

        // Multi channel
        String multiPub = testStore.resolveChannelName(
                prefix, EventType.JOIN, join, 16, EventStoreMode.MULTI_CHANNEL);
        assertEquals("SOCKETIO4J:JOIN", multiPub);

        List<String> multiSub = testStore.resolveSubscriptionChannels(
                prefix, EventType.JOIN, 16, EventStoreMode.MULTI_CHANNEL);
        assertEquals(1, multiSub.size());
        assertEquals("SOCKETIO4J:JOIN", multiSub.get(0));
    }
}
