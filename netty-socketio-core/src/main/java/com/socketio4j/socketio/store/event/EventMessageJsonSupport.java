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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.UntypedObjectDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Shared JSON ObjectMapper builder for JSON-based EventStores (NATS, Kafka, Redis Streams, etc.).
 * Guarantees lossless JSON serialization and deserialization of binary byte arrays (byte[])
 * embedded inside EventMessages and Packets.
 */
public final class EventMessageJsonSupport {
    private static final String BYTES_FIELD = "$bytes";
    private EventMessageJsonSupport() {
    }

    public static ObjectMapper createObjectMapper() {
        SimpleModule module = new SimpleModule("EventMessageJsonModule");

        // Custom byte[] serializer -> {"$bytes": "<base64>"}
        module.addSerializer(byte[].class, new JsonSerializer<byte[]>() {
            @Override
            public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null) {
                    gen.writeNull();
                } else {
                    gen.writeStartObject();
                    gen.writeStringField(BYTES_FIELD, Base64.getEncoder().encodeToString(value));
                    gen.writeEndObject();
                }
            }
        });
        module.addDeserializer(byte[].class, new JsonDeserializer<byte[]>() {

            @Override
            public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {

                if (p.currentToken() == JsonToken.START_OBJECT) {
                    JsonNode node = p.readValueAsTree();
                    JsonNode bytes = node.get(BYTES_FIELD);

                    if (bytes != null && bytes.isTextual()) {
                        return Base64.getDecoder().decode(bytes.asText());
                    }

                    return ctxt.reportInputMismatch(
                            byte[].class,
                            "Expected object containing '$bytes' field");
                }

                if (p.currentToken() == JsonToken.START_ARRAY) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        baos.write((byte) p.getIntValue());
                    }
                    return baos.toByteArray();
                }

                // Default Jackson handling for Base64 string
                return p.getBinaryValue();
            }
        });
        // Custom UntypedObjectDeserializer -> converts {"$bytes": "<base64>"} back to byte[]
        module.addDeserializer(Object.class, new EventMessageObjectDeserializer());

        return JsonMapper.builder()
                .addModule(module)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                .build();
    }

    @SuppressWarnings("deprecation")
    public static class EventMessageObjectDeserializer extends UntypedObjectDeserializer {

        private static final long serialVersionUID = 1L;

        public EventMessageObjectDeserializer() {
            super((JavaType) null, (JavaType) null);
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            Object obj = super.deserialize(p, ctxt);
            return convertBytesPlaceholders(obj);
        }

        private Object convertBytesPlaceholders(Object obj) {
            if (obj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) obj;
                if (map.size() == 1 && map.containsKey(BYTES_FIELD)) {
                    Object val = map.get(BYTES_FIELD);
                    if (val instanceof String) {
                        return Base64.getDecoder().decode((String) val);
                    }
                }
                Map<Object, Object> result = new HashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(entry.getKey(), convertBytesPlaceholders(entry.getValue()));
                }
                return result;
            } else if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                List<Object> result = new ArrayList<>(list.size());
                for (Object item : list) {
                    result.add(convertBytesPlaceholders(item));
                }
                return result;
            }
            return obj;
        }
    }
}
