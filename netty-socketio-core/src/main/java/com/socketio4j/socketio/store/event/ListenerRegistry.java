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

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds the local listeners of poll based event stores and dispatches messages to them.
 */
public final class ListenerRegistry {

    private final ConcurrentMap<EventType, Queue<ListenerRegistration<? extends EventMessage>>> listeners =
            new ConcurrentHashMap<>();

    public <T extends EventMessage> ListenerRegistration<T> register(EventType type,
                                                                     EventListener<T> listener,
                                                                     Class<T> clazz) {
        ListenerRegistration<T> registration = new ListenerRegistration<>(listener, clazz);
        listeners.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>()).add(registration);
        return registration;
    }

    public void unregister(EventType type, ListenerRegistration<? extends EventMessage> registration) {
        Queue<ListenerRegistration<? extends EventMessage>> queue = listeners.get(type);
        if (queue != null) {
            queue.remove(registration);
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends EventMessage> void dispatch(EventType type, EventMessage msg) {
        Queue<ListenerRegistration<? extends EventMessage>> registrations = listeners.get(type);
        if (registrations == null) {
            return;
        }
        for (ListenerRegistration<? extends EventMessage> registration : registrations) {
            if (registration.getClazz().isInstance(msg)) {
                ((ListenerRegistration<T>) registration).getListener().onMessage((T) msg);
            }
        }
    }

    public void remove(EventType type) {
        listeners.remove(type);
    }

    public boolean isEmpty() {
        return listeners.isEmpty();
    }

    public void clear() {
        listeners.clear();
    }
}
