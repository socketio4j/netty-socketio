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
package com.socketio4j.socketio.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NamespaceMeters Tests")
class NamespaceMetersTest {

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("Should register every meter tagged with the namespace")
    void shouldRegisterAllMeters() {
        new NamespaceMeters(registry, "chat", false);

        assertThat(registry.getMeters())
                .allMatch(meter -> "chat".equals(meter.getId().getTag("namespace")))
                .extracting(meter -> meter.getId().getName())
                .contains("socketio.event.received",
                        "socketio.event.handled",
                        "socketio.event.failed",
                        "socketio.event.sent",
                        "socketio.event.unknown.total",
                        "socketio.event.unknown.distinct.estimate",
                        "socketio.ack.sent",
                        "socketio.ack.missing",
                        "socketio.connect.total",
                        "socketio.disconnect.total",
                        "socketio.clients.connected",
                        "socketio.room.join.total",
                        "socketio.room.leave.total",
                        "socketio.room.members",
                        "socketio.event.processing.time",
                        "socketio.ack.latency");
    }

    @Test
    @DisplayName("Should allow the empty namespace but reject null arguments")
    void shouldValidateConstructorArguments() {
        assertThat(new NamespaceMeters(registry, "", false)).isNotNull();
        assertThatThrownBy(() -> new NamespaceMeters(null, "chat", false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NamespaceMeters(registry, null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should expose the registered meters through accessors")
    void shouldExposeMetersThroughAccessors() {
        NamespaceMeters meters = new NamespaceMeters(registry, "chat", false);

        meters.getEventReceived().increment();
        meters.getEventHandled().increment();
        meters.getEventFailed().increment();
        meters.getEventSent().increment(2);
        meters.getEventUnknown().increment();
        meters.getAckSent().increment();
        meters.getAckMissing().increment();
        meters.getConnect().increment();
        meters.getDisconnect().increment();
        meters.getRoomJoin().increment();
        meters.getRoomLeave().increment();
        meters.getConnected().set(7);
        meters.getRoomMembers().set(3);

        assertThat(meters.getEventReceived().count()).isEqualTo(1);
        assertThat(meters.getEventSent().count()).isEqualTo(2);
        assertThat(meters.getConnected().get()).isEqualTo(7);
        assertThat(meters.getRoomMembers().get()).isEqualTo(3);
        assertThat(meters.getEventProcessing().count()).isZero();
        assertThat(meters.getAckLatency().count()).isZero();
    }

    @Test
    @DisplayName("Should keep the published distinct estimate empty until the publish interval elapses")
    void shouldNotPublishDistinctEstimateImmediately() {
        NamespaceMeters meters = new NamespaceMeters(registry, "chat", true);

        meters.recordUnknownEvent(1L);
        meters.recordUnknownEvent(2L);

        assertThat(registry.get("socketio.event.unknown.distinct.estimate").gauge().value()).isZero();
    }
}
