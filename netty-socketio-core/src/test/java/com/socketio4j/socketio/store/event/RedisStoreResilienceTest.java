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

import com.socketio4j.socketio.store.redis_pubsub.RedisStore;
import com.socketio4j.socketio.store.redis_reliable.RedisPubSubReliableEventStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}
