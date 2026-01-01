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
package com.socketio4j.socketio.store.hazelcast;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;

/**
 * A {@code StoreFactory} implementation that provides session-scoped storage backed
 * by Hazelcast and allows users to supply an {@link EventStore} of their choice.
 * <p>
 * Session data is stored in Hazelcast via {@link HazelcastStore}, while event
 * propagation is determined entirely by the provided {@link EventStore}. This
 * design allows hybrid configurations such as:
 * <ul>
 *     <li>Hazelcast session storage + Kafka event distribution</li>
 *     <li>Hazelcast session storage + Redis Streams event distribution</li>
 *     <li>Hazelcast session storage + in-memory event propagation (local only)</li>
 * </ul>
 * <p>
 * If no {@link EventStore} is supplied, {@link HazelcastEventStore} is used by default.
 */
public class HazelcastStoreFactory extends BaseStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(HazelcastStoreFactory.class);

    private final HazelcastInstance hazelcastClient;
    private final EventStore eventStore;

    /**
     * Creates a {@code HazelcastStoreFactory} using the provided Hazelcast instance and
     * a caller-supplied {@link EventStore}.
     *
     * @apiNote Added in API version{@code 4.0.0}
     * 
     * @param hazelcastClient non-null Hazelcast instance
     * @param eventStore      non-null event store implementation
     * @throws NullPointerException if either argument is {@code null}
     */
    public HazelcastStoreFactory(@NotNull HazelcastInstance hazelcastClient,
                                 @NotNull EventStore eventStore) {
        this.hazelcastClient = Objects.requireNonNull(hazelcastClient, "hazelcastClient cannot be null");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore cannot be null");
    }

    /**
     * Creates a {@code HazelcastStoreFactory} using default Hazelcast-backed event distribution.
     * <p>
     * Session data remains in Hazelcast, while events are propagated via {@link HazelcastEventStore}
     * in {@link EventStoreMode#MULTI_CHANNEL} mode.
     *
     * @apiNote Added in API version{@code 4.0.0}
     * 
     * @param hazelcastClient non-null Hazelcast instance
     */
    public HazelcastStoreFactory(@NotNull HazelcastInstance hazelcastClient) {
        this(hazelcastClient,
             new HazelcastEventStore(hazelcastClient, hazelcastClient, null, null, ""));
    }

    @Override
    public Store createStore(UUID sessionId) {
        return new HazelcastStore(sessionId, hazelcastClient);
    }

    @Override
    public EventStore eventStore() {
        return eventStore;
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
    public <K, V> Map<K, V> createMap(String name) {
        return hazelcastClient.getMap(name);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " (Hazelcast session store)";
    }
}