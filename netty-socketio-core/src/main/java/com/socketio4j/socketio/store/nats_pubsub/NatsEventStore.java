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

import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.AbstractEventStore;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.SubscriptionRegistry;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;



/**
 * Unreliable NATS Core based EventStore.
 * Events are ephemeral and not replayed.
 */
public class NatsEventStore extends AbstractEventStore {

    private static final Logger log =
            LoggerFactory.getLogger(NatsEventStore.class);

    private final Connection nats;

    private final SubscriptionRegistry<Subscription, Dispatcher> subscriptions = new SubscriptionRegistry<>();

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
        super(nodeId, eventStoreMode, EventStoreMode.MULTI_CHANNEL, null, "");
        this.nats = Objects.requireNonNull(natsConnection, "natsConnection");
    }

    // ----------------------------------------------------------------------
    // EventStore SPI
    // ----------------------------------------------------------------------

    @Override
    public void publish0(EventType type, EventMessage msg) {
        stampNodeId(msg);

        try {
            byte[] data = EventMessageCodec.serialize(msg);
            nats.publish(channelName(type), data);
        } catch (Exception e) {
            log.warn("Failed to publish event {}", type, e);
        }
    }

    @Override
    public <T extends EventMessage> void subscribe0(
            EventType type,
            final EventListener<T> listener,
            Class<T> clazz) {

        final String subject = channelName(type);
        final Dispatcher dispatcher = nats.createDispatcher();

        Subscription subscription = dispatcher.subscribe(subject, (Message msg) -> {
            try {
                T event = EventMessageCodec.deserialize(msg.getData(), clazz);
                if (isRemote(event)) {
                    listener.onMessage(event);
                }
            } catch (Exception e) {
                log.warn("Failed to process event on subject {}", subject, e);
            }
        });

        subscriptions.add(type, subscription, dispatcher);
    }

    @Override
    public void unsubscribe0(EventType type) {
        subscriptions.remove(type, (subscription, dispatcher) -> {
            dispatcher.unsubscribe(subscription);
            nats.closeDispatcher(dispatcher);
        });
    }

    @Override
    public void shutdown0() {
        unsubscribeAll();
        subscriptions.clear();
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

        public NatsEventStore build() {
            return new NatsEventStore(
                    nats,
                    eventStoreMode,
                    nodeId
            );
        }
    }
}
