package com.socketio4j.socketio.store.kafka.serialization;

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:21 pm
 */

import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.socketio4j.socketio.store.event.EventMessage;

public final class EventMessageSerializer
        implements Serializer<EventMessage> {

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
            LoggerFactory.getLogger(getClass())
                    .error("Failed to serialize EventMessage for topic {}", topic, e);
            return null;
        }
    }
}
