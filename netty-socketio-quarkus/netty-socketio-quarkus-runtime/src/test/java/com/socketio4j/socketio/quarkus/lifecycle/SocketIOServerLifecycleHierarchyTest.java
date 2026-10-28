/**
 * Copyright (c) 2025 The Socketio4j Project
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
package com.socketio4j.socketio.quarkus.lifecycle;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.annotation.OnConnect;
import com.socketio4j.socketio.annotation.OnDisconnect;
import com.socketio4j.socketio.annotation.OnEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketIOServerLifecycleHierarchyTest {

    interface AnnotatedInterface {
        @OnConnect
        void onConnect(SocketIOClient client);
    }

    static class InterfaceImplementingClass implements AnnotatedInterface {
        @Override
        public void onConnect(SocketIOClient client) {
        }
    }

    static class BaseListener {
        @OnDisconnect
        public void onDisconnect(SocketIOClient client) {
        }
    }

    static class DerivedListener extends BaseListener {
        public void normalMethod() {
        }
    }

    static class DirectEventListener {
        @OnEvent("message")
        public void onMessage(SocketIOClient client, String message) {
        }
    }

    static class UnannotatedClass {
        public void doSomething() {
        }
    }

    @Test
    void testHasSocketIOAnnotationsOnDirectClass() {
        SocketIOServerLifecycle lifecycle = new SocketIOServerLifecycle();
        assertTrue(lifecycle.hasSocketIOAnnotations(DirectEventListener.class));
    }

    @Test
    void testHasSocketIOAnnotationsOnSubclass() {
        SocketIOServerLifecycle lifecycle = new SocketIOServerLifecycle();
        assertTrue(lifecycle.hasSocketIOAnnotations(DerivedListener.class));
    }

    @Test
    void testHasSocketIOAnnotationsOnInterfaceImplementation() {
        SocketIOServerLifecycle lifecycle = new SocketIOServerLifecycle();
        assertTrue(lifecycle.hasSocketIOAnnotations(InterfaceImplementingClass.class));
    }

    @Test
    void testHasSocketIOAnnotationsOnUnannotatedClass() {
        SocketIOServerLifecycle lifecycle = new SocketIOServerLifecycle();
        assertFalse(lifecycle.hasSocketIOAnnotations(UnannotatedClass.class));
        assertFalse(lifecycle.hasSocketIOAnnotations(Object.class));
        assertFalse(lifecycle.hasSocketIOAnnotations(null));
    }
}
