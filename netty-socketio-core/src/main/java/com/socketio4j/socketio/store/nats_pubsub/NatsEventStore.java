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
package com.socketio4j.socketio.store.nats_pubsub;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;



/**
 * Unreliable NATS Core based EventStore.
 * Events are ephemeral and not replayed.
 */
public class NatsEventStore implements EventStore {

    private static final Logger log =
            LoggerFactory.getLogger(NatsEventStore.class);

    private final Connection nats;
    private final Long nodeId;
    private final EventStoreMode eventStoreMode;
    private final String subjectPrefix;
    private final int partitionCount;

    /**
     * EventType -> subscriptions
     */
    private final ConcurrentMap<EventType, Queue<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    /**
     * Subscription -> dispatcher
     */
    private final ConcurrentMap<Subscription, Dispatcher> activeDispatchers =
            new ConcurrentHashMap<>();

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------

    /**
     * API 4.x.y
     *
     * @param natsConnection shared NATS connection
     * @param eventStoreMode SINGLE_CHANNEL or MULTI_CHANNEL
     * @param nodeId node identifier (used to ignore self-published events)
     */
    public NatsEventStore(@NotNull Connection natsConnection,
                          @Nullable EventStoreMode eventStoreMode,
                          @Nullable Long nodeId) {
        this(natsConnection, eventStoreMode, nodeId, null, DEFAULT_PARTITION_COUNT);
    }

    private NatsEventStore(@NotNull Connection natsConnection,
                          @Nullable EventStoreMode eventStoreMode,
                          @Nullable Long nodeId,
                          @Nullable String subjectPrefix,
                          int partitionCount) {

        this.nats = Objects.requireNonNull(natsConnection, "natsConnection");

        if (nodeId == null) {
            nodeId = getNodeId();
        }
        this.nodeId = nodeId;

        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        }
        this.eventStoreMode = eventStoreMode;
        this.subjectPrefix = (subjectPrefix != null) ? subjectPrefix : "";
        this.partitionCount = partitionCount > 0 ? partitionCount : DEFAULT_PARTITION_COUNT;
    }

    // ----------------------------------------------------------------------
    // EventStore SPI
    // ----------------------------------------------------------------------

    @Override
    public EventStoreMode getEventStoreMode() {
        return eventStoreMode;
    }

    @Override
    public int getPartitionCount() {
        return partitionCount;
    }

    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);

        try {
            byte[] data = EventMessageCodec.serialize(msg);
            String subject = resolveChannelName(subjectPrefix, type, msg, partitionCount, eventStoreMode);
            nats.publish(subject, data);
        } catch (Exception e) {
            log.warn("Failed to publish event {}", type, e);
        }
    }

    @Override
    public <T extends EventMessage> void subscribe0(
            EventType type,
            final EventListener<T> listener,
            Class<T> clazz) {

        List<String> subjects = resolveSubscriptionChannels(subjectPrefix, type, partitionCount, eventStoreMode);
        for (String subject : subjects) {
            final Dispatcher dispatcher = nats.createDispatcher();

            Subscription subscription = dispatcher.subscribe(subject, (Message msg) -> {
                try {
                    T event = EventMessageCodec.deserialize(msg.getData(), clazz);
                    if (!nodeId.equals(event.getNodeId())) {
                        listener.onMessage(event);
                    }
                } catch (Exception e) {
                    log.warn("Failed to process event on subject {}", subject, e);
                }
            });

            activeDispatchers.put(subscription, dispatcher);
            subscriptions
                    .computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>())
                    .add(subscription);
        }
    }

    @Override
    public void unsubscribe0(EventType type) {
        Queue<Subscription> subs = subscriptions.remove(type);
        if (subs == null || subs.isEmpty()) {
            return;
        }

        for (Subscription sub : subs) {
            try {
                Dispatcher dispatcher = activeDispatchers.remove(sub);
                if (dispatcher != null) {
                    dispatcher.unsubscribe(sub);
                    nats.closeDispatcher(dispatcher);
                }
            } catch (Exception e) {
                log.warn("Failed to unsubscribe from {}", type, e);
            }
        }
    }

    @Override
    public void shutdown0() {
        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
        subscriptions.clear();
        activeDispatchers.clear();
    }

    // ----------------------------------------------------------------------
    // Builder (matches RedissonEventStore style)
    // ----------------------------------------------------------------------

    public static final class Builder {

        // Required
        private final Connection nats;

        // Optional
        private Long nodeId;
        private EventStoreMode eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        private String subjectPrefix = "";
        private int partitionCount = DEFAULT_PARTITION_COUNT;

        public Builder(@NotNull Connection nats) {
            this.nats = Objects.requireNonNull(nats, "nats");
        }

        public Builder nodeId(long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder eventStoreMode(@NotNull EventStoreMode mode) {
            this.eventStoreMode = Objects.requireNonNull(mode, "eventStoreMode");
            return this;
        }

        public Builder subjectPrefix(String prefix) {
            this.subjectPrefix = prefix;
            return this;
        }

        public Builder partitionCount(int partitionCount) {
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("partitionCount must be > 0");
            }
            this.partitionCount = partitionCount;
            return this;
        }

        public NatsEventStore build() {
            return new NatsEventStore(
                    nats,
                    eventStoreMode,
                    nodeId,
                    subjectPrefix,
                    partitionCount
            );
        }
    }
}
