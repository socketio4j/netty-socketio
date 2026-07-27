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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import com.socketio4j.socketio.store.event.AbstractEventStore;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.SubscriptionRegistry;


public class HazelcastPubSubEventStore extends AbstractEventStore {

    private final HazelcastInstance hazelcastPub;
    private final HazelcastInstance hazelcastSub;
    private static final String DEFAULT_TOPIC_NAME_PREFIX = "SOCKETIO4J:";

    private final SubscriptionRegistry<UUID, ITopic<?>> subscriptions = new SubscriptionRegistry<>();
    private final ConcurrentMap<EventType, ITopic<EventMessage>> activePubTopics = new ConcurrentHashMap<>();

    public HazelcastPubSubEventStore(
            @NotNull HazelcastInstance hazelcastPub,
            @NotNull HazelcastInstance hazelcastSub,
            @Nullable Long nodeId,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable String topicPrefix
    ) {
        super(nodeId, eventStoreMode, EventStoreMode.MULTI_CHANNEL, topicPrefix, DEFAULT_TOPIC_NAME_PREFIX);
        this.hazelcastPub = Objects.requireNonNull(hazelcastPub, "hazelcastPub cannot be null");
        this.hazelcastSub = Objects.requireNonNull(hazelcastSub, "hazelcastSub cannot be null");
    }

    @Override
    public void publish0(EventType type, EventMessage msg) {
        stampNodeId(msg);

        ITopic<EventMessage> topic = activePubTopics.computeIfAbsent(type, k -> hazelcastPub.getTopic(channelName(k)));

        topic.publish(msg);
    }

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, final EventListener<T> listener, Class<T> clazz) {

        ITopic<T> topic = hazelcastSub.getTopic(channelName(type));

        UUID regId = topic.addMessageListener(msg -> {
            if (isRemote(msg.getMessageObject())) {
                listener.onMessage(msg.getMessageObject());
            }
        });

        subscriptions.add(type, regId, topic);
    }

    @Override
    public void unsubscribe0(EventType type) {
        subscriptions.remove(type, (id, topic) -> topic.removeMessageListener(id));
    }

    @Override
    public void shutdown0() {
        unsubscribeAll();
        subscriptions.clear();
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
        private String topicNamePrefix = DEFAULT_TOPIC_NAME_PREFIX;

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

        public HazelcastPubSubEventStore.Builder nodeId(long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public HazelcastPubSubEventStore.Builder eventStoreMode(@NotNull EventStoreMode mode) {
            this.eventStoreMode = Objects.requireNonNull(mode, "eventStoreMode");
            return this;
        }

        public HazelcastPubSubEventStore.Builder topicNamePrefix(@NotNull String prefix) {
            if (prefix.isEmpty()) {
                throw new IllegalArgumentException("ringBufferNamePrefix cannot be empty");
            }
            this.topicNamePrefix = prefix;
            return this;
        }

        // --------------------------------------------------
        // Build
        // --------------------------------------------------

        public HazelcastPubSubEventStore build() {
            return new HazelcastPubSubEventStore(
                    hazelcastPub,
                    hazelcastSub,
                    nodeId,
                    eventStoreMode,
                    topicNamePrefix
            );
        }
    }

}
