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
package com.socketio4j.socketio.integration.interop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.provider.Arguments;

/**
 * Exact official Socket.IO client releases used by every JavaScript interop
 * suite. Keep this in sync with {@code client-loader.js} and {@code interop.html}.
 */
public final class JsClientInteropMatrix {

    /** Maven property used to select a compatibility subset. */
    public static final String VERSIONS_PROPERTY = "socketio.interop.versions";

    /** Complete release matrix, including protocol and regression boundaries. */
    public static final List<String> FULL_VERSIONS = Collections.unmodifiableList(Arrays.asList(
            "1.7.3", "2.1.1", "2.3.0", "2.4.0", "2.5.0", "3.1.3",
            "4.0.0", "4.7.0", "4.7.2", "4.7.5", "4.8.1", "4.8.3"));

    /** One representative from each supported Socket.IO protocol family. */
    public static final List<String> SMOKE_VERSIONS = Collections.unmodifiableList(Arrays.asList(
            "1.7.3", "2.5.0", "3.1.3", "4.8.3"));

    /** Versions selected for this JVM. Defaults to the smoke matrix. */
    public static final List<String> VERSIONS = resolveVersions(System.getProperty(VERSIONS_PROPERTY));

    public static final List<String> TRANSPORTS = Collections.unmodifiableList(
            Arrays.asList("websocket", "polling"));

    private JsClientInteropMatrix() {
    }

    /**
     * Resolves {@value #VERSIONS_PROPERTY}. Accepted values are {@code smoke},
     * {@code full}, or a comma-separated subset of {@link #FULL_VERSIONS}.
     * Omitting the property uses the smoke matrix; release verification passes
     * {@code full} explicitly.
     */
    static List<String> resolveVersions(String configuredVersions) {
        if (configuredVersions == null || configuredVersions.trim().isEmpty()
                || "smoke".equalsIgnoreCase(configuredVersions.trim())) {
            return SMOKE_VERSIONS;
        }

        if ("full".equalsIgnoreCase(configuredVersions.trim())) {
            return FULL_VERSIONS;
        }


        List<String> versions = new ArrayList<String>();
        for (String value : configuredVersions.split(",", -1)) {
            String version = value.trim();
            if (version.isEmpty()) {
                throw new IllegalArgumentException("Empty Socket.IO client version in -D"
                        + VERSIONS_PROPERTY + "=" + configuredVersions);
            }
            if (!FULL_VERSIONS.contains(version)) {
                throw new IllegalArgumentException("Unsupported Socket.IO client version '" + version
                        + "' in -D" + VERSIONS_PROPERTY + ". Supported versions: " + FULL_VERSIONS);
            }
            if (versions.contains(version)) {
                throw new IllegalArgumentException("Duplicate Socket.IO client version '" + version
                        + "' in -D" + VERSIONS_PROPERTY);
            }
            versions.add(version);
        }
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("No Socket.IO client versions configured in -D"
                    + VERSIONS_PROPERTY);
        }
        return Collections.unmodifiableList(versions);
    }

    public static String configuredVersionsCsv() {
        return String.join(",", VERSIONS);
    }

    public static boolean usesEngineIOV3(String version) {
        return Arrays.asList("1.7.3", "2.1.1", "2.3.0", "2.4.0", "2.5.0").contains(version);
    }

    public static Stream<String> clientVersions() {
        return VERSIONS.stream();
    }

    public static Stream<Arguments> clientTransports() {
        return clientVersions().flatMap(version -> TRANSPORTS.stream()
                .map(transport -> Arguments.of(version, transport)));
    }

    public static Stream<Arguments> pollingClientTransports() {
        return clientVersions().map(version -> Arguments.of(version, "polling"));
    }
}
