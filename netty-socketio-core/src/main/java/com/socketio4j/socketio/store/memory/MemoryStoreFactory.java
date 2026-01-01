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
package com.socketio4j.socketio.store.memory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;

import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;

/**
 * A {@code StoreFactory} implementation that provides per-session in-memory storage.
 * <p>
 * Session data is stored locally in JVM memory via {@link MemoryStore}. Event propagation
 * is determined entirely by the provided {@link EventStore}. This allows combinations like:
 * <ul>
 *     <li>Memory session storage + Kafka event distribution</li>
 *     <li>Memory session storage + Redis Streams event distribution</li>
 *     <li>Memory session storage + in-memory event propagation (local only)</li>
 * </ul>
 * <p>
 * If no {@link EventStore} is supplied, {@link MemoryEventStore} is used by default.
 */
public class MemoryStoreFactory extends BaseStoreFactory {

    private final EventStore eventStore;

    /**
     * Creates a new {@code MemoryStoreFactory} using {@link MemoryEventStore}.
     * Both session data and events remain local to the JVM.
     * @apiNote Added in API version{@code 4.0.0}
     */
    public MemoryStoreFactory() {
        this.eventStore = new MemoryEventStore();
    }

    /**
     * Creates a new {@code MemoryStoreFactory} using the provided {@link EventStore}.
     * Session data remains local, but event propagation depends on the given implementation.
     *
     * @apiNote Added in API version{@code 4.0.0}
     * 
     * @param eventStore non-null event store
     * @throws NullPointerException if {@code eventStore} is {@code null}
     */
    public MemoryStoreFactory(@NotNull EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore can not be null");
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
    public void shutdown() {
        // no-op
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return new ConcurrentHashMap<>();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " (memory session store)";
    }
}
