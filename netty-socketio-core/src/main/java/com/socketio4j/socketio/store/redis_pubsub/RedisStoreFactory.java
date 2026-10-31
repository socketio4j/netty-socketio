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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.metrics.SocketIOMetrics;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;

/**
 * {@code RedisStoreFactory} provides session-scoped storage backed by Redis,
 * using Redisson as the storage driver. Each connected session receives its own
 * {@link RedisStore}, allowing per-session key/value data to be shared across
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
 * When instantiated using {@link #RedisStoreFactory(RedissonClient)}, this factory
 * configures a {@link RedisPubSubEventStore} in
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
 * RedisStoreFactory factory = new RedisStoreFactory(redis, es);
 * }</pre>
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *     <li>{@link #createStore(UUID)} returns a new distributed {@link RedisStore}</li>
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

public class RedisStoreFactory extends BaseStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(RedisStoreFactory.class);

    private final RedissonClient redisClient;
    private final EventStore eventStore;
    private volatile String keyPrefix;
    private volatile Duration sessionTtl;

    /**
     * Creates a {@code RedisStoreFactory} using the provided Redis client,
     * user-supplied {@link EventStore}, optional key prefix, and optional session TTL.
     *
     * @param redisClient non-null Redis client
     * @param eventStore  non-null event store implementation
     * @param keyPrefix   optional key prefix for session maps, or null for default
     * @param sessionTtl  optional session TTL, or null to preserve default no-expiry behavior
     */
    public RedisStoreFactory(@NotNull RedissonClient redisClient,
                             @NotNull EventStore eventStore,
                             @Nullable String keyPrefix,
                             @Nullable Duration sessionTtl) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient cannot be null");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore cannot be null");
        this.keyPrefix = keyPrefix;
        this.sessionTtl = sessionTtl;
    }

    /**
     * Creates a {@code RedisStoreFactory} using the provided Redis client,
     * user-supplied {@link EventStore}, and optional session TTL.
     *
     * @param redisClient non-null Redis client
     * @param eventStore  non-null event store implementation
     * @param sessionTtl  optional session TTL, or null to preserve default no-expiry behavior
     */
    public RedisStoreFactory(@NotNull RedissonClient redisClient,
                             @NotNull EventStore eventStore,
                             @Nullable Duration sessionTtl) {
        this(redisClient, eventStore, null, sessionTtl);
    }

    /**
     * Creates a {@code RedisStoreFactory} using the provided Redis client and
     * user-supplied {@link EventStore}. Preserves default no-expiry behavior for session data.
     *
     * @apiNote Added in API version {@code 4.0.0}
     * 
     * @param redisClient non-null Redis client
     * @param eventStore  non-null event store implementation
     */
    public RedisStoreFactory(@NotNull RedissonClient redisClient,
                             @NotNull EventStore eventStore) {
        this(redisClient, eventStore, null, null);
    }

    /**
     * Creates a {@code RedisStoreFactory} using default Redis-backed event distribution,
     * metrics tracking, and optional session TTL.
     *
     * @param redisClient non-null Redis client
     * @param metrics     metrics instance, or null for no-op metrics
     * @param sessionTtl  optional session TTL, or null to preserve default no-expiry behavior
     */
    public RedisStoreFactory(@NotNull RedissonClient redisClient,
                             @Nullable SocketIOMetrics metrics,
                             @Nullable Duration sessionTtl) {
        this(redisClient,
             new RedisPubSubEventStore(redisClient, redisClient, EventStoreMode.MULTI_CHANNEL, null, metrics),
             sessionTtl);
    }

    /**
     * Creates a {@code RedisStoreFactory} using default Redis-backed event distribution
     * with metrics tracking and default no-expiry behavior for session data.
     *
     * @param redisClient non-null Redis client
     * @param metrics     metrics instance, or null for no-op metrics
     */
    public RedisStoreFactory(@NotNull RedissonClient redisClient,
                             @Nullable SocketIOMetrics metrics) {
        this(redisClient, metrics, (Duration) null);
    }

    /**
     * Creates a {@code RedisStoreFactory} using default Redis-backed event distribution
     * and optional session TTL.
     *
     * @param redisClient non-null Redis client
     * @param sessionTtl  optional session TTL, or null to preserve default no-expiry behavior
     */
    public RedisStoreFactory(@NotNull RedissonClient redisClient,
                             @Nullable Duration sessionTtl) {
        this(redisClient, (SocketIOMetrics) null, sessionTtl);
    }

    /**
     * Creates a {@code RedisStoreFactory} using default Redis-backed event distribution
     * and optional key prefix and session TTL.
     *
     * @param redisClient non-null Redis client
     * @param keyPrefix   optional key prefix for session maps, or null for default
     * @param sessionTtl  optional session TTL, or null to preserve default no-expiry behavior
     */
    public RedisStoreFactory(@NotNull RedissonClient redisClient,
                             @Nullable String keyPrefix,
                             @Nullable Duration sessionTtl) {
        this(redisClient,
             new RedisPubSubEventStore(redisClient, redisClient, EventStoreMode.MULTI_CHANNEL, null, null),
             keyPrefix,
             sessionTtl);
    }

    /**
     * Creates a {@code RedisStoreFactory} using default Redis-backed event distribution
     * and default no-expiry behavior for sessions.
     *
     * @apiNote Added in API version {@code 4.0.0}
     * 
     * @param redisClient non-null Redis client
     */
    public RedisStoreFactory(@NotNull RedissonClient redisClient) {
        this(redisClient, (Duration) null);
    }

    @Nullable
    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(@Nullable Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    @Nullable
    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(@Nullable String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Store createStore(UUID sessionId) {
        return createStore(sessionId, sessionTtl);
    }

    /**
     * Creates a {@link RedisStore} for the given session with a custom TTL.
     *
     * @param sessionId non-null session identifier
     * @param ttl       custom session TTL, or null for no expiry
     * @return a new distributed RedisStore
     */
    public Store createStore(UUID sessionId, @Nullable Duration ttl) {
        return new RedisStore(sessionId, redisClient, keyPrefix, ttl);
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
