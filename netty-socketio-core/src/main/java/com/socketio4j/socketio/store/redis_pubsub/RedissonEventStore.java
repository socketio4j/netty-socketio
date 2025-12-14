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

import java.util.Arrays;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;

/**
 * Unreliable Redis Pub/Sub based EventStore.
 * Events are ephemeral and not replayed.
 */
public class RedissonEventStore implements EventStore {

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;
    private final Long nodeId;
    private final EventStoreMode eventStoreMode;

    private final ConcurrentMap<EventType, Queue<Integer>> map = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, RTopic> activeSubTopics = new ConcurrentHashMap<>();
    private final ConcurrentMap<EventType, RTopic> activePubTopics = new ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(RedissonEventStore.class);

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------
    /**
     * API 4.x.y
     * @param redissonPub
     * @param redissonSub
     * @param eventStoreMode
     * @param nodeId
     */
    public RedissonEventStore(@NotNull RedissonClient redissonPub,
                              @NotNull RedissonClient redissonSub,
                              @Nullable EventStoreMode eventStoreMode,
                              @Nullable Long nodeId) {
        Objects.requireNonNull(redissonPub, "redissonPub is null");
        Objects.requireNonNull(redissonSub, "redissonSub is null");

        this.redissonPub = redissonPub;
        this.redissonSub = redissonSub;
        if (nodeId == null) {
            nodeId = getNodeId();
        }
        this.nodeId = nodeId;
        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        }
        this.eventStoreMode = eventStoreMode;
    }

    @Override
    public EventStoreMode getEventStoreMode(){
        return this.eventStoreMode;
    }
    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);
        RTopic topic = activePubTopics.computeIfAbsent(type, k -> {
            String topicName = getStreamName(k);
            return redissonPub.getTopic(topicName);
        });
        topic.publish(msg);
    }

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, final EventListener<T> listener, Class<T> clazz) {
        RTopic topic = redissonSub.getTopic(getStreamName(type));
        int regId = topic.addListener(clazz, (channel, msg) -> {
            if (!nodeId.equals(msg.getNodeId())) {
                listener.onMessage(msg);
            }
        });
        activeSubTopics.put(regId, topic);
        map.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>()).add(regId);
    }
    private String getStreamName(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            return  EventType.ALL_SINGLE_CHANNEL.name();
        }
        return type.name();
    }
    @Override
    public void unsubscribe0(EventType type) {

        Queue<Integer> regIds = map.remove(type);
        if (regIds == null || regIds.isEmpty()) {
            return;
        }
        for (Integer id : regIds) {
            RTopic topic = activeSubTopics.remove(id);
            if (topic == null) {
                continue;
            }
            try {
                topic.removeListener(id);
            } catch (Exception ex) {
                log.warn("Failed to remove listener {} from topic {}", id, getStreamName(type), ex);
            }
        }
    }

    @Override
    public void shutdown0() {
        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
        map.clear();
        activePubTopics.clear();
        activeSubTopics.clear();
    }

    public static final class Builder {

        // -------------------------
        // Required
        // -------------------------
        private final RedissonClient redissonPub;
        private final RedissonClient redissonSub;

        // -------------------------
        // Optional (defaults)
        // -------------------------
        private Long nodeId;
        private EventStoreMode eventStoreMode = EventStoreMode.MULTI_CHANNEL;

        // -------------------------
        // Constructors
        // -------------------------

        public Builder(@NotNull RedissonClient redissonClient) {
            this(redissonClient, redissonClient);
        }

        public Builder(@NotNull RedissonClient redissonPub,
                       @NotNull RedissonClient redissonSub) {
            this.redissonPub = Objects.requireNonNull(redissonPub, "redissonPub");
            this.redissonSub = Objects.requireNonNull(redissonSub, "redissonSub");
        }

        // -------------------------
        // Optional setters
        // -------------------------

        public Builder nodeId(long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder eventStoreMode(@NotNull EventStoreMode mode) {
            this.eventStoreMode = Objects.requireNonNull(mode, "eventStoreMode");
            return this;
        }

        // -------------------------
        // Build
        // -------------------------

        public RedissonEventStore build() {
            return new RedissonEventStore(
                    redissonPub,
                    redissonSub,
                    eventStoreMode,
                    nodeId
            );
        }
    }

}
