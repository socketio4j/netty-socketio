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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@DisplayName("MicrometerSocketIOMetrics Tests")
class MicrometerSocketIOMetricsTest {

    private static final String NS = "chat";

    private MeterRegistry registry;
    private MicrometerSocketIOMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerSocketIOMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        metrics.close();
        registry.close();
    }

    private Counter counter(String name, String namespace) {
        return registry.get(name).tag("namespace", namespace).counter();
    }

    private Gauge gauge(String name, String namespace) {
        return registry.get(name).tag("namespace", namespace).gauge();
    }

    private Timer timer(String name, String namespace) {
        return registry.get(name).tag("namespace", namespace).timer();
    }

    @Test
    @DisplayName("Should reject a null registry")
    void shouldRejectNullRegistry() {
        assertThatThrownBy(() -> new MicrometerSocketIOMetrics(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("registry can not be null");
    }

    @Test
    @DisplayName("Should expose the registry it was built with")
    void shouldExposeRegistry() {
        assertThat(metrics.getRegistry()).isSameAs(registry);
        assertThat(metrics.registry()).isSameAs(registry);
        assertThat(MicrometerMetricsFactory.using(registry, true).getRegistry()).isSameAs(registry);
    }

    @Test
    @DisplayName("Should count received, handled and failed events")
    void shouldCountEvents() {
        metrics.eventReceived(NS);
        metrics.eventReceived(NS);
        metrics.eventHandled(NS, TimeUnit.MILLISECONDS.toNanos(20));
        metrics.eventFailed(NS);
        metrics.unknownEventReceived(NS);

        assertThat(counter("socketio.event.received", NS).count()).isEqualTo(2);
        assertThat(counter("socketio.event.handled", NS).count()).isEqualTo(1);
        assertThat(counter("socketio.event.failed", NS).count()).isEqualTo(1);
        assertThat(counter("socketio.event.unknown.total", NS).count()).isEqualTo(1);
        assertThat(timer("socketio.event.processing.time", NS).totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(20.0);
    }

    @Test
    @DisplayName("Should not record event processing time for non positive durations")
    void shouldNotRecordNonPositiveEventDuration() {
        metrics.eventHandled(NS, 0);

        assertThat(counter("socketio.event.handled", NS).count()).isEqualTo(1);
        assertThat(timer("socketio.event.processing.time", NS).count()).isZero();
    }

    @Test
    @DisplayName("Should count sent events by recipient amount and ignore empty broadcasts")
    void shouldCountSentEvents() {
        metrics.eventSent(NS, 3);
        metrics.eventSent(NS, 0);

        assertThat(counter("socketio.event.sent", NS).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should record ack counters and latency")
    void shouldRecordAckMetrics() {
        metrics.ackSent(NS, TimeUnit.MILLISECONDS.toNanos(5));
        metrics.ackSent(NS, 0);
        metrics.ackMissing(NS);

        assertThat(counter("socketio.ack.sent", NS).count()).isEqualTo(2);
        assertThat(counter("socketio.ack.missing", NS).count()).isEqualTo(1);
        assertThat(timer("socketio.ack.latency", NS).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should track connected clients gauge")
    void shouldTrackConnectedClients() {
        metrics.connect(NS);
        metrics.connect(NS);
        metrics.disconnect(NS);

        assertThat(counter("socketio.connect.total", NS).count()).isEqualTo(2);
        assertThat(counter("socketio.disconnect.total", NS).count()).isEqualTo(1);
        assertThat(gauge("socketio.clients.connected", NS).value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should track room members gauge and never drop below zero")
    void shouldTrackRoomMembers() {
        metrics.roomJoin(NS);
        metrics.roomLeave(NS);
        metrics.roomLeave(NS);

        assertThat(counter("socketio.room.join.total", NS).count()).isEqualTo(1);
        assertThat(counter("socketio.room.leave.total", NS).count()).isEqualTo(2);
        assertThat(gauge("socketio.room.members", NS).value()).isZero();
    }

    @Test
    @DisplayName("Should report the empty namespace as 'default'")
    void shouldMapEmptyNamespaceToDefault() {
        metrics.eventReceived("");
        metrics.eventHandled("", 1);
        metrics.eventFailed("");
        metrics.eventSent("", 1);
        metrics.unknownEventReceived("");
        metrics.ackSent("", 1);
        metrics.ackMissing("");
        metrics.connect("");
        metrics.disconnect("");
        metrics.roomJoin("");
        metrics.roomLeave("");

        assertThat(counter("socketio.event.received", "default").count()).isEqualTo(1);
        assertThat(counter("socketio.connect.total", "default").count()).isEqualTo(1);
        assertThat(counter("socketio.room.join.total", "default").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should keep separate meters per namespace")
    void shouldKeepSeparateMetersPerNamespace() {
        metrics.eventReceived(NS);
        metrics.eventReceived("news");

        assertThat(counter("socketio.event.received", NS).count()).isEqualTo(1);
        assertThat(counter("socketio.event.received", "news").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should publish the distinct unknown event name estimate")
    void shouldPublishDistinctUnknownEventNameEstimate() {
        metrics.unknownEventNames(NS, null);
        metrics.unknownEventNames(NS, "first");

        // the estimate snapshot is published periodically, not on every record
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    metrics.unknownEventNames(NS, "second");
                    assertThat(gauge("socketio.event.unknown.distinct.estimate", NS).value())
                            .isGreaterThan(0.0);
                });
    }

    @Test
    @DisplayName("Should publish percentile histograms when enabled")
    void shouldPublishHistogramWhenEnabled() {
        MicrometerSocketIOMetrics histogramMetrics = MicrometerMetricsFactory.using(registry, true);
        try {
            histogramMetrics.eventHandled(NS, TimeUnit.MILLISECONDS.toNanos(3));

            assertThat(timer("socketio.event.processing.time", NS).count()).isEqualTo(1);
            assertThat(timer("socketio.event.processing.time", NS).takeSnapshot().percentileValues())
                    .isEmpty();
        } finally {
            histogramMetrics.close();
        }
    }

    @Test
    @DisplayName("Should ignore every call on the noop implementation")
    void shouldIgnoreCallsOnNoopMetrics() {
        SocketIOMetrics noop = SocketIOMetrics.noop();

        noop.eventReceived(NS);
        noop.eventHandled(NS, 1);
        noop.eventFailed(NS);
        noop.eventSent(NS, 1);
        noop.unknownEventReceived(NS);
        noop.unknownEventNames(NS, "name");
        noop.ackSent(NS, 1);
        noop.ackMissing(NS);
        noop.connect(NS);
        noop.disconnect(NS);
        noop.roomJoin(NS);
        noop.roomLeave(NS);

        assertThat(SocketIOMetrics.noop()).isSameAs(noop);
        assertThat(registry.getMeters()).isEmpty();
    }
}
