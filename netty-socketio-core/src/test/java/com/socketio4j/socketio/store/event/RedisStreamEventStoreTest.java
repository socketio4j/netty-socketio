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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;

import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.container.CustomizedRedisContainer;
import com.socketio4j.socketio.store.redis_stream.RedisStreamEventStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-grade integration tests for {@link RedisStreamEventStore} using Testcontainers.
 */
public class RedisStreamEventStoreTest extends AbstractEventStoreTest {

    private final List<RedissonClient> createdClients = new ArrayList<>();
    private String currentTestPrefix = "test_stream_";

    @org.junit.jupiter.api.BeforeEach
    @Override
    public void setUp() throws Exception {
        currentTestPrefix = "test_stream_" + System.nanoTime() + "_";
        super.setUp();
    }

    @Override
    protected GenericContainer<?> createContainer() {
        return new CustomizedRedisContainer();
    }

    @Override
    protected EventStore createEventStore(Long nodeId) throws Exception {
        CustomizedRedisContainer customizedRedisContainer = (CustomizedRedisContainer) container;
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + customizedRedisContainer.getHost() + ":" + customizedRedisContainer.getRedisPort());

        RedissonClient redissonPub = Redisson.create(config);
        RedissonClient redissonSub = Redisson.create(config);
        createdClients.add(redissonPub);
        createdClients.add(redissonSub);

        return new RedisStreamEventStore.Builder(redissonPub, redissonSub)
                .nodeId(nodeId)
                .eventStoreMode(EventStoreMode.MULTI_CHANNEL)
                .prefix(currentTestPrefix)
                .streamMaxLength(10000)
                .build();
    }

    @Override
    protected void closeClients() {
        for (RedissonClient client : createdClients) {
            try {
                if (client != null && !client.isShutdown()) {
                    client.shutdown();
                }
            } catch (Exception ignored) {
            }
        }
        createdClients.clear();
    }

    /**
     * Verifies that messages published immediately during/after subscription
     * on a fresh (cold-start) stream are never lost due to initialization races.
     */
    @Test
    public void testRapidPublishImmediatelyAfterSubscribe() throws InterruptedException {
        int messageCount = 100;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger receivedCount = new AtomicInteger(0);

        subscriberStore.subscribe(
                EventType.DISPATCH,
                msg -> {
                    if (!subscriberNodeId.equals(msg.getNodeId())) {
                        receivedCount.incrementAndGet();
                        latch.countDown();
                    }
                },
                DispatchMessage.class
        );

        // Immediately burst publish without delay
        for (int i = 0; i < messageCount; i++) {
            Packet packet = new Packet(PacketType.MESSAGE);
            packet.setSubType(PacketType.EVENT);
            packet.setName("rapid-event");
            packet.setNsp("/");
            packet.setData("msg-" + i);

            DispatchMessage outgoing = new DispatchMessage("room1", packet, "/");
            outgoing.setNodeId(publisherNodeId);
            publisherStore.publish(EventType.DISPATCH, outgoing);
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                "All " + messageCount + " rapid messages should be received without loss. Received: " + receivedCount.get());
        assertEquals(messageCount, receivedCount.get());
    }

    /**
     * Verifies that re-subscribing after unsubscribe works seamlessly and re-arms polling.
     */
    @Test
    public void testResubscribeAfterUnsubscribe() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        subscriberStore.subscribe(
                EventType.DISPATCH,
                msg -> {
                    if (!subscriberNodeId.equals(msg.getNodeId())) {
                        latch1.countDown();
                    }
                },
                DispatchMessage.class
        );

        Packet p1 = new Packet(PacketType.MESSAGE);
        p1.setSubType(PacketType.EVENT);
        p1.setName("event-1");
        p1.setNsp("/");
        p1.setData("first");
        DispatchMessage msg1 = new DispatchMessage("room1", p1, "/");
        msg1.setNodeId(publisherNodeId);
        publisherStore.publish(EventType.DISPATCH, msg1);

        assertTrue(latch1.await(5, TimeUnit.SECONDS), "First message must be received");

        // Unsubscribe
        subscriberStore.unsubscribe(EventType.DISPATCH);

        // Re-subscribe
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<String> receivedData = new AtomicReference<>();
        subscriberStore.subscribe(
                EventType.DISPATCH,
                msg -> {
                    if (!subscriberNodeId.equals(msg.getNodeId())) {
                        receivedData.set((String) msg.getPacket().getData());
                        latch2.countDown();
                    }
                },
                DispatchMessage.class
        );

        Packet p2 = new Packet(PacketType.MESSAGE);
        p2.setSubType(PacketType.EVENT);
        p2.setName("event-2");
        p2.setNsp("/");
        p2.setData("second");
        DispatchMessage msg2 = new DispatchMessage("room1", p2, "/");
        msg2.setNodeId(publisherNodeId);
        publisherStore.publish(EventType.DISPATCH, msg2);

        assertTrue(latch2.await(5, TimeUnit.SECONDS), "Message after re-subscribe must be received");
        assertEquals("second", receivedData.get());
    }

    /**
     * Verifies that a throwing listener does not crash the poller or prevent
     * subsequent listeners and messages from being processed.
     */
    @Test
    public void testFaultyListenerDoesNotCrashPoller() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger healthyCallCount = new AtomicInteger(0);

        // Faulty listener that throws an unhandled RuntimeException
        subscriberStore.subscribe(
                EventType.DISPATCH,
                msg -> {
                    throw new RuntimeException("Simulated listener exception");
                },
                DispatchMessage.class
        );

        // Healthy listener on the same topic
        subscriberStore.subscribe(
                EventType.DISPATCH,
                msg -> {
                    if (!subscriberNodeId.equals(msg.getNodeId())) {
                        healthyCallCount.incrementAndGet();
                        latch.countDown();
                    }
                },
                DispatchMessage.class
        );

        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setName("fault-test");
        packet.setNsp("/");
        packet.setData("data");

        DispatchMessage outgoing = new DispatchMessage("room1", packet, "/");
        outgoing.setNodeId(publisherNodeId);
        publisherStore.publish(EventType.DISPATCH, outgoing);

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Healthy listener should still receive message despite faulty listener throwing");
        assertEquals(1, healthyCallCount.get());
    }
}
