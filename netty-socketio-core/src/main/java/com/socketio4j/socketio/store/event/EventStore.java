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
package com.socketio4j.socketio.store.event;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface EventStore {

    Logger log = LoggerFactory.getLogger(EventStore.class);

    int DEFAULT_PARTITION_COUNT = 16;

    default int getPartitionCount() {
        return DEFAULT_PARTITION_COUNT;
    }

    default EventStoreMode getEventStoreMode() {
        return EventStoreMode.MULTI_CHANNEL;
    }

    default EventStoreType getEventStoreType() {
        return EventStoreType.PUBSUB;
    }

    default PublishMode getPublishMode(){
        return PublishMode.UNRELIABLE;
    }

    default Long getNodeId() {
        return ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    }

    /**
     * Resolves the channel/topic/stream name for publishing an event message based on
     * the store prefix, event type, message partition key, partition count, and store mode.
     */
    default String resolveChannelName(String prefix, EventType type, EventMessage msg, int partitionCount, EventStoreMode mode) {
        if (prefix == null) {
            prefix = "";
        }
        if (mode == EventStoreMode.SINGLE_CHANNEL) {
            return prefix + EventType.ALL_SINGLE_CHANNEL.name();
        }
        if (mode == EventStoreMode.PARTITIONED_CHANNEL) {
            String partitionKey = msg != null ? msg.getPartitionKey() : null;
            if (partitionKey != null && !partitionKey.isEmpty()) {
                int p = (partitionKey.hashCode() & 0x7FFFFFFF) % Math.max(1, partitionCount);
                return prefix + "room_" + p;
            }
            return prefix + "lifecycle";
        }
        return prefix + type.name();
    }

    /**
     * Resolves all channel/topic/stream names that must be subscribed to for a given event type
     * based on the store prefix, partition count, and store mode.
     */
    default List<String> resolveSubscriptionChannels(String prefix, EventType type, int partitionCount, EventStoreMode mode) {
        if (prefix == null) {
            prefix = "";
        }
        if (mode == EventStoreMode.SINGLE_CHANNEL) {
            return Collections.singletonList(prefix + EventType.ALL_SINGLE_CHANNEL.name());
        }
        if (mode == EventStoreMode.PARTITIONED_CHANNEL) {
            int count = Math.max(1, partitionCount);
            List<String> channels = new ArrayList<>(count + 1);
            for (int i = 0; i < count; i++) {
                channels.add(prefix + "room_" + i);
            }
            channels.add(prefix + "lifecycle");
            return channels;
        }
        return Collections.singletonList(prefix + type.name());
    }

    default void publish(EventType type, EventMessage msg) {
        try {
            publish0(type, msg);
        } catch (Exception e) {
            log.error("Error publishing event {}", e.getMessage(), e);
            //re-throw to keep the behavior
            throw e;
        }
    }

    void publish0(EventType type, EventMessage msg);

    default <T extends EventMessage> void subscribe(EventType type, EventListener<T> listener, Class<T> clazz) {
        try {
            subscribe0(type, listener, clazz);
        } catch (Exception e) {
            log.error("Error subscribing event {}", e.getMessage(), e);
            //re-throw to keep the behavior
            throw e;
        }
    }

    <T extends EventMessage> void subscribe0(EventType type, EventListener<T> listener, Class<T> clazz);

    default void unsubscribe(EventType type) {
        try {
            unsubscribe0(type);
        } catch (Exception e) {
            log.error("Error unsubscribing event {}", e.getMessage(), e);
            //re-throw to keep the behavior
            throw e;
        }
    }

    void unsubscribe0(EventType type);

    default void shutdown() {
        try {
            shutdown0();
        } catch (Exception e) {
            log.error("Error shutting down event store {}", e.getMessage(), e);
            //re-throw to keep the behavior
            throw e;
        }
    }

    void shutdown0();

}
