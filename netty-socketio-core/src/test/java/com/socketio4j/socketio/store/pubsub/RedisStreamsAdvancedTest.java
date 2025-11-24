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
package com.socketio4j.socketio.store.pubsub;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;

import com.socketio4j.socketio.store.CustomizedRedisContainer;
import com.socketio4j.socketio.store.RedisStreamsPubSubStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Advanced tests for RedisStreamsPubSubStore covering edge cases,
 * concurrent operations, and stream-specific behavior.
 */
public class RedisStreamsAdvancedTest {

    private GenericContainer<?> container;
    private RedissonClient redissonClient;
    private PubSubStore store1;
    private PubSubStore store2;
    private PubSubStore store3;

    @BeforeEach
    public void setUp() throws Exception {
        container = new CustomizedRedisContainer();
        container.start();

        CustomizedRedisContainer customizedRedisContainer = (CustomizedRedisContainer) container;
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + customizedRedisContainer.getHost() + ":" + customizedRedisContainer.getRedisPort());

        redissonClient = Redisson.create(config);
        
        // Create three stores with different node IDs
        store1 = new RedisStreamsPubSubStore(1L, redissonClient);
        store2 = new RedisStreamsPubSubStore(2L, redissonClient);
        store3 = new RedisStreamsPubSubStore(3L, redissonClient);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (store1 != null) {
            store1.shutdown();
        }
        if (store2 != null) {
            store2.shutdown();
        }
        if (store3 != null) {
            store3.shutdown();
        }
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    @Test
    public void testMultipleSubscribersReceiveMessage() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<TestMessage> received1 = new AtomicReference<>();
        AtomicReference<TestMessage> received2 = new AtomicReference<>();

        // Two different nodes subscribe
        store1.subscribe(PubSubType.DISPATCH, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                received1.set(msg);
                latch1.countDown();
            }
        }, TestMessage.class);

        store2.subscribe(PubSubType.DISPATCH, msg -> {
            if (!msg.getNodeId().equals(2L)) {
                received2.set(msg);
                latch2.countDown();
            }
        }, TestMessage.class);

        // Third node publishes
        TestMessage testMessage = new TestMessage();
        testMessage.setContent("broadcast message");
        store3.publish(PubSubType.DISPATCH, testMessage);

        // Both should receive
        assertTrue(latch1.await(5, TimeUnit.SECONDS), "Store1 should receive message");
        assertTrue(latch2.await(5, TimeUnit.SECONDS), "Store2 should receive message");

        assertEquals("broadcast message", received1.get().getContent());
        assertEquals("broadcast message", received2.get().getContent());
        assertEquals(3L, received1.get().getNodeId());
        assertEquals(3L, received2.get().getNodeId());
    }

    @Test
    public void testNodeDoesNotReceiveOwnMessages() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TestMessage> received = new AtomicReference<>();

        // Subscribe on store1
        store1.subscribe(PubSubType.CONNECT, msg -> {
            received.set(msg);
            latch.countDown();
        }, TestMessage.class);

        // Publish from same store (node1)
        TestMessage testMessage = new TestMessage();
        testMessage.setContent("self message");
        store1.publish(PubSubType.CONNECT, testMessage);

        // Should not receive own message
        assertFalse(latch.await(2, TimeUnit.SECONDS), "Should not receive own message");
        assertEquals(null, received.get());
    }

    @Test
    public void testMultipleMessagesInSequence() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger messageCount = new AtomicInteger(0);

        store1.subscribe(PubSubType.DISPATCH, msg -> {
            messageCount.incrementAndGet();
            latch.countDown();
        }, TestMessage.class);

        // Publish multiple messages
        for (int i = 0; i < 3; i++) {
            TestMessage msg = new TestMessage();
            msg.setContent("message " + i);
            store2.publish(PubSubType.DISPATCH, msg);
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All messages should be received");
        assertEquals(3, messageCount.get());
    }

    @Test
    public void testResubscribeAfterUnsubscribe() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<TestMessage> received1 = new AtomicReference<>();
        AtomicReference<TestMessage> received2 = new AtomicReference<>();

        // First subscription
        store1.subscribe(PubSubType.JOIN, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                received1.set(msg);
                latch1.countDown();
            }
        }, TestMessage.class);

        TestMessage msg1 = new TestMessage();
        msg1.setContent("first message");
        store2.publish(PubSubType.JOIN, msg1);

        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        assertNotNull(received1.get());

        // Unsubscribe
        store1.unsubscribe(PubSubType.JOIN);

        // Re-subscribe
        store1.subscribe(PubSubType.JOIN, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                received2.set(msg);
                latch2.countDown();
            }
        }, TestMessage.class);

        TestMessage msg2 = new TestMessage();
        msg2.setContent("second message");
        store2.publish(PubSubType.JOIN, msg2);

        assertTrue(latch2.await(5, TimeUnit.SECONDS));
        assertNotNull(received2.get());
        assertEquals("second message", received2.get().getContent());
    }

    @Test
    public void testConcurrentPublishFromMultipleNodes() throws InterruptedException {
        final int messageCount = 10;
        CountDownLatch latch = new CountDownLatch(messageCount * 2); // store1 receives from store2 and store3
        AtomicInteger receivedCount = new AtomicInteger(0);

        store1.subscribe(PubSubType.DISPATCH, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                receivedCount.incrementAndGet();
                latch.countDown();
            }
        }, TestMessage.class);

        // Publish from store2
        Thread thread2 = new Thread(() -> {
            for (int i = 0; i < messageCount; i++) {
                TestMessage msg = new TestMessage();
                msg.setContent("from node2: " + i);
                store2.publish(PubSubType.DISPATCH, msg);
            }
        });

        // Publish from store3
        Thread thread3 = new Thread(() -> {
            for (int i = 0; i < messageCount; i++) {
                TestMessage msg = new TestMessage();
                msg.setContent("from node3: " + i);
                store3.publish(PubSubType.DISPATCH, msg);
            }
        });

        thread2.start();
        thread3.start();

        assertTrue(latch.await(15, TimeUnit.SECONDS), "All messages should be received");
        assertEquals(messageCount * 2, receivedCount.get());

        thread2.join();
        thread3.join();
    }

    @Test
    public void testShutdownCleansUpResources() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        store1.subscribe(PubSubType.CONNECT, msg -> {
            latch.countDown();
        }, TestMessage.class);

        // Shutdown the store
        store1.shutdown();

        // Try to publish after shutdown
        TestMessage msg = new TestMessage();
        msg.setContent("after shutdown");
        store2.publish(PubSubType.CONNECT, msg);

        // Should not receive after shutdown
        assertFalse(latch.await(2, TimeUnit.SECONDS), "Should not receive messages after shutdown");
    }

    @Test
    public void testMultipleSubscriptionsToSameType() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<TestMessage> received1 = new AtomicReference<>();
        AtomicReference<TestMessage> received2 = new AtomicReference<>();

        // Subscribe twice to the same type (this should work - both listeners should fire)
        store1.subscribe(PubSubType.DISPATCH, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                received1.set(msg);
                latch1.countDown();
            }
        }, TestMessage.class);

        store1.subscribe(PubSubType.DISPATCH, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                received2.set(msg);
                latch2.countDown();
            }
        }, TestMessage.class);

        TestMessage msg = new TestMessage();
        msg.setContent("test message");
        store2.publish(PubSubType.DISPATCH, msg);

        assertTrue(latch1.await(5, TimeUnit.SECONDS), "First listener should receive");
        assertTrue(latch2.await(5, TimeUnit.SECONDS), "Second listener should receive");
        
        assertNotNull(received1.get());
        assertNotNull(received2.get());
        assertEquals("test message", received1.get().getContent());
        assertEquals("test message", received2.get().getContent());
    }

    @Test
    public void testWorkerThreadCreation() throws InterruptedException {
        // This test verifies that the worker thread is created on first subscription
        CountDownLatch latch = new CountDownLatch(1);
        
        // Before subscription, no worker should be running
        // After subscription, worker should be created
        store1.subscribe(PubSubType.CONNECT, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                latch.countDown();
            }
        }, TestMessage.class);

        TestMessage msg = new TestMessage();
        msg.setContent("test");
        store2.publish(PubSubType.CONNECT, msg);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker should process message");
    }

    @Test
    public void testListenerExceptionDoesNotStopProcessing() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        // First listener throws exception
        store1.subscribe(PubSubType.DISPATCH, msg -> {
            latch1.countDown();
            throw new RuntimeException("Simulated listener error");
        }, TestMessage.class);

        // Second listener should still work
        store1.subscribe(PubSubType.CONNECT, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                latch2.countDown();
            }
        }, TestMessage.class);

        TestMessage msg1 = new TestMessage();
        msg1.setContent("error trigger");
        store2.publish(PubSubType.DISPATCH, msg1);

        TestMessage msg2 = new TestMessage();
        msg2.setContent("normal message");
        store2.publish(PubSubType.CONNECT, msg2);

        // Both should complete despite the exception in first listener
        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
    }
}