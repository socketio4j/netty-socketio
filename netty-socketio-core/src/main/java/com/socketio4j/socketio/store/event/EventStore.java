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


import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface EventStore {

    Logger log = LoggerFactory.getLogger(EventStore.class);


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
