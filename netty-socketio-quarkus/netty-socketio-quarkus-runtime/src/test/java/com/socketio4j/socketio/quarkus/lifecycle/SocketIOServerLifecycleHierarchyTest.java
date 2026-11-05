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
package com.socketio4j.socketio.quarkus.lifecycle;

import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.annotation.OnConnect;
import com.socketio4j.socketio.annotation.OnDisconnect;
import com.socketio4j.socketio.annotation.OnEvent;
import com.socketio4j.socketio.namespace.Namespace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    interface ParentInterface {
        @OnConnect
        void connected(SocketIOClient client);
    }

    interface LeftInterface extends ParentInterface { }
    interface RightInterface extends ParentInterface { }

    static class InterfaceBase implements LeftInterface {
        int connects;
        int disconnects;

        @Override
        public void connected(SocketIOClient client) {
            connects++;
        }

        @OnDisconnect
        public void disconnected(SocketIOClient client) {
            disconnects++;
        }
    }

    static class DiamondListener extends InterfaceBase implements RightInterface { }

    @Test
    void registersSuperclassAndSharedInterfaceMethodsOnce() {
        SocketIOServerLifecycle lifecycle = new SocketIOServerLifecycle();
        lifecycle.socketIOServer = new SocketIOServer(new Configuration());
        DiamondListener listener = new DiamondListener();
        lifecycle.registerListeners(listener, DiamondListener.class);

        Namespace namespace = (Namespace) lifecycle.socketIOServer.getNamespace(Namespace.DEFAULT_NAME);
        SocketIOClient client = mock(SocketIOClient.class);
        when(client.getSessionId()).thenReturn(UUID.randomUUID());
        when(client.getAllRooms()).thenReturn(Collections.emptySet());
        namespace.onConnect(client);
        namespace.onDisconnect(client);
        assertEquals(1, listener.connects);
        assertEquals(1, listener.disconnects);
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
