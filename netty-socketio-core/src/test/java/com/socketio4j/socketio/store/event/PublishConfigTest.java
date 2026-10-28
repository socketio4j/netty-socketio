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

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PublishConfig Tests")
class PublishConfigTest {

    @ParameterizedTest
    @EnumSource(EventType.class)
    @DisplayName("Should apply the default mode to every event type")
    void shouldApplyDefaultMode(EventType type) {
        assertThat(PublishConfig.allReliable().get(type)).isEqualTo(PublishMode.RELIABLE);
        assertThat(PublishConfig.allUnreliable().get(type)).isEqualTo(PublishMode.UNRELIABLE);
    }

    @Test
    @DisplayName("Should expose the default mode")
    void shouldExposeDefaultMode() {
        assertThat(PublishConfig.allReliable().getDefaultMode()).isEqualTo(PublishMode.RELIABLE);
        assertThat(PublishConfig.allUnreliable().getDefaultMode()).isEqualTo(PublishMode.UNRELIABLE);
    }

    @Test
    @DisplayName("Should prefer overrides over the default mode")
    void shouldPreferOverrides() {
        Map<EventType, PublishMode> overrides = new EnumMap<>(EventType.class);
        overrides.put(EventType.DISPATCH, PublishMode.UNRELIABLE);

        PublishConfig reliable = PublishConfig.allReliable(overrides);
        assertThat(reliable.get(EventType.DISPATCH)).isEqualTo(PublishMode.UNRELIABLE);
        assertThat(reliable.get(EventType.CONNECT)).isEqualTo(PublishMode.RELIABLE);

        PublishConfig unreliable = PublishConfig.allUnreliable(
                Collections.singletonMap(EventType.CONNECT, PublishMode.RELIABLE));
        assertThat(unreliable.get(EventType.CONNECT)).isEqualTo(PublishMode.RELIABLE);
        assertThat(unreliable.get(EventType.DISPATCH)).isEqualTo(PublishMode.UNRELIABLE);
    }

    @Test
    @DisplayName("Should copy the overrides given at construction time")
    void shouldCopyOverrides() {
        Map<EventType, PublishMode> overrides = new HashMap<>();
        overrides.put(EventType.JOIN, PublishMode.UNRELIABLE);
        PublishConfig config = PublishConfig.allReliable(overrides);

        overrides.put(EventType.LEAVE, PublishMode.UNRELIABLE);

        assertThat(config.get(EventType.JOIN)).isEqualTo(PublishMode.UNRELIABLE);
        assertThat(config.get(EventType.LEAVE)).isEqualTo(PublishMode.RELIABLE);
    }

    @Test
    @DisplayName("Should reject null arguments")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> new PublishConfig(null, Collections.emptyMap()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("defaultMode must not be null");
        assertThatThrownBy(() -> new PublishConfig(PublishMode.RELIABLE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("overrides must not be null");
        assertThatThrownBy(() -> PublishConfig.allReliable().get(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("type must not be null");
    }

    @Test
    @DisplayName("Should print event types in lower case")
    void shouldPrintEventTypesInLowerCase() {
        assertThat(EventType.BULK_JOIN).hasToString("bulk_join");
        assertThat(EventType.valueOf("DISPATCH")).isEqualTo(EventType.DISPATCH);
        assertThat(EventStoreType.values()).containsExactly(EventStoreType.LOCAL,
                EventStoreType.PUBSUB, EventStoreType.STREAM, EventStoreType.BROKER);
    }
}
