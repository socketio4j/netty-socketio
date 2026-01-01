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
import com.socketio4j.socketio.store.event.EventStoreMode;

/**
 * {@code RedissonStoreFactory} provides session-scoped storage backed by Redis,
 * using Redisson as the storage driver. Each connected session receives its own
 * {@link RedissonStore}, allowing per-session key/value data to be shared across
 * multiple nodes when running in a clustered environment.
 * <p>
 * Event propagation is handled by the supplied {@link EventStore}. This design
 * keeps storage and event distribution concerns separate, enabling flexible
 * combinations such as:
 * <ul>
 *     <li>Redis session storage + Kafka event propagation</li>
 *     <li>Redis session storage + Redis Streams event propagation</li>
 *     <li>Redis session storage + Hazelcast RingBuffer event propagation</li>
 *     <li>Redis session storage + in-memory event propagation (local only)</li>
 * </ul>
 * The choice of {@code EventStore} determines whether events are distributed or
 * remain confined to a single JVM. Session data, however, is always backed by Redis
 * and therefore visible across nodes that share the same Redis cluster.
 *
 * <h3>Default Behavior</h3>
 * When instantiated using {@link #RedissonStoreFactory(RedissonClient)}, this factory
 * configures a {@link RedissonEventStore} in
 * {@link com.socketio4j.socketio.store.event.EventStoreMode#MULTI_CHANNEL MULTI_CHANNEL} mode.
 * Under this configuration:
 * <ul>
 *     <li>Session data is persistent as long as Redis is running</li>
 *     <li>Events published on one node propagate to all other subscribed nodes</li>
 *     <li>No event replay or history is provided (Pub/Sub semantics)</li>
 * </ul>
 *
 * <h3>Custom EventStore Usage</h3>
 * Passing a custom {@link EventStore} allows hybrid topologies, such as:
 * <pre>{@code
 * RedissonClient redis = Redisson.create();
 * EventStore es = new KafkaEventStore(...);
 *
 * // Redis-backed session storage, Kafka event distribution
 * RedissonStoreFactory factory = new RedissonStoreFactory(redis, es);
 * }</pre>
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *     <li>{@link #createStore(UUID)} returns a new distributed {@link RedissonStore}</li>
 *     <li>{@link #createMap(String)} returns a named Redis-backed map for shared state</li>
 *     <li>{@link #shutdown()} gracefully shuts down the configured {@link EventStore}</li>
 * </ul>
 * Redis connections must be managed externally and are not closed by this factory.
 *
 * <h3>Thread Safety</h3>
 * This factory performs no concurrent operations itself. Thread-safety guarantees
 * depend on the underlying {@link RedissonClient} and the selected {@link EventStore}.
 *
 * <h3>Suitable For</h3>
 * <ul>
 *     <li>Distributed deployments requiring shared session metadata</li>
 *     <li>Systems where event propagation backend may differ from session storage backend</li>
 *     <li>Hybrid architectures: Redis storage + different event transport</li>
 * </ul>
 *
 * <h3>Not Suitable For</h3>
 * <ul>
 *     <li>Stateless deployments with no need for shared session data</li>
 *     <li>Low-latency clusters where Redis round-trip time is unacceptable</li>
 *     <li>Scenarios requiring event replay or durable streaming without custom EventStore</li>
 * </ul>
 *
 * <h3>Summary</h3>
 * <blockquote>
 *     Session state is stored in Redis for cross-node access. Event distribution
 *     is determined entirely by the chosen {@link EventStore}. This factory allows
 *     storage and event systems to be configured independently.
 * </blockquote>
 */

public class RedissonStoreFactory extends BaseStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(RedissonStoreFactory.class);

    private final RedissonClient redisClient;
    private final EventStore eventStore;

    /**
     * Creates a {@code RedissonStoreFactory} using the provided Redis client and
     * user-supplied {@link EventStore}. Session data is stored via Redis, while the
     * event store determines whether event propagation is local or distributed.
     *
     * @apiNote Added in API version{@code 4.0.0}
     * 
     * @param redisClient non-null Redis client
     * @param eventStore  non-null event store implementation
     */
    public RedissonStoreFactory(@NotNull RedissonClient redisClient,
                                @NotNull EventStore eventStore) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient cannot be null");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore cannot be null");
    }

    /**
     * Creates a {@code RedissonStoreFactory} using default Redis-backed event distribution.
     * Session data is stored in Redis, and events are propagated using {@link RedissonEventStore}
     * in {@link EventStoreMode#MULTI_CHANNEL} mode.
     *
     * @apiNote Added in API version{@code 4.0.0}
     * 
     * @param redisClient non-null Redis client
     */
    public RedissonStoreFactory(@NotNull RedissonClient redisClient) {
        this(redisClient,
             new RedissonEventStore(redisClient, redisClient, EventStoreMode.MULTI_CHANNEL, null));
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
    public <K, V> Map<K, V> createMap(String name) {
        return redisClient.getMap(name);
    }

    @Override
    public void shutdown() {
        try {
            eventStore.shutdown();
        } catch (Exception e) {
            log.error("Failed to shut down event store", e);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " (redis session store)";
    }
}
