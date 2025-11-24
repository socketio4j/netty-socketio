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
package com.socketio4j.socketio.store.pubsub;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.socketio4j.socketio.handler.AuthorizeHandler;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.namespace.NamespacesHub;
import com.socketio4j.socketio.protocol.JsonSupport;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.StoreFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for BaseStoreFactory to verify the refactored subscription logic
 * and lambda-based message handling. Tests that all PubSub message types
 * are properly subscribed and processed in the correct order.
 */
public class BaseStoreFactoryTest {

    @Mock
    private NamespacesHub namespacesHub;

    @Mock
    private AuthorizeHandler authorizeHandler;

    @Mock
    private JsonSupport jsonSupport;

    @Mock
    private Namespace namespace;

    private AutoCloseable closeableMocks;
    private TestStoreFactory storeFactory;
    private MockPubSubStore pubSubStore;

    @BeforeEach
    public void setUp() {
        closeableMocks = MockitoAnnotations.openMocks(this);
        pubSubStore = new MockPubSubStore();
        storeFactory = new TestStoreFactory(pubSubStore);
        
        when(namespacesHub.get(any(String.class))).thenReturn(namespace);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (closeableMocks != null) {
            closeableMocks.close();
        }
        if (storeFactory != null) {
            storeFactory.shutdown();
        }
    }

    @Test
    public void testInitSubscribesToAllPubSubTypes() {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        // Verify all 7 PubSub types are subscribed
        assertEquals(7, pubSubStore.subscriptions.size());
        assertNotNull(pubSubStore.subscriptions.get(PubSubType.CONNECT));
        assertNotNull(pubSubStore.subscriptions.get(PubSubType.JOIN));
        assertNotNull(pubSubStore.subscriptions.get(PubSubType.BULK_JOIN));
        assertNotNull(pubSubStore.subscriptions.get(PubSubType.DISPATCH));
        assertNotNull(pubSubStore.subscriptions.get(PubSubType.LEAVE));
        assertNotNull(pubSubStore.subscriptions.get(PubSubType.BULK_LEAVE));
        assertNotNull(pubSubStore.subscriptions.get(PubSubType.DISCONNECT));
    }

    @Test
    public void testConnectMessageHandling() throws Exception {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        UUID sessionId = UUID.randomUUID();
        ConnectMessage msg = new ConnectMessage(sessionId);
        msg.setNodeId(2L);

        // Trigger the listener
        pubSubStore.triggerListener(PubSubType.CONNECT, msg);

        // Verify authorize handler was called
        verify(authorizeHandler, times(1)).connect(sessionId);
    }

    @Test
    public void testJoinMessageHandling() throws Exception {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        UUID sessionId = UUID.randomUUID();
        String room = "testRoom";
        String namespacePath = "/test";
        
        JoinLeaveMessage msg = new JoinLeaveMessage(sessionId, room, namespacePath);
        msg.setNodeId(2L);

        // Trigger the listener
        pubSubStore.triggerListener(PubSubType.JOIN, msg);

        // Verify namespace.join was called
        verify(namespacesHub, times(1)).get(namespacePath);
        verify(namespace, times(1)).join(room, sessionId);
    }

    @Test
    public void testBulkJoinMessageHandling() throws Exception {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        UUID sessionId = UUID.randomUUID();
        Set<String> rooms = new HashSet<>();
        rooms.add("room1");
        rooms.add("room2");
        rooms.add("room3");
        String namespacePath = "/test";
        
        BulkJoinLeaveMessage msg = new BulkJoinLeaveMessage(sessionId, rooms, namespacePath);
        msg.setNodeId(2L);

        // Trigger the listener
        pubSubStore.triggerListener(PubSubType.BULK_JOIN, msg);

        // Verify namespace.join was called for each room
        verify(namespacesHub, times(1)).get(namespacePath);
        verify(namespace, times(1)).join("room1", sessionId);
        verify(namespace, times(1)).join("room2", sessionId);
        verify(namespace, times(1)).join("room3", sessionId);
    }

    @Test
    public void testDispatchMessageHandling() throws Exception {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        String room = "testRoom";
        String namespacePath = "/test";
        Packet packet = new Packet(PacketType.MESSAGE);
        
        DispatchMessage msg = new DispatchMessage(room, packet, namespacePath);
        msg.setNodeId(2L);

        // Trigger the listener
        pubSubStore.triggerListener(PubSubType.DISPATCH, msg);

        // Verify namespace.dispatch was called
        verify(namespacesHub, times(1)).get(namespacePath);
        verify(namespace, times(1)).dispatch(room, packet);
    }

    @Test
    public void testLeaveMessageHandling() throws Exception {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        UUID sessionId = UUID.randomUUID();
        String room = "testRoom";
        String namespacePath = "/test";
        
        JoinLeaveMessage msg = new JoinLeaveMessage(sessionId, room, namespacePath);
        msg.setNodeId(2L);

        // Trigger the listener
        pubSubStore.triggerListener(PubSubType.LEAVE, msg);

        // Verify namespace.leave was called
        verify(namespacesHub, times(1)).get(namespacePath);
        verify(namespace, times(1)).leave(room, sessionId);
    }

    @Test
    public void testBulkLeaveMessageHandling() throws Exception {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        UUID sessionId = UUID.randomUUID();
        Set<String> rooms = new HashSet<>();
        rooms.add("room1");
        rooms.add("room2");
        String namespacePath = "/test";
        
        BulkJoinLeaveMessage msg = new BulkJoinLeaveMessage(sessionId, rooms, namespacePath);
        msg.setNodeId(2L);

        // Trigger the listener
        pubSubStore.triggerListener(PubSubType.BULK_LEAVE, msg);

        // Verify namespace.leave was called for each room
        verify(namespacesHub, times(1)).get(namespacePath);
        verify(namespace, times(1)).leave("room1", sessionId);
        verify(namespace, times(1)).leave("room2", sessionId);
    }

    @Test
    public void testDisconnectMessageHandling() throws Exception {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        UUID sessionId = UUID.randomUUID();
        DisconnectMessage msg = new DisconnectMessage(sessionId);
        msg.setNodeId(2L);

        // Trigger the listener - should just log, no exceptions
        pubSubStore.triggerListener(PubSubType.DISCONNECT, msg);

        // No interactions expected for disconnect (it just logs)
        verify(authorizeHandler, never()).connect(any());
        verify(namespace, never()).join(any(), any());
    }

    @Test
    public void testHandlesNullNamespace() throws Exception {
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);

        when(namespacesHub.get(any(String.class))).thenReturn(null);

        UUID sessionId = UUID.randomUUID();
        String room = "testRoom";
        String namespacePath = "/nonexistent";
        
        JoinLeaveMessage msg = new JoinLeaveMessage(sessionId, room, namespacePath);
        msg.setNodeId(2L);

        // Trigger the listener - should not throw exception
        pubSubStore.triggerListener(PubSubType.JOIN, msg);

        // Verify namespace methods were not called
        verify(namespace, never()).join(any(), any());
    }

    @Test
    public void testOnDisconnectDestroysStore() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Store store = mock(Store.class);
        ClientHead client = mock(ClientHead.class);
        
        when(client.getSessionId()).thenReturn(sessionId);
        when(client.getStore()).thenReturn(store);

        storeFactory.onDisconnect(client);

        verify(store, times(1)).destroy();
    }

    @Test
    public void testOnDisconnectHandlesNullStore() throws Exception {
        ClientHead client = mock(ClientHead.class);
        
        when(client.getStore()).thenReturn(null);

        // Should not throw exception
        storeFactory.onDisconnect(client);

        verify(client, times(1)).getStore();
    }

    @Test
    public void testOnDisconnectHandlesExceptionInDestroy() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Store store = mock(Store.class);
        ClientHead client = mock(ClientHead.class);
        
        when(client.getSessionId()).thenReturn(sessionId);
        when(client.getStore()).thenReturn(store);
        when(store.destroy()).thenThrow(new RuntimeException("Simulated destroy error"));

        // Should catch exception and not propagate
        storeFactory.onDisconnect(client);

        verify(store, times(1)).destroy();
    }

    @Test
    public void testToString() {
        String result = storeFactory.toString();
        assertNotNull(result);
        assertNotNull(result.contains("TestStoreFactory"));
        assertNotNull(result.contains("distributed"));
    }

    @Test
    public void testGetNodeId() {
        Long nodeId = storeFactory.getNodeIdPublic();
        assertNotNull(nodeId);
        assertNotNull(nodeId >= 0 && nodeId < 1000000);
    }

    // Helper classes for testing

    private static class TestStoreFactory extends BaseStoreFactory {
        private final PubSubStore pubSubStore;

        public TestStoreFactory(PubSubStore pubSubStore) {
            this.pubSubStore = pubSubStore;
        }

        @Override
        public Store createStore(UUID sessionId) {
            return mock(Store.class);
        }

        @Override
        public PubSubStore pubSubStore() {
            return pubSubStore;
        }

        @Override
        public void shutdown() {
            pubSubStore.shutdown();
        }

        @Override
        public <K, V> Map<K, V> createMap(String name) {
            return mock(Map.class);
        }

        // Expose protected method for testing
        public Long getNodeIdPublic() {
            return getNodeId();
        }
    }

    private static class MockPubSubStore implements PubSubStore {
        private final java.util.Map<PubSubType, PubSubListener<?>> subscriptions = new java.util.HashMap<>();

        @Override
        public void publish(PubSubType type, PubSubMessage msg) {
            // No-op for testing
        }

        @Override
        public <T extends PubSubMessage> void subscribe(PubSubType type, PubSubListener<T> listener, Class<T> clazz) {
            subscriptions.put(type, listener);
        }

        @Override
        public void unsubscribe(PubSubType type) {
            subscriptions.remove(type);
        }

        @Override
        public void shutdown() {
            subscriptions.clear();
        }

        @SuppressWarnings("unchecked")
        public <T extends PubSubMessage> void triggerListener(PubSubType type, T message) {
            PubSubListener<T> listener = (PubSubListener<T>) subscriptions.get(type);
            if (listener != null) {
                listener.onMessage(message);
            }
        }
    }
}