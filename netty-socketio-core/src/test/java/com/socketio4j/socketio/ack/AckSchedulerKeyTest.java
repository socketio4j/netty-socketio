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
package com.socketio4j.socketio.ack;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.scheduler.SchedulerKey;
import com.socketio4j.socketio.scheduler.SchedulerKey.Type;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AckSchedulerKey Tests")
class AckSchedulerKeyTest {

    private static final UUID SESSION_ID = UUID.randomUUID();

    @Test
    @DisplayName("Should expose the ack index")
    void shouldExposeIndex() {
        assertThat(new AckSchedulerKey(Type.ACK_TIMEOUT, SESSION_ID, 7).getIndex()).isEqualTo(7);
    }

    @Test
    @DisplayName("Should be equal for the same type, session and index")
    void shouldBeEqualForSameValues() {
        AckSchedulerKey key = new AckSchedulerKey(Type.ACK_TIMEOUT, SESSION_ID, 7);
        AckSchedulerKey same = new AckSchedulerKey(Type.ACK_TIMEOUT, SESSION_ID, 7);

        assertThat(key).isEqualTo(key)
                .isEqualTo(same)
                .hasSameHashCodeAs(same);
    }

    @Test
    @DisplayName("Should not be equal when type, session or index differ")
    void shouldNotBeEqualForDifferentValues() {
        AckSchedulerKey key = new AckSchedulerKey(Type.ACK_TIMEOUT, SESSION_ID, 7);

        assertThat(key)
                .isNotEqualTo(new AckSchedulerKey(Type.ACK_TIMEOUT, SESSION_ID, 8))
                .isNotEqualTo(new AckSchedulerKey(Type.PING_TIMEOUT, SESSION_ID, 7))
                .isNotEqualTo(new AckSchedulerKey(Type.ACK_TIMEOUT, UUID.randomUUID(), 7));
        assertThat(key.hashCode())
                .isNotEqualTo(new AckSchedulerKey(Type.ACK_TIMEOUT, SESSION_ID, 8).hashCode());
    }

    @Test
    @DisplayName("Should not be equal to null, other types or the plain scheduler key")
    void shouldNotBeEqualToOtherTypes() {
        AckSchedulerKey key = new AckSchedulerKey(Type.ACK_TIMEOUT, SESSION_ID, 7);

        assertThat(key)
                .isNotEqualTo(null)
                .isNotEqualTo("key")
                .isNotEqualTo(new SchedulerKey(Type.ACK_TIMEOUT, SESSION_ID));
    }
}
