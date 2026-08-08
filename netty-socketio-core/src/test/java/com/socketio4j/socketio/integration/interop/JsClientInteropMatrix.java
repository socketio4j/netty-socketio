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
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.provider.Arguments;

/**
 * Exact official Socket.IO client releases used by every JavaScript interop
 * suite. Keep this in sync with {@code client-loader.js} and {@code interop.html}.
 */
public final class JsClientInteropMatrix {

    public static final List<String> VERSIONS = new ArrayList<>(Arrays.asList(
            "1.7.3", "2.1.1", "2.3.0", "2.4.0", "2.5.0", "3.1.3",
            "4.0.0", "4.7.0", "4.7.2", "4.7.5", "4.8.1", "4.8.3"));

    public static final List<String> TRANSPORTS = new ArrayList<>(Arrays.asList("websocket", "polling"));

    private JsClientInteropMatrix() {
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
