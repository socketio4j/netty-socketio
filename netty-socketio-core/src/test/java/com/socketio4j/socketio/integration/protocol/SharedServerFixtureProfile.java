/*
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
package com.socketio4j.socketio.integration.protocol;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.nativeio.TransportType;

/**
 * Immutable server profiles that may be pooled by tests. A new profile is
 * required for every observable server configuration difference.
 */
public enum SharedServerFixtureProfile {

    DEFAULT_NIO {
        @Override
        void configure(Configuration configuration) {
            configuration.setTransportType(TransportType.NIO);
        }
    },

    HEARTBEAT_NIO {
        @Override
        void configure(Configuration configuration) {
            configuration.setTransportType(TransportType.NIO);
            configuration.setPingInterval(2_000);
            configuration.setPingTimeout(6_000);
        }
    },

    /**
     * Tests that intentionally sever a polling transport need a bounded
     * server-side reap interval. This is distinct from the default profile so
     * its timing cannot affect unrelated interoperability cases.
     */
    FAST_DISCONNECT_NIO {
        @Override
        void configure(Configuration configuration) {
            configuration.setTransportType(TransportType.NIO);
            configuration.setPingInterval(1_000);
            configuration.setPingTimeout(2_000);
        }
    };

    abstract void configure(Configuration configuration);
}
