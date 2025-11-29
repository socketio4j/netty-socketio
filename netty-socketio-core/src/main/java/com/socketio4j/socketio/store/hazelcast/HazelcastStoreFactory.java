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
import java.util.UUID;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;

/**
 * WARN: It's necessary to add netty-socketio.jar in hazelcast server classpath.
 *
 */
public class HazelcastStoreFactory extends BaseStoreFactory {

    private final HazelcastInstance hazelcastClient;
    private final HazelcastInstance hazelcastPub;
    private final HazelcastInstance hazelcastSub;

    private final EventStore eventStore;

    public HazelcastStoreFactory() {
        this(HazelcastClient.newHazelcastClient());
    }

    public HazelcastStoreFactory(HazelcastInstance instance) {
        this.hazelcastClient = instance;
        this.hazelcastPub = instance;
        this.hazelcastSub = instance;

        this.eventStore = new HazelcastEventStore(hazelcastPub, hazelcastSub, getNodeId());
    }

    public HazelcastStoreFactory(HazelcastInstance hazelcastClient, HazelcastInstance hazelcastPub, HazelcastInstance hazelcastSub) {
        this.hazelcastClient = hazelcastClient;
        this.hazelcastPub = hazelcastPub;
        this.hazelcastSub = hazelcastSub;

        this.eventStore = new HazelcastEventStore(hazelcastPub, hazelcastSub, getNodeId());
    }
    public HazelcastStoreFactory(HazelcastInstance hazelcastClient, HazelcastInstance hazelcastPub, HazelcastInstance hazelcastSub, HazelcastEventStore pubSubStore) {
        this.hazelcastClient = hazelcastClient;
        this.hazelcastPub = hazelcastPub;
        this.hazelcastSub = hazelcastSub;

        this.eventStore = pubSubStore;
    }

    @Override
    public Store createStore(UUID sessionId) {
        return new HazelcastStore(sessionId, hazelcastClient);
    }

    @Override
    public void shutdown() {
        hazelcastClient.shutdown();
        hazelcastPub.shutdown();
        hazelcastSub.shutdown();
    }

    @Override
    public EventStore pubSubStore() {
        return eventStore;
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return hazelcastClient.getMap(name);
    }

}
