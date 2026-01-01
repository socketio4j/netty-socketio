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

/**
 * @author https://github.com/sanjomo
 * @date 22/12/25 4:04 pm
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socketio4j.socketio.store.event.EventMessage;

public final class EventMessageCodec {

    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        MAPPER.configure(
                com.fasterxml.jackson.databind.DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES,
                false
        );
    }

    private EventMessageCodec() {
    }

    public static byte[] serialize(EventMessage msg) {
        try {
            return MAPPER.writeValueAsBytes(msg);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize EventMessage", e);
        }
    }

    public static <T extends EventMessage> T deserialize(byte[] data, Class<T> clazz) {
        try {
            return MAPPER.readValue(data, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize EventMessage", e);
        }
    }
}
