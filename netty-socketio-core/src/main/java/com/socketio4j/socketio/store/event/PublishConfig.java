package com.socketio4j.socketio.store.event;

/**
 * @author https://github.com/sanjomo
 * @date 09/12/25 11:15 pm
 */
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class PublishConfig {

    private final PublishMode defaultMode;
    private final EnumMap<EventType, PublishMode> overrides;

    public PublishConfig(
            PublishMode defaultMode,
            Map<EventType, PublishMode> overrides
    ) {
        this.defaultMode = defaultMode;
        this.overrides = new EnumMap<>(EventType.class);
        this.overrides.putAll(overrides);
    }

    public PublishMode get(EventType type) {
        return overrides.getOrDefault(type, defaultMode);
    }

    // FACTORY HELPERS

    public static PublishConfig allReliable() {
        return new PublishConfig(PublishMode.RELIABLE, Collections.emptyMap());
    }

    public static PublishConfig allUnreliable() {
        return new PublishConfig(PublishMode.UNRELIABLE, Collections.emptyMap());
    }

    public static PublishConfig allReliable(Map<EventType, PublishMode> overrides) {
        return new PublishConfig(PublishMode.RELIABLE, overrides);
    }

    public static PublishConfig allUnreliable(Map<EventType, PublishMode> overrides) {
        return new PublishConfig(PublishMode.UNRELIABLE, overrides);
    }

    public PublishMode getDefaultMode() {
        return defaultMode;
    }
}
