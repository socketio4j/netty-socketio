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

import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author https://github.com/sanjomo
 * @date 06/01/26 2:24 am
 */
public final class MicrometerMetricsFactory {

    private static final Logger log = LoggerFactory.getLogger(MicrometerMetricsFactory.class);

    private MicrometerMetricsFactory() {
    }

    /* ===================== OTLP (DEFAULT) ===================== */

    public static MicrometerSocketIOMetrics otlpDefault() {
        return otlpDefault(false);
    }

    public static MicrometerSocketIOMetrics otlpDefault(boolean histogramEnabled) {
        OtlpMeterRegistry registry =
                new OtlpMeterRegistry(OtlpConfig.DEFAULT, Clock.SYSTEM);
        log.info("OTLP endpoint = {}", OtlpConfig.DEFAULT.url());

        return new MicrometerSocketIOMetrics(registry, histogramEnabled);
    }

    /* ===================== Prometheus (OPTIONAL) ===================== */

    public static MicrometerSocketIOMetrics prometheus() {
        return prometheus(false);
    }

    public static MicrometerSocketIOMetrics prometheus(boolean histogramEnabled) {
        return new MicrometerSocketIOMetrics(
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
                histogramEnabled
        );
    }
}