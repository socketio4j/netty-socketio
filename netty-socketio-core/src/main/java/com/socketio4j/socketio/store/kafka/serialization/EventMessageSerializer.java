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

import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.socketio4j.socketio.store.event.EventMessage;

public final class EventMessageSerializer
        implements Serializer<EventMessage> {
    private static final Logger log = LoggerFactory.getLogger(EventMessageSerializer.class);

    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                    .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                    .build();

    @Override
    public byte[] serialize(String topic, EventMessage data) {

        if (data == null) {
            return null;
        }

        try {
            return MAPPER.writeValueAsBytes(data);
        } catch (Exception e) {
           log.error("Failed to serialize EventMessage for topic {}", topic, e);
            return null;
        }
    }
}
