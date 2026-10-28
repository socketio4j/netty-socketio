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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.config.Config;
import com.hazelcast.config.ReliableTopicConfig;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.Ringbuffer;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.TopicOverloadPolicy;
import com.socketio4j.socketio.metrics.SocketIOMetrics;
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

    private final SocketIOMetrics metrics;

    private static final Logger log = LoggerFactory.getLogger(HazelcastPubSubRingBufferEventStore.class);
    private final String ringBufferNamePrefix;
    private final int ringBufferCapacity;
    private final int ringBufferTimeToLiveSeconds;
    private final TopicOverloadPolicy topicOverloadPolicy;

    private static final String DEFAULT_RING_BUFFER_NAME_PREFIX = "SOCKETIO4J:";

    public HazelcastPubSubRingBufferEventStore(
            @NonNull HazelcastInstance hazelcastPub,
            @NonNull HazelcastInstance hazelcastSub,
            @Nullable Long nodeId,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable String ringBufferNamePrefix,
            @Nullable SocketIOMetrics metrics,
            int ringBufferCapacity,
            int ringBufferTimeToLiveSeconds,
            @Nullable TopicOverloadPolicy topicOverloadPolicy
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
        if (metrics != null) {
            this.metrics = metrics;
        } else {
            this.metrics = SocketIOMetrics.noop();
        }

        if (ringBufferCapacity > 0) {
            this.ringBufferCapacity = ringBufferCapacity;
        } else {
            this.ringBufferCapacity = RingbufferConfig.DEFAULT_CAPACITY;
        }

        if (ringBufferTimeToLiveSeconds >= 0) {
            this.ringBufferTimeToLiveSeconds = ringBufferTimeToLiveSeconds;
        } else {
            this.ringBufferTimeToLiveSeconds = RingbufferConfig.DEFAULT_TTL_SECONDS;
        }

        if (topicOverloadPolicy != null) {
            this.topicOverloadPolicy = topicOverloadPolicy;
        } else {
            this.topicOverloadPolicy = TopicOverloadPolicy.BLOCK;
        }

        initializeRingBufferConfigurations();
    }

    public HazelcastPubSubRingBufferEventStore(
            @NonNull HazelcastInstance hazelcastPub,
            @NonNull HazelcastInstance hazelcastSub,
            @Nullable Long nodeId,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable String ringBufferNamePrefix,
            @Nullable SocketIOMetrics metrics
    ) {
        this(hazelcastPub, hazelcastSub, nodeId, eventStoreMode, ringBufferNamePrefix, metrics,
                RingbufferConfig.DEFAULT_CAPACITY, RingbufferConfig.DEFAULT_TTL_SECONDS, TopicOverloadPolicy.BLOCK);
    }

    public HazelcastPubSubRingBufferEventStore(
            @NonNull HazelcastInstance hazelcastPub,
            @NonNull HazelcastInstance hazelcastSub,
            @Nullable Long nodeId,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable String ringBufferNamePrefix
    ) {
        this(hazelcastPub, hazelcastSub, nodeId, eventStoreMode, ringBufferNamePrefix, SocketIOMetrics.noop());
    }

    private void initializeRingBufferConfigurations() {
        if (EventStoreMode.SINGLE_CHANNEL.equals(this.eventStoreMode)) {
            configureTopicAndRingBuffer(getRingBufferName(EventType.ALL_SINGLE_CHANNEL));
        } else {
            for (EventType type : EventType.values()) {
                configureTopicAndRingBuffer(getRingBufferName(type));
            }
        }
    }

    private void configureTopicAndRingBuffer(String topicName) {
        try {
            Config config = hazelcastPub.getConfig();
            if (config != null) {
                RingbufferConfig rbConfig = config.getRingbufferConfig(topicName);
                rbConfig.setCapacity(ringBufferCapacity);
                rbConfig.setTimeToLiveSeconds(ringBufferTimeToLiveSeconds);

                ReliableTopicConfig topicConfig = config.getReliableTopicConfig(topicName);
                topicConfig.setTopicOverloadPolicy(topicOverloadPolicy);
                log.debug("Configured Hazelcast ringbuffer '{}' with capacity={}, ttl={}, overloadPolicy={}",
                        topicName, ringBufferCapacity, ringBufferTimeToLiveSeconds, topicOverloadPolicy);
            }
        } catch (UnsupportedOperationException e) {
            verifyClientRingBufferCapacity(topicName);
        } catch (Exception e) {
            log.debug("Could not dynamically apply Config to Hazelcast cluster for topic {}: {}", topicName, e.getMessage());
        }
    }

    private void verifyClientRingBufferCapacity(String topicName) {
        try {
            Ringbuffer<?> rb = hazelcastPub.getRingbuffer(topicName);
            if (rb != null) {
                long clusterCapacity = rb.capacity();
                if (clusterCapacity != ringBufferCapacity) {
                    log.info("Hazelcast ring buffer '{}' cluster capacity is {} (configured client target: {}).",
                            topicName, clusterCapacity, ringBufferCapacity);
                }
            }
        } catch (Exception e) {
            log.debug("Could not verify ringbuffer capacity for topic {}: {}", topicName, e.getMessage());
        }
    }


    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);
        String topicName = getRingBufferName(type);
        ITopic<EventMessage> topic = activePubTopics.computeIfAbsent(type, k -> hazelcastPub.getReliableTopic(topicName));

        topic.publishAsync(msg).whenComplete((res, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event {} on reliable topic {}", msg, topicName, ex);
                metrics.eventPublishFailed(extractNamespace(msg));
            }
        });
    }

    @Override
    public SocketIOMetrics getMetrics() {
        return this.metrics;
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

    public int getRingBufferCapacity() {
        return ringBufferCapacity;
    }

    public int getRingBufferTimeToLiveSeconds() {
        return ringBufferTimeToLiveSeconds;
    }

    public TopicOverloadPolicy getTopicOverloadPolicy() {
        return topicOverloadPolicy;
    }

    public static final class Builder {

        // required
        private final HazelcastInstance hazelcastPub;
        private final HazelcastInstance hazelcastSub;

        // optional
        private @Nullable Long nodeId;
        private EventStoreMode eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        private String ringBufferNamePrefix = DEFAULT_RING_BUFFER_NAME_PREFIX;
        private SocketIOMetrics metrics = SocketIOMetrics.noop();
        private int ringBufferCapacity = RingbufferConfig.DEFAULT_CAPACITY;
        private int ringBufferTimeToLiveSeconds = RingbufferConfig.DEFAULT_TTL_SECONDS;
        private TopicOverloadPolicy topicOverloadPolicy = TopicOverloadPolicy.BLOCK;

        // --------------------------------------------------
        // Constructors
        // --------------------------------------------------

        public Builder(@NonNull HazelcastInstance hazelcastClient) {
            this(hazelcastClient, hazelcastClient);
        }

        public Builder(@NonNull HazelcastInstance hazelcastPub,
                       @NonNull HazelcastInstance hazelcastSub) {
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

        public Builder eventStoreMode(@NonNull EventStoreMode mode) {
            this.eventStoreMode = Objects.requireNonNull(mode, "eventStoreMode");
            return this;
        }

        public Builder ringBufferNamePrefix(@NonNull String prefix) {
            if (prefix.isEmpty()) {
                throw new IllegalArgumentException("ringBufferNamePrefix cannot be empty");
            }
            this.ringBufferNamePrefix = prefix;
            return this;
        }

        public Builder metrics(@Nullable SocketIOMetrics metrics) {
            if (metrics != null) {
                this.metrics = metrics;
            } else {
                this.metrics = SocketIOMetrics.noop();
            }
            return this;
        }

        public Builder ringBufferCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("ringBufferCapacity must be greater than 0");
            }
            this.ringBufferCapacity = capacity;
            return this;
        }

        public Builder ringBufferTimeToLiveSeconds(int ttlSeconds) {
            if (ttlSeconds < 0) {
                throw new IllegalArgumentException("ringBufferTimeToLiveSeconds must be non-negative");
            }
            this.ringBufferTimeToLiveSeconds = ttlSeconds;
            return this;
        }

        public Builder topicOverloadPolicy(@NonNull TopicOverloadPolicy topicOverloadPolicy) {
            this.topicOverloadPolicy = Objects.requireNonNull(topicOverloadPolicy, "topicOverloadPolicy");
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
                    ringBufferNamePrefix,
                    metrics,
                    ringBufferCapacity,
                    ringBufferTimeToLiveSeconds,
                    topicOverloadPolicy
            );
        }
    }


}
