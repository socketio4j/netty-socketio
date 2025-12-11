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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;

public class MemoryStoreFactory extends BaseStoreFactory {

    private final MemoryEventStore pubSubMemoryStore = new MemoryEventStore();

    @Override
    public Store createStore(UUID sessionId) {
        return new MemoryStore();
    }

    @Override
    public EventStore eventStore() {
        return pubSubMemoryStore;
    }

    @Override
    public void shutdown() {
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " (local session store only)";
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return new ConcurrentHashMap<>();
    }

}
