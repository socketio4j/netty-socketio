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
package com.socketio4j.socketio.store.redis_stream;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;

import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.redis_pubsub.RedissonStore;



public class RedisStreamsStoreFactory extends BaseStoreFactory {

    private final RedissonClient redissonClient;
    private final EventStore eventStore;

    public RedisStreamsStoreFactory(RedissonClient redissonClient) {
        Objects.requireNonNull(redissonClient, "redisson client can not be null");
        this.redissonClient = redissonClient;

        this.eventStore = new RedisStreamsStore("socketio4j", getNodeId(), redissonClient, 3, StreamMessageId.NEWEST, Duration.ofSeconds(1), 100, EventStoreMode.SINGLE_CHANNEL
        );
    }
    public RedisStreamsStoreFactory(RedissonClient redissonClient, EventStoreMode eventStoreMode) {
        Objects.requireNonNull(redissonClient, "redisson client can not be null");
        this.redissonClient = redissonClient;

        this.eventStore = new RedisStreamsStore("socketio4j", getNodeId(), redissonClient, 3, StreamMessageId.NEWEST, Duration.ofSeconds(1), 100,eventStoreMode
        );
    }

    public RedisStreamsStoreFactory(RedissonClient redissonClient, RedisStreamsStore eventStore) {
        Objects.requireNonNull(redissonClient, "redisson client can not be null");
        Objects.requireNonNull(eventStore, "SingleChannelRedisStreamsStore can not be null");
        this.redissonClient = redissonClient;
        this.eventStore = eventStore;
    }

    public RedisStreamsStoreFactory() {
        this.redissonClient = Redisson.create();
        this.eventStore = new RedisStreamsStore("socketio4j", getNodeId(), redissonClient, 3, StreamMessageId.NEWEST, Duration.ofSeconds(1), 100, EventStoreMode.SINGLE_CHANNEL
        );
    }


    @Override
    public Store createStore(UUID sessionId) {
        return new RedissonStore(sessionId, redissonClient);
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
        return redissonClient.getMap(name);
    }
}