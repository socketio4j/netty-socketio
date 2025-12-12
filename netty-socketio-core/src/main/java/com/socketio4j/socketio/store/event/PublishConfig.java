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

/**
 * @author https://github.com/sanjomo
 * @date 09/12/25 11:15 pm
 */
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public class PublishConfig {

    private final PublishMode defaultMode;
    private final EnumMap<EventType, PublishMode> overrides;

    public PublishConfig(
            PublishMode defaultMode,
            Map<EventType, PublishMode> overrides
    ) {
        Objects.requireNonNull(defaultMode, "defaultMode must not be null");
        Objects.requireNonNull(overrides, "overrides must not be null");

        this.defaultMode = defaultMode;
        this.overrides = new EnumMap<>(EventType.class);
        this.overrides.putAll(overrides);
    }

    public PublishMode get(EventType type) {
        Objects.requireNonNull(type, "type must not be null");
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
