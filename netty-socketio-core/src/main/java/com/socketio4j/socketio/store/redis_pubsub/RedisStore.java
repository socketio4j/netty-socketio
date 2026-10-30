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
package com.socketio4j.socketio.store.redis_pubsub;

import java.time.Duration;
import java.util.UUID;

import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.store.Store;

public class RedisStore implements Store {

    public static final Duration DEFAULT_TTL = Duration.ofDays(1);

    private final RMap<String, Object> map;
    private final Duration ttl;

    public RedisStore(UUID sessionId, RedissonClient redisson) {
        this(sessionId, redisson, null, DEFAULT_TTL);
    }

    public RedisStore(UUID sessionId, RedissonClient redisson, Duration ttl) {
        this(sessionId, redisson, null, ttl);
    }

    public RedisStore(UUID sessionId, RedissonClient redisson, String keyPrefix, Duration ttl) {
        String key;
        if (keyPrefix != null && !keyPrefix.isEmpty()) {
            key = keyPrefix + sessionId.toString();
        } else {
            key = sessionId.toString();
        }
        this.map = redisson.getMap(key);
        this.ttl = ttl;
        refreshTtl();
    }

    private void refreshTtl() {
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            map.expire(ttl);
        }
    }

    @Override
    public void set(String key, Object value) {
        map.put(key, value);
        refreshTtl();
    }

    @Override
    public <T> T get(String key) {
        return (T) map.get(key);
    }

    @Override
    public boolean has(String key) {
        return map.containsKey(key);
    }

    @Override
    public void del(String key) {
        map.remove(key);
        refreshTtl();
    }

    @Override
    public void destroy() {
        map.delete();
    }

    public Duration getTtl() {
        return ttl;
    }

}
