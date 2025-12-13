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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.PublishConfig;
import com.socketio4j.socketio.store.redis_pubsub.RedissonStore;


public class RedissonStreamStoreFactory extends BaseStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(RedissonStreamStoreFactory.class);

    private final RedissonClient redisClient;
    private final RedissonClient redisPub;
    private final RedissonClient redisSub;

    private final EventStore eventStore;

    public RedissonStreamStoreFactory() {
        this(Redisson.create(), EventStoreMode.MULTI_CHANNEL);
    }

    public RedissonStreamStoreFactory(RedissonClient redisson, EventStoreMode eventStoreMode) {

        Objects.requireNonNull(redisson, "redisson cannot be null");

        this.redisClient = redisson;
        this.redisPub = redisson;
        this.redisSub = redisson;

        this.eventStore = new RedissonStreamEventStore.Builder(redisSub, eventStoreMode).trimEvery(Duration.ofSeconds(30)).build();
    }

    public RedissonStreamStoreFactory(RedissonClient redisson, RedissonStreamEventStore eventStore) {

        Objects.requireNonNull(redisson, "redisson cannot be null");
        Objects.requireNonNull(eventStore, "eventStore cannot be null");

        this.redisClient = redisson;
        this.redisPub = redisson;
        this.redisSub = redisson;
        this.eventStore = eventStore;
    }

    public RedissonStreamStoreFactory(Redisson redisClient, Redisson redisPub, Redisson redisSub, PublishConfig publishConfig, EventStoreMode eventStoreMode) {

        Objects.requireNonNull(redisClient, "redisClient cannot be null");
        Objects.requireNonNull(redisPub, "redisPub cannot be null");
        Objects.requireNonNull(redisSub, "redisSub cannot be null");

        this.redisClient = redisClient;
        this.redisPub = redisPub;
        this.redisSub = redisSub;

        this.eventStore = new RedissonStreamEventStore(redisClient, getNodeId(), eventStoreMode, null, null, Duration.ofSeconds(30));
    }

    public RedissonStreamStoreFactory(Redisson redisClient, Redisson redisPub, Redisson redisSub, RedissonStreamEventStore eventStore) {

        Objects.requireNonNull(redisClient, "redisClient cannot be null");
        Objects.requireNonNull(redisPub, "redisPub cannot be null");
        Objects.requireNonNull(redisSub, "redisSub cannot be null");
        Objects.requireNonNull(eventStore, "eventStore cannot be null");

        this.redisClient = redisClient;
        this.redisPub = redisPub;
        this.redisSub = redisSub;

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
