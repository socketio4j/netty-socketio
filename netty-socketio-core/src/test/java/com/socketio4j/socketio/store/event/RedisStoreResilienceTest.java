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

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.redis_pubsub.RedisStore;
import com.socketio4j.socketio.store.redis_pubsub.RedisStoreFactory;
import com.socketio4j.socketio.store.redis_reliable.RedisPubSubReliableEventStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Redis Store Resilience Tests")
public class RedisStoreResilienceTest {

    @Test
    @DisplayName("Should accept sub-second trimEvery without period <= 0 exception")
    void shouldAcceptSubSecondTrimEvery() {
        RedissonClient redissonPub = mock(RedissonClient.class);
        RedissonClient redissonSub = mock(RedissonClient.class);

        RedisPubSubReliableEventStore store = new RedisPubSubReliableEventStore.Builder(redissonPub, redissonSub)
                .trimEvery(Duration.ofMillis(500))
                .streamMaxLength(1000)
                .build();

        assertNotNull(store);
        store.shutdown();
    }

    @Test
    @DisplayName("Should apply custom prefix and TTL on RedisStore operations")
    void shouldApplyCustomPrefixAndTtlOnRedisStore() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Object> map = mock(RMap.class);

        UUID sessionId = UUID.randomUUID();
        String prefix = "socketio:custom:";
        Duration ttl = Duration.ofMinutes(15);

        when(redissonClient.<String, Object>getMap(prefix + sessionId)).thenReturn(map);

        RedisStore store = new RedisStore(sessionId, redissonClient, prefix, ttl);

        verify(redissonClient, times(1)).getMap(eq(prefix + sessionId));
        verify(map, times(1)).expire(eq(ttl));

        store.set("testKey", "testVal");
        verify(map, times(1)).put("testKey", "testVal");
        verify(map, times(2)).expire(eq(ttl));

        when(map.get("testKey")).thenReturn("testVal");
        assertEquals("testVal", store.get("testKey"));

        store.del("testKey");
        verify(map, times(1)).remove("testKey");
        verify(map, times(3)).expire(eq(ttl));

        store.destroy();
        verify(map, times(1)).delete();
    }

    @Test
    void disabledTtlClearsExistingExpirationOnWrites() {
        for (Duration ttl : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            RedissonClient client = mock(RedissonClient.class);
            @SuppressWarnings("unchecked")
            RMap<String, Object> map = mock(RMap.class);
            UUID id = UUID.randomUUID();
            when(client.<String, Object>getMap(id.toString())).thenReturn(map);

            RedisStore store = new RedisStore(id, client, ttl);
            store.set("key", "value");
            store.del("key");

            verify(map, times(3)).clearExpire();
            verify(map, never()).expire(any(Duration.class));
        }
    }

    @Test
    @DisplayName("Should preserve no-expiry contract by default for factory-created stores")
    void shouldPreserveNoExpiryByDefaultInRedisStoreFactory() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Object> map = mock(RMap.class);

        UUID sessionId = UUID.randomUUID();
        when(redissonClient.<String, Object>getMap(sessionId.toString())).thenReturn(map);

        RedisStoreFactory factory = new RedisStoreFactory(redissonClient);
        assertNull(factory.getSessionTtl());
        assertNull(factory.getKeyPrefix());

        Store store = factory.createStore(sessionId);
        assertNotNull(store);
        assertTrue(store instanceof RedisStore);
        RedisStore redisStore = (RedisStore) store;
        assertNull(redisStore.getTtl());

        // Verify map.expire is never invoked by default
        verify(map, never()).expire(any(Duration.class));

        redisStore.set("key", "val");
        verify(map, times(1)).put("key", "val");
        verify(map, never()).expire(any(Duration.class));

        redisStore.del("key");
        verify(map, times(1)).remove("key");
        verify(map, never()).expire(any(Duration.class));
    }

    @Test
    @DisplayName("Should apply configured session TTL and prefix in RedisStoreFactory")
    void shouldApplyConfiguredSessionTtlInRedisStoreFactory() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Object> map = mock(RMap.class);

        UUID sessionId = UUID.randomUUID();
        String prefix = "socketio:session:";
        Duration ttl = Duration.ofHours(6);

        when(redissonClient.<String, Object>getMap(prefix + sessionId)).thenReturn(map);

        RedisStoreFactory factory = new RedisStoreFactory(redissonClient, prefix, ttl);
        assertEquals(ttl, factory.getSessionTtl());
        assertEquals(prefix, factory.getKeyPrefix());

        Store store = factory.createStore(sessionId);
        assertNotNull(store);
        assertTrue(store instanceof RedisStore);
        RedisStore redisStore = (RedisStore) store;
        assertEquals(ttl, redisStore.getTtl());

        verify(map, times(1)).expire(eq(ttl));

        redisStore.set("key", "val");
        verify(map, times(2)).expire(eq(ttl));
    }

    @Test
    @DisplayName("Should allow per-call TTL override on factory createStore")
    void shouldAllowPerCallTtlOverrideOnFactoryCreateStore() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Object> map = mock(RMap.class);

        UUID sessionId = UUID.randomUUID();
        Duration customTtl = Duration.ofMinutes(45);

        when(redissonClient.<String, Object>getMap(sessionId.toString())).thenReturn(map);

        RedisStoreFactory factory = new RedisStoreFactory(redissonClient);
        Store store = factory.createStore(sessionId, customTtl);
        assertNotNull(store);
        assertTrue(store instanceof RedisStore);
        RedisStore redisStore = (RedisStore) store;
        assertEquals(customTtl, redisStore.getTtl());

        verify(map, times(1)).expire(eq(customTtl));
    }

    @Test
    @DisplayName("Should preserve standalone RedisStore DEFAULT_TTL for 2-arg constructor")
    void shouldPreserveStandaloneRedisStoreDefaultTtl() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Object> map = mock(RMap.class);

        UUID sessionId = UUID.randomUUID();
        when(redissonClient.<String, Object>getMap(sessionId.toString())).thenReturn(map);

        RedisStore store = new RedisStore(sessionId, redissonClient);
        assertEquals(RedisStore.DEFAULT_TTL, store.getTtl());
        verify(map, times(1)).expire(eq(RedisStore.DEFAULT_TTL));
    }
}
