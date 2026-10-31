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
package com.socketio4j.socketio.micronaut.annotation;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.annotation.OnConnect;
import com.socketio4j.socketio.annotation.OnEvent;
import com.socketio4j.socketio.micronaut.config.NettySocketIOBasicConfigurationProperties;

import io.netty.util.ResourceLeakDetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Test for Micronaut annotation scanner hierarchy and properties resilience")
public class MicronautAnnotationScannerHierarchyTest {

    @Test
    @DisplayName("Verify scanner detects annotations declared on interfaces and superclasses")
    public void testHierarchyAnnotationDetection() {
        MicronautAnnotationScanner scanner = new MicronautAnnotationScanner(null, null);

        // Subclass inherits @OnConnect from BaseListener
        assertTrue(scanner.hasSocketIOAnnotations(SubclassListener.class));

        // Implementation inherits @OnEvent from ListenerInterface
        assertTrue(scanner.hasSocketIOAnnotations(InterfaceListenerImpl.class));

        // Direct annotated class
        assertTrue(scanner.hasSocketIOAnnotations(BaseListener.class));

        // Class without annotations
        assertFalse(scanner.hasSocketIOAnnotations(PlainObject.class));

        // Null and Object return false safely
        assertFalse(scanner.hasSocketIOAnnotations(null));
        assertFalse(scanner.hasSocketIOAnnotations(Object.class));
    }

    @Test
    @DisplayName("Verify basic configuration properties transports and leak detection in Micronaut")
    public void testBasicPropertiesBinding() {
        NettySocketIOBasicConfigurationProperties properties = new NettySocketIOBasicConfigurationProperties();
        assertNotNull(properties.getTransports());

        properties.setTransports(Collections.singletonList(Transport.WEBSOCKET));
        assertEquals(Collections.singletonList(Transport.WEBSOCKET), properties.getTransports());

        properties.setLeakDetectionLevel(ResourceLeakDetector.Level.ADVANCED);
        assertEquals(ResourceLeakDetector.Level.ADVANCED, properties.getLeakDetectionLevel());
    }

    public static class BaseListener {
        @OnConnect
        public void onConnect() {
        }
    }

    public static class SubclassListener extends BaseListener {
    }

    public interface ListenerInterface {
        @OnEvent("customEvent")
        void onEvent();
    }

    public static class InterfaceListenerImpl implements ListenerInterface {
        @Override
        public void onEvent() {
        }
    }

    public static class PlainObject {
        public void plainMethod() {
        }
    }
}
