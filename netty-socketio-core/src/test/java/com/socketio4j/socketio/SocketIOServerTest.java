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
package com.socketio4j.socketio;

import com.socketio4j.socketio.nativeio.TransportType;
import io.netty.util.ResourceLeakDetector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketIOServerTest {

    @Test
    void testConfigurationLeakDetectionLevel() {
        Configuration config = new Configuration();
        assertNull(config.getLeakDetectionLevel());

        config.setLeakDetectionLevel(ResourceLeakDetector.Level.ADVANCED);
        assertEquals(ResourceLeakDetector.Level.ADVANCED, config.getLeakDetectionLevel());

        Configuration copy = new Configuration(config);
        assertEquals(ResourceLeakDetector.Level.ADVANCED, copy.getLeakDetectionLevel());
    }

    @Test
    void testSocketIOServerAppliesLeakDetectionLevel() {
        ResourceLeakDetector.Level prev = ResourceLeakDetector.getLevel();
        try {
            Configuration configuration = new Configuration();
            configuration.setHostname("127.0.0.1");
            configuration.setPort(0);
            configuration.setTransportType(TransportType.NIO);
            configuration.setLeakDetectionLevel(ResourceLeakDetector.Level.ADVANCED);

            SocketIOServer server = new SocketIOServer(configuration);
            try {
                server.start();
                assertEquals(ResourceLeakDetector.Level.ADVANCED, ResourceLeakDetector.getLevel());
            } finally {
                server.stop();
            }
        } finally {
            ResourceLeakDetector.setLevel(prev);
        }
    }

    @Test
    void startPublishesEphemeralPortBeforeReturning() {
        Configuration configuration = new Configuration();
        configuration.setHostname("127.0.0.1");
        configuration.setPort(0);
        configuration.setTransportType(TransportType.NIO);

        SocketIOServer server = new SocketIOServer(configuration);
        try {
            server.start();

            assertTrue(server.isStarted());
            assertTrue(configuration.getPort() > 0,
                    "start() must publish the OS-assigned ephemeral port before it returns");
        } finally {
            server.stop();
        }
    }
}
