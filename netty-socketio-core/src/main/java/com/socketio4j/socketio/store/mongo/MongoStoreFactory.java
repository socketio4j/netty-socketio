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
package com.socketio4j.socketio.store.mongo;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.reactivestreams.client.MongoClient;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.memory.MemoryStore;

/**
 * A {@code StoreFactory} implementation that provides session-scoped storage
 * and MongoDB Change Streams based event distribution.
 * <p>
 * Session data is stored in memory via {@link MemoryStore} for low-latency,
 * non-blocking access, while event propagation across cluster nodes is handled
 * asynchronously by {@link MongoEventStore} using MongoDB Change Streams.
 */
public class MongoStoreFactory extends BaseStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(MongoStoreFactory.class);

    private final EventStore eventStore;

    /**
     * Creates a {@code MongoStoreFactory} using default {@link MongoEventStore}
     * with {@link EventStoreMode#MULTI_CHANNEL} mode.
     *
     * @param mongoClient  shared MongoDB client (must connect to a replica set)
     * @param databaseName database to use for event collections
     */
    public MongoStoreFactory(@NotNull MongoClient mongoClient, @NotNull String databaseName) {
        this(mongoClient, databaseName, EventStoreMode.MULTI_CHANNEL);
    }

    /**
     * Creates a {@code MongoStoreFactory} using default {@link MongoEventStore}
     * with the specified {@link EventStoreMode}.
     *
     * @param mongoClient    shared MongoDB client (must connect to a replica set)
     * @param databaseName   database to use for event collections
     * @param eventStoreMode SINGLE_CHANNEL or MULTI_CHANNEL mode
     */
    public MongoStoreFactory(@NotNull MongoClient mongoClient,
                             @NotNull String databaseName,
                             @Nullable EventStoreMode eventStoreMode) {
        this(createDefaultEventStore(mongoClient, databaseName, eventStoreMode));
    }

    private static EventStore createDefaultEventStore(MongoClient mongoClient,
                                                      String databaseName,
                                                      EventStoreMode mode) {
        EventStoreMode targetMode = EventStoreMode.MULTI_CHANNEL;
        if (mode != null) {
            targetMode = mode;
        }
        return new MongoEventStore.Builder(mongoClient, databaseName)
                .eventStoreMode(targetMode)
                .build();
    }

    /**
     * Creates a {@code MongoStoreFactory} using the provided {@link EventStore}.
     *
     * @param eventStore non-null event store implementation
     */
    public MongoStoreFactory(@NotNull EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore cannot be null");
    }

    @Override
    public Store createStore(UUID sessionId) {
        return new MemoryStore();
    }

    @Override
    public EventStore eventStore() {
        return eventStore;
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return new ConcurrentHashMap<>();
    }

    @Override
    public void shutdown() {
        try {
            eventStore.shutdown();
        } catch (Exception e) {
            log.error("Failed to shut down Mongo event store", e);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " (memory session store, MongoDB change streams publish/subscribe)";
    }
}
