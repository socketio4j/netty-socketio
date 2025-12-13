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
package com.socketio4j.socketio.store.memory;

import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;


public class MemoryEventStore implements EventStore {

    @Override
    public EventStoreMode getEventStoreMode() {
        return EventStoreMode.SINGLE_CHANNEL; // No effect as local client will be sent first and then publish via adapter , for local no need to publish
    }

    @Override
    public EventStoreType getEventStoreType() {
        return EventStoreType.LOCAL;
    }

    @Override
    public void publish0(EventType type, EventMessage msg) {
    }

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, EventListener<T> listener, Class<T> clazz) {
    }

    @Override
    public void unsubscribe0(EventType type) {
    }

    @Override
    public void shutdown0() {
    }

}
