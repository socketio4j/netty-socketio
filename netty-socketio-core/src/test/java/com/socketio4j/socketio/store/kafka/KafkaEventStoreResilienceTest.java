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
package com.socketio4j.socketio.store.kafka;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.socketio4j.socketio.metrics.SocketIOMetrics;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.event.BulkJoinMessage;
import com.socketio4j.socketio.store.event.BulkLeaveMessage;
import com.socketio4j.socketio.store.event.ConnectMessage;
import com.socketio4j.socketio.store.event.DisconnectMessage;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.JoinMessage;
import com.socketio4j.socketio.store.event.LeaveMessage;
import com.socketio4j.socketio.store.event.ListenerRegistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class KafkaEventStoreResilienceTest {

    @Test
    @DisplayName("publish0 routes causal record keys by room or sessionId")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testCausalRecordKeyRouting() {
        KafkaProducer<String, EventMessage> producer = mock(KafkaProducer.class);
        SocketIOMetrics metrics = mock(SocketIOMetrics.class);
        Properties props = new Properties();

        KafkaEventStore store = new KafkaEventStore(producer, props, 100L, EventStoreMode.MULTI_CHANNEL, "TEST-", metrics);

        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);

        // 1. DispatchMessage -> key is room
        DispatchMessage dispatchMsg = new DispatchMessage("chatRoom", new Packet(PacketType.MESSAGE), "/chat");
        store.publish0(EventType.DISPATCH, dispatchMsg);
        verify(producer).send(captor.capture(), any(Callback.class));
        assertThat(captor.getValue().key()).isEqualTo("chatRoom");
        assertThat(captor.getValue().topic()).isEqualTo("TEST-DISPATCH");

        // 2. JoinMessage -> key is room
        reset(producer);
        JoinMessage joinMsg = new JoinMessage(UUID.randomUUID(), "gameRoom", "/chat");
        store.publish0(EventType.JOIN, joinMsg);
        verify(producer).send(captor.capture(), any(Callback.class));
        assertThat(captor.getValue().key()).isEqualTo("gameRoom");

        // 3. LeaveMessage -> key is room
        reset(producer);
        LeaveMessage leaveMsg = new LeaveMessage(UUID.randomUUID(), "gameRoom", "/chat");
        store.publish0(EventType.LEAVE, leaveMsg);
        verify(producer).send(captor.capture(), any(Callback.class));
        assertThat(captor.getValue().key()).isEqualTo("gameRoom");

        // 4. BulkJoinMessage -> key is sessionId
        reset(producer);
        UUID bulkSid = UUID.randomUUID();
        BulkJoinMessage bulkJoinMsg = new BulkJoinMessage(bulkSid, Collections.singleton("bulkRoom"), "/chat");
        store.publish0(EventType.BULK_JOIN, bulkJoinMsg);
        verify(producer).send(captor.capture(), any(Callback.class));
        assertThat(captor.getValue().key()).isEqualTo(bulkSid.toString());

        // 5. BulkLeaveMessage -> key is sessionId
        reset(producer);
        BulkLeaveMessage bulkLeaveMsg = new BulkLeaveMessage(bulkSid, Collections.singleton("bulkRoom"), "/chat");
        store.publish0(EventType.BULK_LEAVE, bulkLeaveMsg);
        verify(producer).send(captor.capture(), any(Callback.class));
        assertThat(captor.getValue().key()).isEqualTo(bulkSid.toString());

        // 6. DisconnectMessage -> key is sessionId
        reset(producer);
        UUID sid = UUID.randomUUID();
        DisconnectMessage disMsg = new DisconnectMessage(sid);
        store.publish0(EventType.DISCONNECT, disMsg);
        verify(producer).send(captor.capture(), any(Callback.class));
        assertThat(captor.getValue().key()).isEqualTo(sid.toString());

        // 7. ConnectMessage -> key is sessionId
        reset(producer);
        ConnectMessage connMsg = new ConnectMessage(sid);
        store.publish0(EventType.CONNECT, connMsg);
        verify(producer).send(captor.capture(), any(Callback.class));
        assertThat(captor.getValue().key()).isEqualTo(sid.toString());
    }

    @Test
    @DisplayName("publish0 guards null messages and handles synchronous send failures")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testPublishNullAndFailureHandling() {
        KafkaProducer<String, EventMessage> producer = mock(KafkaProducer.class);
        SocketIOMetrics metrics = mock(SocketIOMetrics.class);
        Properties props = new Properties();

        KafkaEventStore store = new KafkaEventStore(producer, props, 100L, EventStoreMode.MULTI_CHANNEL, "TEST-", metrics);

        // Null message does not throw
        assertThatCode(() -> store.publish0(EventType.DISPATCH, null)).doesNotThrowAnyException();

        // Synchronous send failure does not crash caller, records failure metric
        doThrow(new RuntimeException("Kafka buffer full")).when(producer).send(any(), any());
        DispatchMessage msg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        assertThatCode(() -> store.publish0(EventType.DISPATCH, msg)).doesNotThrowAnyException();
        verify(metrics).eventPublishFailed("/chat");
    }

    @Test
    @DisplayName("publish0 records failure metric on asynchronous callback failure")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testPublishAsyncCallbackFailure() {
        KafkaProducer<String, EventMessage> producer = mock(KafkaProducer.class);
        SocketIOMetrics metrics = mock(SocketIOMetrics.class);
        Properties props = new Properties();

        doAnswer(invocation -> {
            Callback cb = invocation.getArgument(1);
            cb.onCompletion(null, new RuntimeException("Async broker unreachable"));
            return null;
        }).when(producer).send(any(), any(Callback.class));

        KafkaEventStore store = new KafkaEventStore(producer, props, 100L, EventStoreMode.MULTI_CHANNEL, "TEST-", metrics);
        DispatchMessage msg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        store.publish0(EventType.DISPATCH, msg);
        verify(metrics).eventPublishFailed("/chat");
    }

    @Test
    @DisplayName("dispatch isolates listener exceptions so all listeners are called")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testDispatchListenerExceptionIsolation() throws Exception {
        KafkaProducer<String, EventMessage> producer = mock(KafkaProducer.class);
        Properties props = new Properties();

        KafkaEventStore store = new KafkaEventStore(producer, props, 100L, EventStoreMode.MULTI_CHANNEL, "TEST-", null);

        EventListener<DispatchMessage> listener1 = mock(EventListener.class);
        EventListener<DispatchMessage> listener2 = mock(EventListener.class);

        doThrow(new RuntimeException("Listener 1 threw exception")).when(listener1).onMessage(any());

        // Inject listeners into store's listeners map via reflection
        Field listenersField = KafkaEventStore.class.getDeclaredField("listeners");
        listenersField.setAccessible(true);
        ConcurrentMap<EventType, Queue<ListenerRegistration<? extends EventMessage>>> listenersMap =
                (ConcurrentMap) listenersField.get(store);

        Queue<ListenerRegistration<? extends EventMessage>> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        queue.add(new ListenerRegistration<>(listener1, DispatchMessage.class));
        queue.add(new ListenerRegistration<>(listener2, DispatchMessage.class));
        listenersMap.put(EventType.DISPATCH, queue);

        // Invoke private dispatch method
        Method dispatchMethod = KafkaEventStore.class.getDeclaredMethod("dispatch", EventType.class, EventMessage.class);
        dispatchMethod.setAccessible(true);

        DispatchMessage msg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        assertThatCode(() -> dispatchMethod.invoke(store, EventType.DISPATCH, msg)).doesNotThrowAnyException();

        // Verify listener2 was still executed even though listener1 failed
        verify(listener1).onMessage(msg);
        verify(listener2).onMessage(msg);
    }

    @Test
    @DisplayName("subscribe0 mode compatibility validation")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testSubscribeModeValidation() {
        KafkaProducer<String, EventMessage> producer = mock(KafkaProducer.class);
        Properties props = new Properties();

        KafkaEventStore multiChannelStore = new KafkaEventStore(producer, props, 100L, EventStoreMode.MULTI_CHANNEL, "TEST-");
        EventListener<DispatchMessage> listener = mock(EventListener.class);

        assertThatThrownBy(() -> multiChannelStore.subscribe0(EventType.ALL_SINGLE_CHANNEL, listener, DispatchMessage.class))
                .isInstanceOf(UnsupportedOperationException.class);

        KafkaEventStore singleChannelStore = new KafkaEventStore(producer, props, 100L, EventStoreMode.SINGLE_CHANNEL, "TEST-");
        assertThatThrownBy(() -> singleChannelStore.subscribe0(EventType.DISPATCH, listener, DispatchMessage.class))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("shutdown0 safely handles producer close errors without leaking state")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testShutdownSafety() {
        KafkaProducer<String, EventMessage> producer = mock(KafkaProducer.class);
        Properties props = new Properties();

        doThrow(new RuntimeException("Producer close failed")).when(producer).close();

        KafkaEventStore store = new KafkaEventStore(producer, props, 100L, EventStoreMode.MULTI_CHANNEL, "TEST-");
        assertThatCode(store::shutdown0).doesNotThrowAnyException();
    }
}
