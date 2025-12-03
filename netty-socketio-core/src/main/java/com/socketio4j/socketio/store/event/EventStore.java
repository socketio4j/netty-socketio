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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public interface EventStore {

    default EventStoreMode getMode(){
        return  EventStoreMode.MULTI_CHANNEL;
    }

    default EventStoreType getStoreType() {
        return EventStoreType.PUBSUB;
    }

    default Long getNodeId() {
        return ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    }

    default List<EventType> getEnabledTypes() {
        return Arrays.stream(EventType.values())
                .filter(t -> t != EventType.ALL_SINGLE_CHANNEL)
                .collect(Collectors.toList());
    }


    void publish(EventType type, EventMessage msg);

    <T extends EventMessage> void subscribe(EventType type, EventListener<T> listener, Class<T> clazz);

    void unsubscribe(EventType type);

    void shutdown();

}
