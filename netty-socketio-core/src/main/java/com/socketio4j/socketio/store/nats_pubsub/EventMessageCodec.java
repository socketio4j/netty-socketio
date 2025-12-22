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
