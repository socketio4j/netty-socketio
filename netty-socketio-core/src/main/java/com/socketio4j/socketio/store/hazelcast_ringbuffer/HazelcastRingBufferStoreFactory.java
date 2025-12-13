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
package com.socketio4j.socketio.store.hazelcast_ringbuffer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.hazelcast.HazelcastStore;


/**
 * WARN: It's necessary to add netty-socketio.jar in hazelcast server classpath.
 *
 */
public class HazelcastRingBufferStoreFactory extends BaseStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(HazelcastRingBufferStoreFactory.class);

    private final HazelcastInstance hazelcastClient;
    private final EventStore eventStore;

    /**
     * API 4.y.z
     * @param hazelcastClient
     * @param eventStore
     */
    public HazelcastRingBufferStoreFactory(HazelcastInstance hazelcastClient, HazelcastRingBufferEventStore eventStore) {

        Objects.requireNonNull(hazelcastClient, "hazelcastClient cannot be null");
        Objects.requireNonNull(eventStore, "eventStore cannot be null");

        this.hazelcastClient = hazelcastClient;
        this.eventStore = eventStore;
    }

    @Override
    public Store createStore(UUID sessionId) {
        return new HazelcastStore(sessionId, hazelcastClient);
    }

    @Override
    public void shutdown() {
        eventStore.shutdown();
    }


    @Override
    public EventStore eventStore() {
        return eventStore;
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return hazelcastClient.getMap(name);
    }

}
