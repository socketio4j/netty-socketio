package com.socketio4j.socketio.store.kafka.serialization;

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:21 pm
 */

import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.socketio4j.socketio.store.event.EventMessage;

import static com.socketio4j.socketio.store.event.EventStore.log;

public final class EventMessageDeserializer
        implements Deserializer<EventMessage> {

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
            LoggerFactory.getLogger(getClass())
                    .error("Failed to deserialize EventMessage on topic {}", topic, e);
            return null;
        }
    }
}
