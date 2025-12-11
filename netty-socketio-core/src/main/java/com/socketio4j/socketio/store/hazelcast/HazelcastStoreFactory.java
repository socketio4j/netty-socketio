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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.PublishConfig;


/**
 * WARN: It's necessary to add netty-socketio.jar in hazelcast server classpath.
 *
 */
public class HazelcastStoreFactory extends BaseStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(HazelcastStoreFactory.class);

    private final HazelcastInstance hazelcastClient;
    private final HazelcastInstance hazelcastPub;
    private final HazelcastInstance hazelcastSub;

    private final EventStore eventStore;

    public HazelcastStoreFactory() {
        this(HazelcastClient.newHazelcastClient(), PublishConfig.allUnreliable(), EventStoreMode.MULTI_CHANNEL);
    }

    public HazelcastStoreFactory(HazelcastInstance instance, PublishConfig publishConfig, EventStoreMode eventStoreMode) {

        Objects.requireNonNull(instance, "instance cannot be null");

        this.hazelcastClient = instance;
        this.hazelcastPub = instance;
        this.hazelcastSub = instance;
        this.eventStore = new HazelcastEventStore(hazelcastPub, hazelcastSub, getNodeId(), publishConfig, eventStoreMode);
    }

    public HazelcastStoreFactory(HazelcastInstance hazelcastClient, HazelcastInstance hazelcastPub, HazelcastInstance hazelcastSub, PublishConfig publishConfig, EventStoreMode eventStoreMode) {

        Objects.requireNonNull(hazelcastClient, "hazelcastClient cannot be null");
        Objects.requireNonNull(hazelcastPub, "hazelcastPub cannot be null");
        Objects.requireNonNull(hazelcastSub, "hazelcastSub cannot be null");

        this.hazelcastClient = hazelcastClient;
        this.hazelcastPub = hazelcastPub;
        this.hazelcastSub = hazelcastSub;
        this.eventStore = new HazelcastEventStore(hazelcastPub, hazelcastSub, getNodeId(),  publishConfig, eventStoreMode);
    }


    public HazelcastStoreFactory(HazelcastInstance hazelcastClient, HazelcastInstance hazelcastPub, HazelcastInstance hazelcastSub, HazelcastEventStore pubSubStore) {

        Objects.requireNonNull(hazelcastClient, "hazelcastClient cannot be null");
        Objects.requireNonNull(hazelcastPub, "hazelcastPub cannot be null");
        Objects.requireNonNull(hazelcastSub, "hazelcastSub cannot be null");
        Objects.requireNonNull(pubSubStore, "eventStore cannot be null");

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
