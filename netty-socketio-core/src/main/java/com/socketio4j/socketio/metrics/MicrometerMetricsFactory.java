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
 * Factory for creating Micrometer-based metrics implementations.
 * <p>
 * This factory supports both "Zero Config" defaults and "Enterprise Custom" configurations
 * for Datadog, New Relic, InfluxDB, OTLP, and Prometheus.
 */
public final class MicrometerMetricsFactory {

    private static final Logger log = LoggerFactory.getLogger(MicrometerMetricsFactory.class);

    private MicrometerMetricsFactory() {
        // Prevent instantiation
    }

    /* ==================================================================================
     * PROMETHEUS (Pull-Based)
     * ================================================================================== */

    public static MicrometerSocketIOMetrics prometheusDefault(boolean histogramEnabled) {
        return prometheus(PrometheusConfig.DEFAULT, histogramEnabled);
    }

    public static MicrometerSocketIOMetrics prometheus(PrometheusConfig config, boolean histogramEnabled) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(config);
        log.info("Prometheus metrics enabled (Pull-based)");
        return new MicrometerSocketIOMetrics(registry, histogramEnabled);
    }

    /* ==================================================================================
     * OTLP / OPEN TELEMETRY (Push-Based)
     * ================================================================================== */

    public static MicrometerSocketIOMetrics otlpDefault(boolean histogramEnabled) {
        return otlp(OtlpConfig.DEFAULT, histogramEnabled);
    }

    public static MicrometerSocketIOMetrics otlp(OtlpConfig config, boolean histogramEnabled) {
        OtlpMeterRegistry registry = new OtlpMeterRegistry(config, Clock.SYSTEM);
        log.info("OTLP metrics enabled (Push-based)");
        return new MicrometerSocketIOMetrics(registry, histogramEnabled);
    }

    /* ==================================================================================
     * DATADOG (Push-Based)
     * ================================================================================== */

    public static MicrometerSocketIOMetrics datadog(DatadogConfig config, boolean histogramEnabled) {
        DatadogMeterRegistry registry = new DatadogMeterRegistry(config, Clock.SYSTEM);
        log.info("Datadog metrics enabled (Push-based)");
        return new MicrometerSocketIOMetrics(registry, histogramEnabled);
    }

    /* ==================================================================================
     * NEW RELIC (Push-Based)
     * ================================================================================== */

    public static MicrometerSocketIOMetrics newRelic(NewRelicConfig config, boolean histogramEnabled) {
        NewRelicMeterRegistry registry = new NewRelicMeterRegistry(config, Clock.SYSTEM);
        log.info("New Relic metrics enabled (Push-based)");
        return new MicrometerSocketIOMetrics(registry, histogramEnabled);
    }

    /* ==================================================================================
     * INFLUX DB (Push-Based)
     * ================================================================================== */

    public static MicrometerSocketIOMetrics influxDefault(boolean histogramEnabled) {
        return influx(InfluxConfig.DEFAULT, histogramEnabled);
    }

    public static MicrometerSocketIOMetrics influx(InfluxConfig config, boolean histogramEnabled) {
        InfluxMeterRegistry registry = new InfluxMeterRegistry(config, Clock.SYSTEM);
        log.info("InfluxDB metrics enabled (Push-based)");
        return new MicrometerSocketIOMetrics(registry, histogramEnabled);
    }

    /* ==================================================================================
     * COMPOSITE / ADVANCED / TESTING
     * ================================================================================== */

    /**
     * Creates a composite registry. Useful for testing multiple backends simultaneously
     * (e.g., debugging Prometheus locally while pushing to Datadog).
     */
    public static MicrometerSocketIOMetrics composite(boolean histogramEnabled, MeterRegistry... registries) {
        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        List<String> names = new ArrayList<>();

        for (MeterRegistry r : registries) {
            composite.add(r);
            names.add(r.getClass().getSimpleName());
        }

        log.warn("Composite MeterRegistry enabled with: {}", names);
        return new MicrometerSocketIOMetrics(composite, histogramEnabled);
    }
}