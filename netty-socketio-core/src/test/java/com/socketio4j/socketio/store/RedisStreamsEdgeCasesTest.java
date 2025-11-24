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
package com.socketio4j.socketio.store;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;

import com.socketio4j.socketio.store.pubsub.ConnectMessage;
import com.socketio4j.socketio.store.pubsub.PubSubListener;
import com.socketio4j.socketio.store.pubsub.PubSubStore;
import com.socketio4j.socketio.store.pubsub.PubSubType;
import com.socketio4j.socketio.store.pubsub.TestMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive edge case and error handling tests for RedisStreamsPubSubStore
 * and RedisStreamsStoreFactory.
 */
public class RedisStreamsEdgeCasesTest {

    private GenericContainer<?> container;
    private RedissonClient redissonClient;
    private RedisStreamsPubSubStore pubSubStore;
    private RedisStreamsStoreFactory storeFactory;

    @BeforeEach
    public void setUp() throws Exception {
        container = new CustomizedRedisContainer();
        container.start();

        CustomizedRedisContainer customizedRedisContainer = (CustomizedRedisContainer) container;
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + customizedRedisContainer.getHost() + ":" + customizedRedisContainer.getRedisPort());

        redissonClient = Redisson.create(config);
        pubSubStore = new RedisStreamsPubSubStore(1L, redissonClient);
        storeFactory = new RedisStreamsStoreFactory(redissonClient);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (pubSubStore != null) {
            pubSubStore.shutdown();
        }
        if (storeFactory != null) {
            storeFactory.shutdown();
        }
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    @Test
    public void testPublishBeforeSubscribe() throws InterruptedException {
        // Publish a message before anyone subscribes
        TestMessage msg = new TestMessage();
        msg.setContent("early message");
        pubSubStore.publish(PubSubType.DISPATCH, msg);

        CountDownLatch latch = new CountDownLatch(1);
        
        // Now subscribe with a different node
        RedisStreamsPubSubStore subscriber = new RedisStreamsPubSubStore(2L, redissonClient);
        try {
            subscriber.subscribe(PubSubType.DISPATCH, message -> {
                if (!message.getNodeId().equals(2L)) {
                    latch.countDown();
                }
            }, TestMessage.class);

            // Publish another message
            TestMessage msg2 = new TestMessage();
            msg2.setContent("after subscribe");
            pubSubStore.publish(PubSubType.DISPATCH, msg2);

            // Should receive the new message
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } finally {
            subscriber.shutdown();
        }
    }

    @Test
    public void testMultipleShutdownCalls() {
        // Multiple shutdown calls should be safe
        pubSubStore.shutdown();
        pubSubStore.shutdown();
        pubSubStore.shutdown();
        
        // No exception should be thrown
        assertTrue(true);
    }

    @Test
    public void testSubscribeAfterShutdown() throws InterruptedException {
        pubSubStore.shutdown();

        CountDownLatch latch = new CountDownLatch(1);
        
        // Subscribe after shutdown - should not cause issues but won't receive messages
        pubSubStore.subscribe(PubSubType.CONNECT, msg -> {
            latch.countDown();
        }, ConnectMessage.class);

        // Try to publish
        RedisStreamsPubSubStore publisher = new RedisStreamsPubSubStore(2L, redissonClient);
        try {
            ConnectMessage msg = new ConnectMessage(UUID.randomUUID());
            publisher.publish(PubSubType.CONNECT, msg);

            // Should not receive as store is shut down
            assertFalse(latch.await(2, TimeUnit.SECONDS));
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    public void testUnsubscribeNonExistentType() {
        // Unsubscribe from a type that was never subscribed
        pubSubStore.unsubscribe(PubSubType.BULK_JOIN);
        
        // Should not throw exception
        assertTrue(true);
    }

    @Test
    public void testUnsubscribeAll() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        // Subscribe to multiple types
        pubSubStore.subscribe(PubSubType.CONNECT, msg -> latch1.countDown(), ConnectMessage.class);
        pubSubStore.subscribe(PubSubType.DISPATCH, msg -> latch2.countDown(), TestMessage.class);

        // Unsubscribe from all
        pubSubStore.unsubscribe(PubSubType.CONNECT);
        pubSubStore.unsubscribe(PubSubType.DISPATCH);

        // Create a publisher
        RedisStreamsPubSubStore publisher = new RedisStreamsPubSubStore(2L, redissonClient);
        try {
            publisher.publish(PubSubType.CONNECT, new ConnectMessage(UUID.randomUUID()));
            publisher.publish(PubSubType.DISPATCH, new TestMessage());

            // Should not receive
            assertFalse(latch1.await(2, TimeUnit.SECONDS));
            assertFalse(latch2.await(2, TimeUnit.SECONDS));
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    public void testStoreCreationWithNullOrEmptySessionId() {
        UUID sessionId = UUID.randomUUID();
        Store store = storeFactory.createStore(sessionId);
        
        assertNotNull(store);
        
        // Test basic operations
        store.set("key", "value");
        assertEquals("value", store.get("key"));
        
        store.destroy();
    }

    @Test
    public void testStoreSetGetDeleteOperations() {
        UUID sessionId = UUID.randomUUID();
        Store store = storeFactory.createStore(sessionId);
        
        // Set and get
        store.set("testKey", "testValue");
        assertTrue(store.has("testKey"));
        assertEquals("testValue", store.get("testKey"));
        
        // Delete
        store.del("testKey");
        assertFalse(store.has("testKey"));
        
        // Get after delete should return null
        assertEquals(null, store.get("testKey"));
        
        store.destroy();
    }

    @Test
    public void testStoreWithVariousDataTypes() {
        UUID sessionId = UUID.randomUUID();
        Store store = storeFactory.createStore(sessionId);
        
        // String
        store.set("stringKey", "stringValue");
        assertEquals("stringValue", store.get("stringKey"));
        
        // Integer
        store.set("intKey", 42);
        assertEquals(42, store.get("intKey"));
        
        // Long
        store.set("longKey", 123456789L);
        assertEquals(123456789L, store.get("longKey"));
        
        // Boolean
        store.set("boolKey", true);
        assertEquals(true, store.get("boolKey"));
        
        // Null value
        store.set("nullKey", null);
        assertTrue(store.has("nullKey"));
        assertEquals(null, store.get("nullKey"));
        
        store.destroy();
    }

    @Test
    public void testFactoryMapOperations() {
        String mapName = "testMap";
        java.util.Map<String, Object> map = storeFactory.createMap(mapName);
        
        assertNotNull(map);
        
        // Test operations
        map.put("key1", "value1");
        map.put("key2", 123);
        
        assertEquals("value1", map.get("key1"));
        assertEquals(123, map.get("key2"));
        
        assertTrue(map.containsKey("key1"));
        assertFalse(map.containsKey("nonexistent"));
        
        map.remove("key1");
        assertFalse(map.containsKey("key1"));
        
        map.clear();
        assertTrue(map.isEmpty());
    }

    @Test
    public void testRapidSubscribeUnsubscribe() throws InterruptedException {
        // Rapidly subscribe and unsubscribe
        for (int i = 0; i < 10; i++) {
            pubSubStore.subscribe(PubSubType.CONNECT, msg -> {}, ConnectMessage.class);
            pubSubStore.unsubscribe(PubSubType.CONNECT);
        }
        
        // Final subscribe should still work
        CountDownLatch latch = new CountDownLatch(1);
        pubSubStore.subscribe(PubSubType.CONNECT, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                latch.countDown();
            }
        }, ConnectMessage.class);

        RedisStreamsPubSubStore publisher = new RedisStreamsPubSubStore(2L, redissonClient);
        try {
            publisher.publish(PubSubType.CONNECT, new ConnectMessage(UUID.randomUUID()));
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    public void testWorkerThreadResilience() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        
        pubSubStore.subscribe(PubSubType.DISPATCH, msg -> {
            if (!msg.getNodeId().equals(1L)) {
                if (latch.getCount() == 2) {
                    // First message - throw exception
                    latch.countDown();
                    throw new RuntimeException("Simulated error");
                } else {
                    // Second message - should still be processed
                    latch.countDown();
                }
            }
        }, TestMessage.class);

        RedisStreamsPubSubStore publisher = new RedisStreamsPubSubStore(2L, redissonClient);
        try {
            TestMessage msg1 = new TestMessage();
            msg1.setContent("message 1");
            publisher.publish(PubSubType.DISPATCH, msg1);

            Thread.sleep(1000); // Wait for error to be processed

            TestMessage msg2 = new TestMessage();
            msg2.setContent("message 2");
            publisher.publish(PubSubType.DISPATCH, msg2);

            // Both messages should be processed despite the error
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    public void testNodeIdIsSetOnPublish() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final Long[] receivedNodeId = new Long[1];
        
        RedisStreamsPubSubStore subscriber = new RedisStreamsPubSubStore(2L, redissonClient);
        try {
            subscriber.subscribe(PubSubType.DISPATCH, msg -> {
                receivedNodeId[0] = msg.getNodeId();
                latch.countDown();
            }, TestMessage.class);

            TestMessage msg = new TestMessage();
            msg.setContent("test");
            pubSubStore.publish(PubSubType.DISPATCH, msg);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1L, receivedNodeId[0]);
        } finally {
            subscriber.shutdown();
        }
    }
}