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
package com.socketio4j.socketio.namespace;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.socketio4j.socketio.AckMode;
import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.listener.ConnectListener;
import com.socketio4j.socketio.listener.DataListener;
import com.socketio4j.socketio.listener.DefaultExceptionListener;
import com.socketio4j.socketio.listener.DisconnectListener;
import com.socketio4j.socketio.listener.ExceptionListener;
import com.socketio4j.socketio.listener.PingListener;
import com.socketio4j.socketio.listener.PongListener;
import com.socketio4j.socketio.metrics.SocketIOMetrics;
import com.socketio4j.socketio.protocol.JsonSupport;
import com.socketio4j.socketio.store.StoreFactory;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.transport.NamespaceClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NamespaceTest extends AbstractNamespaceTestSupport {

    private Namespace namespace;

    private AutoCloseable closeableMocks;

    @Mock
    private Configuration configuration;

    @Mock
    private JsonSupport jsonSupport;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private SocketIOClient mockClient;

    @Mock
    private NamespaceClient mockNamespaceClient;

    private static final String NAMESPACE_NAME = "/test";
    private static final UUID CLIENT_SESSION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        closeableMocks = MockitoAnnotations.openMocks(this);
        when(configuration.getJsonSupport()).thenReturn(jsonSupport);
        when(configuration.getStoreFactory()).thenReturn(storeFactory);
        when(configuration.getAckMode()).thenReturn(AckMode.AUTO);
        when(configuration.getExceptionListener()).thenReturn(new DefaultExceptionListener());
        when(configuration.isMetricsEnabled()).thenReturn(false);
        namespace = new Namespace(NAMESPACE_NAME, configuration);

        when(mockClient.getSessionId()).thenReturn(CLIENT_SESSION_ID);
        when(mockClient.getAllRooms()).thenReturn(Collections.emptySet());
        when(mockNamespaceClient.getSessionId()).thenReturn(CLIENT_SESSION_ID);

        // Mock StoreFactory eventStore to avoid NullPointerException
        when(storeFactory.eventStore()).thenReturn(mock(EventStore.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        closeableMocks.close();
    }

    /**
     * Test basic namespace properties and initialization
     */
    @Test
    void testBasicProperties() {
        // Test namespace name
        assertEquals(NAMESPACE_NAME, namespace.getName());
        assertNotNull(namespace.getName());
        assertFalse(namespace.getName().isEmpty());

        // Test default namespace name constant
        assertEquals("", Namespace.DEFAULT_NAME);
        assertNotNull(Namespace.DEFAULT_NAME);

        // Test initial state
        assertTrue(namespace.getAllClients().isEmpty());
        assertEquals(0, namespace.getAllClients().size());
        assertTrue(namespace.getRooms().isEmpty());
        assertEquals(0, namespace.getRooms().size());
        assertNull(namespace.getClient(CLIENT_SESSION_ID));

        // Test namespace is not null
        assertNotNull(namespace);

        // Test namespace is properly initialized
        assertNotNull(namespace);
    }

    /**
     * Test client management operations with concurrency safety
     */
    @Test
    void testClientManagement() throws InterruptedException {
        // Test initial state
        assertTrue(namespace.getAllClients().isEmpty());
        assertEquals(0, namespace.getAllClients().size());

        // Test adding client
        namespace.addClient(mockClient);
        assertEquals(1, namespace.getAllClients().size());
        assertTrue(namespace.getAllClients().contains(mockClient));
        assertEquals(mockClient, namespace.getClient(CLIENT_SESSION_ID));
        assertNotNull(namespace.getClient(CLIENT_SESSION_ID));

        // Verify client properties
        assertNotNull(mockClient.getSessionId());
        assertEquals(CLIENT_SESSION_ID, mockClient.getSessionId());

        // Test concurrent client addition
        int taskCount = DEFAULT_TASK_COUNT;
        Set<UUID> addedSessionIds = Collections.synchronizedSet(new HashSet<>());

        CountDownLatch latch =
                executeConcurrentOperationsWithIndex(
                        taskCount,
                        index -> {
                            SocketIOClient client = mock(SocketIOClient.class);
                            UUID sessionId = UUID.randomUUID();
                            when(client.getSessionId()).thenReturn(sessionId);
                            when(client.getAllRooms()).thenReturn(Collections.emptySet());

                            namespace.addClient(client);
                            addedSessionIds.add(sessionId);
                        });

        waitForCompletion(latch);

        // Verify all clients were added safely
        assertEquals(taskCount + 1, namespace.getAllClients().size());
        assertEquals(taskCount, addedSessionIds.size());

        // Verify each added client can be retrieved
        for (UUID sessionId : addedSessionIds) {
            assertNotNull(namespace.getClient(sessionId));
        }

        // Test client removal
        namespace.onDisconnect(mockClient);
        assertEquals(taskCount, namespace.getAllClients().size());
        assertFalse(namespace.getAllClients().contains(mockClient));
        assertNull(namespace.getClient(CLIENT_SESSION_ID));

        // Verify remaining clients are still accessible
        assertFalse(namespace.getAllClients().isEmpty());
        assertEquals(taskCount, namespace.getAllClients().size());

        // Test that operations completed successfully
        assertTrue(latch.getCount() == 0);
    }

    /**
     * Test event listener management with thread safety
     */
    @Test
    void testEventListenerManagement() throws InterruptedException {
        // Test initial state - no listeners
        assertNotNull(namespace);

        // Test adding event listener
        String eventName = "testEvent";
        DataListener<String> listener = (client, data, ackRequest) -> {
        };
        assertNotNull(listener);
        assertNotNull(eventName);
        assertFalse(eventName.isEmpty());

        namespace.addEventListener(eventName, String.class, listener);

        // Verify event mapping was added
        verify(jsonSupport, times(1))
                .addEventMapping(eq(NAMESPACE_NAME), eq(eventName), eq(String.class));

        // Test concurrent listener addition
        int taskCount = 5;
        Set<String> addedEventNames = Collections.synchronizedSet(new HashSet<>());

        CountDownLatch latch =
                executeConcurrentOperationsWithIndex(
                        taskCount,
                        index -> {
                            String concurrentEventName = "concurrentEvent" + index;
                            DataListener<String> concurrentListener = (client, data, ackRequest) -> {
                            };
                            assertNotNull(concurrentListener);

                            namespace.addEventListener(concurrentEventName, String.class, concurrentListener);
                            addedEventNames.add(concurrentEventName);
                        });

        waitForCompletion(latch);

        // Verify all listeners were added safely
        verify(jsonSupport, times(taskCount + 1))
                .addEventMapping(eq(NAMESPACE_NAME), anyString(), eq(String.class));
        assertEquals(taskCount, addedEventNames.size());

        // Verify specific event names were processed
        for (String addedEventName : addedEventNames) {
            assertNotNull(addedEventName);
            assertFalse(addedEventName.isEmpty());
        }

        // Verify that operations completed successfully
        assertTrue(latch.getCount() == 0);

        // Test removing specific listener
        namespace.removeAllListeners(eventName);
        verify(jsonSupport).removeEventMapping(NAMESPACE_NAME, eventName);

        // Verify specific event mapping was removed
        verify(jsonSupport, times(1)).removeEventMapping(eq(NAMESPACE_NAME), eq(eventName));
    }

    @Test
    void testConcurrentRoomJoiningThreadSafety() throws InterruptedException {
        int clientCount = 20;
        String roomName = "concurrentRoom";
        Set<UUID> joinedClientIds = ConcurrentHashMap.newKeySet();

        CountDownLatch latch = executeConcurrentOperationsWithIndex(clientCount, index -> {
            UUID id = UUID.randomUUID();
            joinedClientIds.add(id);
            SocketIOClient client = mock(SocketIOClient.class);
            when(client.getSessionId()).thenReturn(id);
            namespace.addClient(client);
            namespace.joinRoom(roomName, id);
        });

        waitForCompletion(latch);

        assertTrue(namespace.getRooms().contains(roomName), "Room should exist in namespace rooms set");
        Set<UUID> roomClientIds = new HashSet<>();
        for (SocketIOClient client : namespace.getRoomClients(roomName)) {
            roomClientIds.add(client.getSessionId());
        }
        assertEquals(joinedClientIds, roomClientIds,
                "Room should retain every client ID joined concurrently");
    }

    @Test
    void testPongExceptionDispatchesToOnPongException() {
        ExceptionListener mockExceptionListener = mock(ExceptionListener.class);
        when(configuration.getExceptionListener()).thenReturn(mockExceptionListener);
        Namespace ns = new Namespace("/test-pong", configuration);

        RuntimeException simulated = new RuntimeException("pong failed");
        ns.addPongListener(client -> {
            throw simulated;
        });

        ns.onPong(mockClient);

        verify(mockExceptionListener, times(1)).onPongException(eq(simulated), eq(mockClient));
        verify(mockExceptionListener, never()).onPingException(any(), any());
    }

    @Test
    void testListenerIsolationAcrossListeners() {
        ExceptionListener mockExceptionListener = mock(ExceptionListener.class);
        when(configuration.getExceptionListener()).thenReturn(mockExceptionListener);
        Namespace ns = new Namespace("/test-isolation", configuration);

        AtomicInteger connectCount = new AtomicInteger();
        ns.addConnectListener(client -> {
            throw new RuntimeException("connect boom");
        });
        ns.addConnectListener(client -> connectCount.incrementAndGet());
        ns.onConnect(mockClient);
        assertEquals(1, connectCount.get());
        verify(mockExceptionListener, times(1)).onConnectException(any(), eq(mockClient));

        AtomicInteger disconnectCount = new AtomicInteger();
        ns.addDisconnectListener(client -> {
            throw new RuntimeException("disconnect boom");
        });
        ns.addDisconnectListener(client -> disconnectCount.incrementAndGet());
        ns.onDisconnect(mockClient);
        assertEquals(1, disconnectCount.get());
        verify(mockExceptionListener, times(1)).onDisconnectException(any(), eq(mockClient));

        AtomicInteger pingCount = new AtomicInteger();
        ns.addPingListener(client -> {
            throw new RuntimeException("ping boom");
        });
        ns.addPingListener(client -> pingCount.incrementAndGet());
        ns.onPing(mockClient);
        assertEquals(1, pingCount.get());
        verify(mockExceptionListener, times(1)).onPingException(any(), eq(mockClient));

        AtomicInteger pongCount = new AtomicInteger();
        ns.addPongListener(client -> {
            throw new RuntimeException("pong boom");
        });
        ns.addPongListener(client -> pongCount.incrementAndGet());
        ns.onPong(mockClient);
        assertEquals(1, pongCount.get());
        verify(mockExceptionListener, times(1)).onPongException(any(), eq(mockClient));
    }

    @Test
    void testAckMissingOnlyRecordedWhenAckRequested() {
        SocketIOMetrics mockMetrics = mock(SocketIOMetrics.class);
        when(configuration.isMetricsEnabled()).thenReturn(true);
        when(configuration.getMetrics()).thenReturn(mockMetrics);
        when(configuration.getAckMode()).thenReturn(AckMode.MANUAL);

        Namespace ns = new Namespace("/test-ack", configuration);
        AckRequest ackReqWithoutAck = mock(AckRequest.class);
        when(ackReqWithoutAck.isAckRequested()).thenReturn(false);
        when(ackReqWithoutAck.isSent()).thenReturn(false);

        ns.addEventListener("pingEvent", String.class, (client, data, ackRequest) -> {
        });
        ns.onEvent(mockNamespaceClient, "pingEvent", Collections.singletonList("msg"), ackReqWithoutAck);

        // When client did NOT request ack, ackMissing should NOT be called
        verify(mockMetrics, never()).ackMissing(anyString());

        AckRequest ackReqWithAck = mock(AckRequest.class);
        when(ackReqWithAck.isAckRequested()).thenReturn(true);
        when(ackReqWithAck.isSent()).thenReturn(false);

        ns.onEvent(mockNamespaceClient, "pingEvent", Collections.singletonList("msg"), ackReqWithAck);
        // When client DID request ack and it was not sent under MANUAL, ackMissing SHOULD be called
        verify(mockMetrics, times(1)).ackMissing(eq("/test-ack"));
    }

    @Test
    void testLeaveRoomCleansUpEmptyRoomsAtomically() {
        String room = "tempRoom";
        UUID client1 = UUID.randomUUID();
        UUID client2 = UUID.randomUUID();

        namespace.join(room, client1);
        namespace.join(room, client2);
        assertTrue(namespace.getRooms().contains(room));

        namespace.leave(room, client1);
        assertTrue(namespace.getRooms().contains(room));

        namespace.leave(room, client2);
        assertFalse(namespace.getRooms().contains(room));
    }
}
