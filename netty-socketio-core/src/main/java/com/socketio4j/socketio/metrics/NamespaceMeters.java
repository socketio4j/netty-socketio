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

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.agkn.hll.HLL;

/**
 * Per-namespace metric container.
 *
 * All meters are registered exactly once during construction.
 * Runtime code must only mutate counters, timers, and atomic values.
 *
 * Units are explicitly defined to guarantee Prometheus + OTLP consistency.
 *
 * @author https://github.com/sanjomo
 * @date 05/01/26 3:10 pm
 */
public final class NamespaceMeters {

    /* ===================== Event Counters ===================== */

    private final Counter eventReceived;
    private final Counter eventHandled;
    private final Counter eventFailed;
    private final Counter eventSent;
    private final Counter eventUnknown;

    /**
     * HyperLogLog for distinct unknown event names.
     * (namespace:eventName hashed externally)
     */
    // executor-thread only (never exposed)
    private final HLL unknownEventActive;

    // scraper threads only (immutable snapshot)
    private final AtomicReference<HLL> unknownEventPublished;


    /* ===================== ACK ===================== */

    private final Counter ackSent;
    private final Counter ackMissing;

    /* ===================== Connections ===================== */

    private final Counter connect;
    private final Counter disconnect;
    private final AtomicInteger connected;

    /* ===================== Rooms ===================== */

    private final Counter roomJoin;
    private final Counter roomLeave;
    private final AtomicInteger roomMembers;

    /* ===================== Timers ===================== */

    private final Timer eventProcessing;
    private final Timer ackLatency;

    /* ===================== Accessors ===================== */

    public Counter getEventReceived() {
        return eventReceived;
    }
    public Counter getEventHandled() {
        return eventHandled;
    }
    public Counter getEventFailed() {
        return eventFailed;
    }
    public Counter getEventSent() {
        return eventSent;
    }
    public Counter getEventUnknown() {
        return eventUnknown;
    }

    private static final long PUBLISH_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(5);

    private long lastPublishNanos = System.nanoTime();

    /**
     * MUST be called only from the single-thread executor.
     */
    void recordUnknownEvent(long hash) {
        unknownEventActive.addRaw(hash);

        long now = System.nanoTime();
        if (now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
            unknownEventPublished.set(copy(unknownEventActive));
            lastPublishNanos = now;
        }
    }


    public Counter getAckSent() {
        return ackSent;
    }
    public Counter getAckMissing() {
        return ackMissing;
    }

    public Counter getConnect() {
        return connect;
    }
    public Counter getDisconnect() {
        return disconnect;
    }
    public AtomicInteger getConnected() {
        return connected;
    }

    public Counter getRoomJoin() {
        return roomJoin;
    }
    public Counter getRoomLeave() {
        return roomLeave;
    }
    public AtomicInteger getRoomMembers() {
        return roomMembers;
    }

    public Timer getEventProcessing() {
        return eventProcessing;
    }
    public Timer getAckLatency() {
        return ackLatency;
    }

    private static HLL copy(HLL src) {
        return HLL.fromBytes(src.toBytes());
    }


    /* ===================== Constructor ===================== */

    NamespaceMeters(MeterRegistry registry, String ns, boolean histogramEnabled) {
        Objects.requireNonNull(registry);
        Objects.requireNonNull(ns); //can be empty
        /* ---------- Event Counters ---------- */

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

        this.unknownEventActive = new HLL(14, 5);
        this.unknownEventPublished =
                new AtomicReference<>(copy(unknownEventActive));

        Gauge.builder(
                "socketio.event.unknown.distinct.estimate",
                unknownEventPublished,
                ref -> ref.get().cardinality()
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

        /* ---------- Timers (CRITICAL) ---------- */

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
