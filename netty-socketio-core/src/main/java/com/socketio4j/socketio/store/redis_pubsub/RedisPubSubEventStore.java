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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.store.event.AbstractEventStore;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.SubscriptionRegistry;

/**
 * Unreliable Redis Pub/Sub based EventStore.
 * Events are ephemeral and not replayed.
 */
public class RedisPubSubEventStore extends AbstractEventStore {

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;

    private final SubscriptionRegistry<Integer, RTopic> subscriptions = new SubscriptionRegistry<>();
    private final ConcurrentMap<EventType, RTopic> activePubTopics = new ConcurrentHashMap<>();

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
    public RedisPubSubEventStore(@NotNull RedissonClient redissonPub,
                              @NotNull RedissonClient redissonSub,
                              @Nullable EventStoreMode eventStoreMode,
                              @Nullable Long nodeId) {
        super(nodeId, eventStoreMode, EventStoreMode.MULTI_CHANNEL, null, "");
        this.redissonPub = Objects.requireNonNull(redissonPub, "redissonPub is null");
        this.redissonSub = Objects.requireNonNull(redissonSub, "redissonSub is null");
    }

    @Override
    public void publish0(EventType type, EventMessage msg) {
        stampNodeId(msg);
        RTopic topic = activePubTopics.computeIfAbsent(type, k -> redissonPub.getTopic(channelName(k)));
        topic.publish(msg);
    }

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, final EventListener<T> listener, Class<T> clazz) {
        RTopic topic = redissonSub.getTopic(channelName(type));
        int regId = topic.addListener(clazz, (channel, msg) -> {
            if (isRemote(msg)) {
                listener.onMessage(msg);
            }
        });
        subscriptions.add(type, regId, topic);
    }

    @Override
    public void unsubscribe0(EventType type) {
        subscriptions.remove(type, (id, topic) -> topic.removeListener(id));
    }

    @Override
    public void shutdown0() {
        unsubscribeAll();
        subscriptions.clear();
        activePubTopics.clear();
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

        public RedisPubSubEventStore build() {
            return new RedisPubSubEventStore(
                    redissonPub,
                    redissonSub,
                    eventStoreMode,
                    nodeId
            );
        }
    }

}
