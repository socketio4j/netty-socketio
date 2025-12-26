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
package com.socketio4j.socketio.store.kafka.serialization;

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:21 pm
 */

import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.transport.PollingTransport;

import static com.socketio4j.socketio.store.event.EventStore.log;

public final class EventMessageDeserializer
        implements Deserializer<EventMessage> {
    private static final Logger log = LoggerFactory.getLogger(EventMessageDeserializer.class);

    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                    .build();

    @Override
    public EventMessage deserialize(String topic, byte[] data) {

        if (data == null || data.length == 0) {
            return null;
        }

        try {
            return MAPPER.readValue(data, EventMessage.class);
        } catch (Exception e) {
            // IMPORTANT: do not throw
            log.error("Failed to deserialize EventMessage on topic {}", topic, e);
            return null;
        }
    }
}
