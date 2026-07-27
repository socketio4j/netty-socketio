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
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks broker subscriptions per {@link EventType} so they can be cancelled on unsubscribe.
 *
 * @param <I> registration id returned by the broker client
 * @param <S> broker handle needed to cancel the registration
 */
public final class SubscriptionRegistry<I, S> {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRegistry.class);

    private final ConcurrentMap<EventType, Queue<I>> registrationIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<I, S> subscriptions = new ConcurrentHashMap<>();

    public void add(EventType type, I registrationId, S subscription) {
        subscriptions.put(registrationId, subscription);
        registrationIds.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>()).add(registrationId);
    }

    /**
     * Removes every registration of the given type, invoking {@code canceller} for each one.
     * Cancellation failures are logged and never abort the remaining removals.
     */
    public void remove(EventType type, BiConsumer<I, S> canceller) {
        Queue<I> ids = registrationIds.remove(type);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (I id : ids) {
            S subscription = subscriptions.remove(id);
            if (subscription == null) {
                continue;
            }
            try {
                canceller.accept(id, subscription);
            } catch (Exception ex) {
                log.warn("Failed to remove subscription {} of type {}", id, type, ex);
            }
        }
    }

    public void clear() {
        registrationIds.clear();
        subscriptions.clear();
    }
}
