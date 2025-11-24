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

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;

import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.store.pubsub.PubSubStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Test class for RedisStreamsStoreFactory using testcontainers.
 * Tests the Redis Streams-based implementation of StoreFactory.
 */
public class RedisStreamsStoreFactoryTest extends StoreFactoryTest {

    private static GenericContainer<?> container;
    private RedissonClient redissonClient;
    private AutoCloseable closeableMocks;

    @Override
    protected StoreFactory createStoreFactory() throws Exception {
        container = new CustomizedRedisContainer().withReuse(true);
        container.start();
        
        CustomizedRedisContainer customizedRedisContainer = (CustomizedRedisContainer) container;
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + customizedRedisContainer.getHost() + ":" + customizedRedisContainer.getRedisPort());
        
        redissonClient = Redisson.create(config);
        return new RedisStreamsStoreFactory(redissonClient);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (closeableMocks != null) {
            closeableMocks.close();
        }
        if (storeFactory != null) {
            storeFactory.shutdown();
        }
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @AfterAll
    public static void afterAll() throws Exception {
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    @Test
    public void testRedisStreamsSpecificFeatures() {
        // Test that the factory creates RedissonStore (same as Redisson implementation)
        UUID sessionId = UUID.randomUUID();
        Store store = storeFactory.createStore(sessionId);
        
        assertNotNull(store, "Store should not be null");
        assertTrue(store instanceof RedissonStore, "Store should be RedissonStore");
        
        // Test that the store works with Redisson
        store.set("redisStreamsKey", "redisStreamsValue");
        assertEquals("redisStreamsValue", store.get("redisStreamsKey"));
    }

    @Test
    public void testRedisStreamsPubSubStore() {
        PubSubStore pubSubStore = storeFactory.pubSubStore();
        
        assertNotNull(pubSubStore, "PubSubStore should not be null");
        assertTrue(pubSubStore instanceof RedisStreamsPubSubStore, "PubSubStore should be RedisStreamsPubSubStore");
    }

    @Test
    public void testRedisStreamsMapCreation() {
        String mapName = "testRedisStreamsMap";
        Map<String, Object> map = storeFactory.createMap(mapName);
        
        assertNotNull(map, "Map should not be null");
        
        // Test that the map works
        map.put("testKey", "testValue");
        assertEquals("testValue", map.get("testKey"));
        
        // Test with different data types
        map.put("intKey", 42);
        assertEquals(42, map.get("intKey"));
        
        map.put("longKey", 123L);
        assertEquals(123L, map.get("longKey"));
    }

    @Test
    public void testOnDisconnect() {
        closeableMocks = MockitoAnnotations.openMocks(this);
        
        UUID sessionId = UUID.randomUUID();
        Store store = storeFactory.createStore(sessionId);
        
        // Add some data to the store
        store.set("key1", "value1");
        store.set("key2", "value2");
        store.set("key3", 123);
        
        // Verify data exists
        assertTrue(store.has("key1"));
        assertEquals("value1", store.get("key1"));
        assertTrue(store.has("key2"));
        assertEquals("value2", store.get("key2"));
        assertTrue(store.has("key3"));
        assertEquals(Integer.valueOf(123), store.get("key3"));
        
        // Create a mock ClientHead
        ClientHead clientHead = Mockito.mock(ClientHead.class);
        when(clientHead.getSessionId()).thenReturn(sessionId);
        when(clientHead.getStore()).thenReturn(store);
        
        // Call onDisconnect
        storeFactory.onDisconnect(clientHead);
        
        // Verify the Redisson map is deleted
        RMap<String, Object> map = redissonClient.getMap(sessionId.toString());
        assertTrue(map.isEmpty() || map.size() == 0, "Map should be empty after delete");
    }

    @Test
    public void testMultipleStoresIndependence() {
        // Create multiple stores
        UUID sessionId1 = UUID.randomUUID();
        UUID sessionId2 = UUID.randomUUID();
        
        Store store1 = storeFactory.createStore(sessionId1);
        Store store2 = storeFactory.createStore(sessionId2);
        
        // Set different values in each store
        store1.set("sharedKey", "value1");
        store2.set("sharedKey", "value2");
        
        // Verify independence
        assertEquals("value1", store1.get("sharedKey"));
        assertEquals("value2", store2.get("sharedKey"));
        
        // Clean up
        store1.destroy();
        store2.destroy();
    }

    @Test
    public void testStoreFactoryToString() {
        String result = storeFactory.toString();
        assertNotNull(result, "toString should not return null");
        assertTrue(result.contains("RedisStreamsStoreFactory"), "toString should contain class name");
        assertTrue(result.contains("distributed"), "toString should mention distributed nature");
    }
}