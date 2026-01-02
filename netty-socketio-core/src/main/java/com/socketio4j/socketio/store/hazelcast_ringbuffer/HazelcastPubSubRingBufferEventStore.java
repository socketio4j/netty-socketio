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

import java.util.Arrays;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;


public class HazelcastPubSubRingBufferEventStore implements EventStore {

    private final HazelcastInstance hazelcastPub;
    private final HazelcastInstance hazelcastSub;
    private final Long nodeId;
    private final EventStoreMode eventStoreMode;

    private final ConcurrentMap<EventType, Queue<UUID>> listenerMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<EventType, ITopic<EventMessage>> activePubTopics = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ITopic<?>> activeSubTopics = new ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(HazelcastPubSubRingBufferEventStore.class);
    private final String ringBufferNamePrefix;

    private static final String DEFAULT_RING_BUFFER_NAME_PREFIX = "SOCKETIO4J:";

    public HazelcastPubSubRingBufferEventStore(
            @NotNull HazelcastInstance hazelcastPub,
            @NotNull HazelcastInstance hazelcastSub,
            @Nullable Long nodeId,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable String ringBufferNamePrefix
    ) {
        Objects.requireNonNull(hazelcastPub, "hazelcastPub cannot be null");
        Objects.requireNonNull(hazelcastSub, "hazelcastSub cannot be null");

        if (ringBufferNamePrefix == null || ringBufferNamePrefix.isEmpty()) {
            ringBufferNamePrefix = DEFAULT_RING_BUFFER_NAME_PREFIX;
        }
        this.ringBufferNamePrefix = ringBufferNamePrefix;

        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        }
        this.eventStoreMode = eventStoreMode;

        this.hazelcastPub = hazelcastPub;
        this.hazelcastSub = hazelcastSub;
        if (nodeId == null) {
            nodeId = getNodeId();
        }
        this.nodeId = nodeId;

    }


    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);

        ITopic<EventMessage> topic = activePubTopics.computeIfAbsent(type, k -> {
            String topicName = getRingBufferName(k);
            return hazelcastPub.getReliableTopic(topicName);
        });

        topic.publish(msg);
    }
    private String getRingBufferName(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            return ringBufferNamePrefix + EventType.ALL_SINGLE_CHANNEL.name();
        }
        return ringBufferNamePrefix + type.name();
    }

    @Override
    public EventStoreMode getEventStoreMode(){
        return eventStoreMode;
    }

    @Override
    public EventStoreType getEventStoreType() {
        return EventStoreType.STREAM;
    }

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, final EventListener<T> listener, Class<T> clazz) {

        ITopic<T> topic = hazelcastSub.getReliableTopic(getRingBufferName(type));

        UUID regId = topic.addMessageListener(msg -> {
            if (!nodeId.equals(msg.getMessageObject().getNodeId())) {
                listener.onMessage(msg.getMessageObject());
            }
        });

        activeSubTopics.put(regId, topic);

        listenerMap.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>())
                .add(regId);
    }

    @Override
    public void unsubscribe0(EventType type) {

        Queue<UUID> regIds = listenerMap.remove(type);
        if (regIds == null || regIds.isEmpty()) {
            return;
        }
        for (UUID id : regIds) {
            ITopic<?> topic = activeSubTopics.remove(id);
            if (topic == null){
                continue;
            }
            try {
                topic.removeMessageListener(id);
            } catch (Exception ex) {
                log.warn("Failed to remove listener {} from topic {}", id, getRingBufferName(type), ex);
            }
        }
    }

    @Override
    public void shutdown0() {
        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
        listenerMap.clear();
        activeSubTopics.clear();
        activePubTopics.clear();
        //do not shut down client here
    }

    public static final class Builder {

        // required
        private final HazelcastInstance hazelcastPub;
        private final HazelcastInstance hazelcastSub;

        // optional
        private Long nodeId;
        private EventStoreMode eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        private String ringBufferNamePrefix = DEFAULT_RING_BUFFER_NAME_PREFIX;

        // --------------------------------------------------
        // Constructors
        // --------------------------------------------------

        public Builder(@NotNull HazelcastInstance hazelcastClient) {
            this(hazelcastClient, hazelcastClient);
        }

        public Builder(@NotNull HazelcastInstance hazelcastPub,
                       @NotNull HazelcastInstance hazelcastSub) {
            this.hazelcastPub = Objects.requireNonNull(hazelcastPub, "hazelcastPub");
            this.hazelcastSub = Objects.requireNonNull(hazelcastSub, "hazelcastSub");
        }

        // --------------------------------------------------
        // Optional setters (fluent)
        // --------------------------------------------------

        public Builder nodeId(long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder eventStoreMode(@NotNull EventStoreMode mode) {
            this.eventStoreMode = Objects.requireNonNull(mode, "eventStoreMode");
            return this;
        }

        public Builder ringBufferNamePrefix(@NotNull String prefix) {
            if (prefix.isEmpty()) {
                throw new IllegalArgumentException("ringBufferNamePrefix cannot be empty");
            }
            this.ringBufferNamePrefix = prefix;
            return this;
        }

        // --------------------------------------------------
        // Build
        // --------------------------------------------------

        public HazelcastPubSubRingBufferEventStore build() {
            return new HazelcastPubSubRingBufferEventStore(
                    hazelcastPub,
                    hazelcastSub,
                    nodeId,
                    eventStoreMode,
                    ringBufferNamePrefix
            );
        }
    }


}
