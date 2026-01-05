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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;


public final class MicrometerSocketIOMetrics implements SocketIOMetrics {

    private final MeterRegistry registry;
    private final boolean histogramEnabled;
    private static final XXHash64 XX_HASH =
            XXHashFactory.fastestInstance().hash64();

    private static long hashToLong(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        return XX_HASH.hash(bytes, 0, bytes.length, 0);
    }

    /* ===================== Constructors ===================== */

    /** Default: percentile summary (single-JVM friendly) */
    public MicrometerSocketIOMetrics(MeterRegistry registry) {
        this(registry, false);
    }

    /**
     * @param histogramEnabled true = Prometheus histogram (recommended for clusters)
     */
    public MicrometerSocketIOMetrics(MeterRegistry registry, boolean histogramEnabled) {
        this.registry = registry;
        this.histogramEnabled = histogramEnabled;
    }

    /* ===================== Helpers ===================== */

    private final ConcurrentMap<String, NamespaceMeters> namespaces = new ConcurrentHashMap<>();

    private NamespaceMeters ns(String namespace) {
        return namespaces.computeIfAbsent(
                namespace,
                n -> new NamespaceMeters(registry, n, histogramEnabled)
        );
    }


    /* ===================== Events ===================== */

    @Override
    public void eventReceived(String ns) {
        ns(ns).eventReceived.increment();
    }

    @Override
    public void eventHandled(String ns, long durationNanos) {
        NamespaceMeters m = ns(ns);
        if (durationNanos > 0) {
            m.eventProcessing.record(durationNanos, TimeUnit.NANOSECONDS);
        }
        m.eventHandled.increment();
    }

    @Override
    public void eventFailed(String ns) {
        ns(ns).eventFailed.increment();
    }

    @Override
    public void eventSent(String ns, int recipients) {
        NamespaceMeters m = ns(ns);

        if (recipients > 0) {
            m.eventSent.increment(recipients);
        }
    }

    @Override
    public void unknownEventReceived(String ns) {
        ns(ns).eventUnknown.increment();
    }

    @Override
    public void unknownEventNames(String ns, String eventName) {
        if (eventName == null) {
            return;
        }
        ns(ns).unknownEventHll.addRaw(hashToLong(ns + ":" + eventName));
    }

    /* ===================== ACK ===================== */

    @Override
    public void ackSent(String ns, long latencyNanos) {
        NamespaceMeters m = ns(ns);
        if (latencyNanos > 0) {
            m.ackLatency.record(latencyNanos, TimeUnit.NANOSECONDS);
        }
        m.ackSent.increment();
    }

    @Override
    public void ackMissing(String ns) {
        ns(ns).ackMissing.increment();
    }

    /* ===================== Connections ===================== */

    @Override
    public void connect(String ns) {
        NamespaceMeters m = ns(ns);
        m.connect.increment();
        m.connected.incrementAndGet();
    }

    @Override
    public void disconnect(String ns) {
        NamespaceMeters m = ns(ns);
        m.disconnect.increment();
        m.connected.decrementAndGet();
    }

    /* ===================== Rooms ===================== */

    @Override
    public void roomJoin(String ns) {
        NamespaceMeters m = ns(ns);
        m.roomJoin.increment();
        m.roomMembers.incrementAndGet();
    }

    @Override
    public void roomLeave(String ns) {
        NamespaceMeters m = ns(ns);
        m.roomLeave.increment();
        int v = m.roomMembers.decrementAndGet();
        if (v < 0) {
            m.roomMembers.compareAndSet(v, 0); // underflow protection
        }
    }

    public PrometheusMeterRegistry prometheus() {
        return (PrometheusMeterRegistry) registry;
    }


}
