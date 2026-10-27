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

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

/**
 * Common state and channel naming shared by all broker backed {@link EventStore} implementations.
 */
public abstract class AbstractEventStore implements EventStore {

    protected final Long nodeId;
    protected final EventStoreMode eventStoreMode;
    protected final String channelPrefix;

    protected AbstractEventStore(@Nullable Long nodeId,
                                 @Nullable EventStoreMode eventStoreMode,
                                 EventStoreMode defaultEventStoreMode,
                                 @Nullable String channelPrefix,
                                 String defaultChannelPrefix) {
        if (nodeId == null) {
            nodeId = getNodeId();
        }
        this.nodeId = nodeId;

        if (eventStoreMode == null) {
            eventStoreMode = defaultEventStoreMode;
        }
        this.eventStoreMode = eventStoreMode;

        if (channelPrefix == null || channelPrefix.isEmpty()) {
            channelPrefix = defaultChannelPrefix;
        }
        this.channelPrefix = channelPrefix;
    }

    @Override
    public EventStoreMode getEventStoreMode() {
        return eventStoreMode;
    }

    /**
     * Maps the event type onto the type actually used for the broker channel:
     * every type collapses to {@link EventType#ALL_SINGLE_CHANNEL} in single channel mode.
     */
    protected EventType resolveType(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            return EventType.ALL_SINGLE_CHANNEL;
        }
        return type;
    }

    protected String channelName(EventType type) {
        return channelPrefix + resolveType(type).name();
    }

    protected void stampNodeId(EventMessage msg) {
        msg.setNodeId(nodeId);
    }

    /**
     * @return true when the message originates from another node and must be dispatched locally.
     */
    protected boolean isRemote(EventMessage msg) {
        return msg != null && !nodeId.equals(msg.getNodeId());
    }

    protected void unsubscribeAll() {
        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
    }

    protected void validateSubscribe(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode) && type != EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "Only ALL_SINGLE_CHANNEL allowed in SINGLE_CHANNEL mode");
        }
        if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode) && type == EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "ALL_SINGLE_CHANNEL not allowed in MULTI_CHANNEL mode");
        }
    }
}
