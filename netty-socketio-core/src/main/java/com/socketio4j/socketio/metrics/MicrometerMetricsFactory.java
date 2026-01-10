/**
 * Copyright (c) 2025 The Socketio4j Project
 * Parent project : Copyright (c) 2012-2025 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.socketio4j.socketio.metrics;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.datadog.DatadogConfig;
import io.micrometer.datadog.DatadogMeterRegistry;
import io.micrometer.influx.InfluxConfig;
import io.micrometer.influx.InfluxMeterRegistry;
import io.micrometer.newrelic.NewRelicConfig;
import io.micrometer.newrelic.NewRelicMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;

/**
 * Factory for creating {@link MicrometerSocketIOMetrics} instances from a
 * user-provided {@link MeterRegistry}.
 *
 * <h2>Design Philosophy</h2>
 * <p>
 * This factory intentionally <strong>does not create</strong> any
 * {@link MeterRegistry} or exporter implementations (OTLP, Prometheus,
 * Datadog, InfluxDB, etc.).
 * </p>
 *
 * <p>
 * The responsibility boundaries are strictly enforced:
 * </p>
 *
 * <ul>
 *   <li><strong>socketio4j (library)</strong>:
 *       <ul>
 *         <li>Defines and records metrics</li>
 *         <li>Depends only on Micrometer Core APIs</li>
 *         <li>Does <em>not</em> expose HTTP endpoints</li>
 *         <li>Does <em>not</em> choose exporters or backends</li>
 *       </ul>
 *   </li>
 *   <li><strong>Application / Driver code</strong>:
 *       <ul>
 *         <li>Creates and configures {@link MeterRegistry} instances</li>
 *         <li>Selects OTLP, Prometheus, Datadog, InfluxDB, etc.</li>
 *         <li>Controls exporter lifecycle and configuration</li>
 *       </ul>
 *   </li>
 *   <li><strong>Observability infrastructure</strong>:
 *       <ul>
 *         <li>Receives metrics (e.g. via OTLP)</li>
 *         <li>Optionally exposes scrape endpoints (e.g. Prometheus via
 *             OpenTelemetry Collector)</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Recommended Usage</h2>
 *
 * <pre>{@code
 * // Application / driver code
 * MeterRegistry registry =
 *     new OtlpMeterRegistry(OtlpConfig.DEFAULT, Clock.SYSTEM);
 *
 * MicrometerSocketIOMetrics metrics =
 *     MicrometerMetricsFactory.using(registry, true);
 * }</pre>
 *
 * <p>
 * This design ensures that socketio4j remains backend-agnostic,
 * cloud-native, and compatible with OpenTelemetry-based observability
 * pipelines.
 * </p>
 *
 * @since 4.0.0
 */

public final class MicrometerMetricsFactory {

    private static final Logger log = LoggerFactory.getLogger(MicrometerMetricsFactory.class);

    private MicrometerMetricsFactory() {
        // Prevent instantiation
    }

    public static MicrometerSocketIOMetrics using(MeterRegistry registry, boolean histogramEnabled) {
        return new MicrometerSocketIOMetrics(registry, histogramEnabled);
    }
}