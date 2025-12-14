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

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;


public class RedissonStoreFactory extends BaseStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(RedissonStoreFactory.class);

    private final RedissonClient redisClient;
    private final EventStore eventStore;

    /**
     * API 4.y.z
     * @param redissonClient
     * @param eventStore
     */
    public RedissonStoreFactory(@NotNull RedissonClient redissonClient,
                                @NotNull EventStore eventStore) {
        Objects.requireNonNull(redissonClient, "redissonClient can not be null");
        Objects.requireNonNull(eventStore, "eventStore can not be null");

        this.redisClient = redissonClient;
        this.eventStore = eventStore;
    }

    @Override
    public Store createStore(UUID sessionId) {
        return new RedissonStore(sessionId, redisClient);
    }

    @Override
    public EventStore eventStore() {
        return eventStore;
    }

    @Override
    public void shutdown() {
        eventStore.shutdown();
    }


    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return redisClient.getMap(name);
    }

}
