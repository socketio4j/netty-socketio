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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.agkn.hll.HLL;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-namespace metric container.
 *
 * All meters are registered exactly once during construction.
 * Runtime code must only mutate counters, timers, and atomic values.
 *
 * @author https://github.com/sanjomo
 * @date 05/01/26 3:10 pm
 */
final class NamespaceMeters {

    /* ===================== Event Counters ===================== */

    final Counter eventReceived;
    final Counter eventHandled;
    final Counter eventFailed;
    final Counter eventSent;
    final Counter eventUnknown;
    /**
     * HyperLogLog for distinct unknown event names.
     * (namespace:eventName hashed outside)
     */
    final HLL unknownEventHll;
    /* ===================== ACK ===================== */

    final Counter ackSent;
    final Counter ackMissing;

    /* ===================== Connections ===================== */

    final Counter connect;
    final Counter disconnect;

    final AtomicInteger connected;

    /* ===================== Rooms ===================== */

    final Counter roomJoin;
    final Counter roomLeave;

    final AtomicInteger roomMembers;

    /* ===================== Timers ===================== */

    final Timer eventProcessing;
    final Timer ackLatency;

    NamespaceMeters(MeterRegistry registry, String ns, boolean histogramEnabled) {

        /* ---------- Events ---------- */

        this.eventReceived = Counter.builder("socketio.event.received")
                .tag("namespace", ns)
                .register(registry);

        this.eventHandled = Counter.builder("socketio.event.handled")
                .tag("namespace", ns)
                .register(registry);

        this.eventFailed = Counter.builder("socketio.event.failed")
                .tag("namespace", ns)
                .register(registry);

        this.eventSent = Counter.builder("socketio.event.sent")
                .tag("namespace", ns)
                .register(registry);

        this.eventUnknown = Counter.builder("socketio.event.unknown.total")
                .tag("namespace", ns)
                .register(registry);

        /* ---------- Unknown Event Cardinality ---------- */

        this.unknownEventHll = new HLL(14, 5);
        Gauge.builder(
                "socketio.event.unknown.distinct.estimate",
                unknownEventHll,
                HLL::cardinality
        ).tag("namespace", ns).register(registry);

        /* ---------- ACK ---------- */

        this.ackSent = Counter.builder("socketio.ack.sent")
                .tag("namespace", ns)
                .register(registry);

        this.ackMissing = Counter.builder("socketio.ack.missing")
                .tag("namespace", ns)
                .register(registry);

        /* ---------- Connections ---------- */

        this.connect = Counter.builder("socketio.connect.total")
                .tag("namespace", ns)
                .register(registry);

        this.disconnect = Counter.builder("socketio.disconnect.total")
                .tag("namespace", ns)
                .register(registry);

        this.connected = new AtomicInteger(0);
        Gauge.builder(
                "socketio.clients.connected",
                connected,
                AtomicInteger::get
        ).tag("namespace", ns).register(registry);

        /* ---------- Rooms ---------- */

        this.roomJoin = Counter.builder("socketio.room.join.total")
                .tag("namespace", ns)
                .register(registry);

        this.roomLeave = Counter.builder("socketio.room.leave.total")
                .tag("namespace", ns)
                .register(registry);

        this.roomMembers = new AtomicInteger(0);
        Gauge.builder(
                "socketio.room.members",
                roomMembers,
                AtomicInteger::get
        ).tag("namespace", ns).register(registry);

        /* ---------- Timers ---------- */

        Timer.Builder eventTimer = Timer.builder("socketio.event.processing.time")
                .tag("namespace", ns);

        Timer.Builder ackTimer = Timer.builder("socketio.ack.latency")
                .tag("namespace", ns);

        if (histogramEnabled) {
            eventTimer.publishPercentileHistogram();
            ackTimer.publishPercentileHistogram();
        } else {
            eventTimer.publishPercentiles(0.5, 0.95, 0.99);
            ackTimer.publishPercentiles(0.5, 0.95, 0.99);
        }

        this.eventProcessing = eventTimer.register(registry);
        this.ackLatency = ackTimer.register(registry);
    }
}
