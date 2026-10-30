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
package com.socketio4j.socketio.test.spring.boot.starter.config;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.spring.boot.starter.config.NettySocketIOBasicConfigurationProperties;
import com.socketio4j.socketio.spring.boot.starter.config.NettySocketIOSslConfigProperties;
import com.socketio4j.socketio.test.spring.boot.starter.BaseSpringApplicationTest;

import io.netty.util.ResourceLeakDetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Test for Netty Socket.IO properties binding resilience")
public class NettySocketIOPropertiesBindingTest extends BaseSpringApplicationTest {

    @Autowired
    private NettySocketIOBasicConfigurationProperties basicProperties;

    @Autowired
    private NettySocketIOSslConfigProperties sslProperties;

    @Autowired
    private Configuration nettyConfiguration;

    @DynamicPropertySource
    public static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("netty-socket-io.port", () -> 0);
        registry.add("netty-socket-io.transports", () -> "WEBSOCKET");
        registry.add("netty-socket-io.leak-detection-level", () -> "PARANOID");
        registry.add("netty-socket-io.ssl.protocol", () -> "TLSv1.3");
        registry.add("netty-socket-io.ssl.key-store-path", () -> "classpath:keystore.jks");
        registry.add("netty-socket-io.ssl.key-store-password", () -> "test123456");
    }

    @Test
    @DisplayName("Verify transports property binding does not throw and binds correctly")
    public void testTransportsBinding() {
        assertNotNull(basicProperties.getTransports());
        assertEquals(Collections.singletonList(Transport.WEBSOCKET), basicProperties.getTransports());
        assertEquals(Collections.singletonList(Transport.WEBSOCKET), nettyConfiguration.getTransports());
    }

    @Test
    @DisplayName("Verify leak detection level binds and propagates to Configuration")
    public void testLeakDetectionLevelBinding() {
        assertEquals(ResourceLeakDetector.Level.PARANOID, basicProperties.getLeakDetectionLevel());
        assertEquals(ResourceLeakDetector.Level.PARANOID, nettyConfiguration.getLeakDetectionLevel());
    }

    @Test
    @DisplayName("Verify SSL protocol alias and key store resource bind correctly")
    public void testSslPropertiesBinding() {
        assertEquals("TLSv1.3", sslProperties.getSslProtocol());
        assertEquals("TLSv1.3", sslProperties.getProtocol());
        assertEquals("classpath:keystore.jks", sslProperties.getKeyStorePath());
        assertTrue(sslProperties.hasKeyStore());
        assertTrue(sslProperties.isSSLProtocolExplicitlySet());
    }
}
