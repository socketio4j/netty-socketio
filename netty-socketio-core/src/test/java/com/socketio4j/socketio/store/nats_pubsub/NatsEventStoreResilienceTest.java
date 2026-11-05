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
package com.socketio4j.socketio.store.nats_pubsub;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.socketio4j.socketio.metrics.SocketIOMetrics;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.event.ConnectMessage;
import com.socketio4j.socketio.store.event.DisconnectMessage;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.Subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NatsEventStoreResilienceTest {

    @Test
    @DisplayName("EventMessageCodec safely handles null and empty inputs and roundtrips messages")
    void testEventMessageCodec() {
        // Null message -> empty byte array
        byte[] nullBytes = EventMessageCodec.serialize(null);
        assertThat(nullBytes).isEmpty();

        // Null / empty bytes -> null message
        assertThat(EventMessageCodec.deserialize(null, DispatchMessage.class)).isNull();
        assertThat(EventMessageCodec.deserialize(new byte[0], DispatchMessage.class)).isNull();

        // DispatchMessage roundtrip
        DispatchMessage dispatchMsg = new DispatchMessage("roomA", new Packet(PacketType.MESSAGE), "/test");
        dispatchMsg.setNodeId(42L);
        byte[] serialized = EventMessageCodec.serialize(dispatchMsg);
        assertThat(serialized).isNotEmpty();

        DispatchMessage deserialized = EventMessageCodec.deserialize(serialized, DispatchMessage.class);
        assertThat(deserialized).isNotNull();
        assertThat(deserialized.getRoom()).isEqualTo("roomA");
        assertThat(deserialized.getNamespace()).isEqualTo("/test");
        assertThat(deserialized.getNodeId()).isEqualTo(42L);

        // ConnectMessage roundtrip
        UUID sid = UUID.randomUUID();
        ConnectMessage connMsg = new ConnectMessage(sid);
        byte[] connBytes = EventMessageCodec.serialize(connMsg);
        ConnectMessage deserializedConn = EventMessageCodec.deserialize(connBytes, ConnectMessage.class);
        assertThat(deserializedConn).isNotNull();
        assertThat(deserializedConn.getSessionId()).isEqualTo(sid);

        // DisconnectMessage roundtrip
        DisconnectMessage disMsg = new DisconnectMessage(sid);
        byte[] disBytes = EventMessageCodec.serialize(disMsg);
        DisconnectMessage deserializedDis = EventMessageCodec.deserialize(disBytes, DisconnectMessage.class);
        assertThat(deserializedDis).isNotNull();
        assertThat(deserializedDis.getSessionId()).isEqualTo(sid);
    }

    @Test
    @DisplayName("NatsEventStore publish0 handles nulls, subject prefixes, and records failure metrics")
    void testNatsPublishOperations() {
        Connection nats = mock(Connection.class);
        SocketIOMetrics metrics = mock(SocketIOMetrics.class);

        // Store with prefix
        NatsEventStore storeWithPrefix = new NatsEventStore.Builder(nats)
                .nodeId(10L)
                .subjectPrefix("clusterA.")
                .metrics(metrics)
                .build();

        // Null message does not publish
        assertThatCode(() -> storeWithPrefix.publish0(EventType.DISPATCH, null)).doesNotThrowAnyException();
        verify(nats, never()).publish(anyString(), any(byte[].class));

        // Publish with prefix
        DispatchMessage msg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        storeWithPrefix.publish0(EventType.DISPATCH, msg);
        verify(nats).publish(eq("clusterA.DISPATCH"), any(byte[].class));

        // Publish failure records metric
        doThrow(new RuntimeException("NATS disconnected")).when(nats).publish(anyString(), any(byte[].class));
        storeWithPrefix.publish0(EventType.DISPATCH, msg);
        verify(metrics).eventPublishFailed("/chat");
    }

    @Test
    @DisplayName("NatsEventStore subscribe0 forwards remote, skips self and null, and isolates listener errors")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testNatsSubscriptionHandling() throws Exception {
        Connection nats = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        Subscription sub = mock(Subscription.class);

        when(nats.createDispatcher()).thenReturn(dispatcher);
        when(dispatcher.subscribe(anyString(), any(MessageHandler.class))).thenReturn(sub);

        NatsEventStore store = new NatsEventStore.Builder(nats)
                .nodeId(100L)
                .build();

        EventListener<DispatchMessage> listener = mock(EventListener.class);
        store.subscribe0(EventType.DISPATCH, listener, DispatchMessage.class);

        ArgumentCaptor<MessageHandler> captor = ArgumentCaptor.forClass(MessageHandler.class);
        verify(dispatcher).subscribe(eq("DISPATCH"), captor.capture());
        MessageHandler handler = captor.getValue();

        // 1. Remote message forwarded
        DispatchMessage remoteMsg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        remoteMsg.setNodeId(200L);
        Message remoteNatsMsg = mock(Message.class);
        when(remoteNatsMsg.getData()).thenReturn(EventMessageCodec.serialize(remoteMsg));

        assertThatCode(() -> handler.onMessage(remoteNatsMsg)).doesNotThrowAnyException();
        verify(listener).onMessage(any(DispatchMessage.class));

        // 2. Self message skipped
        DispatchMessage localMsg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        localMsg.setNodeId(100L);
        Message localNatsMsg = mock(Message.class);
        when(localNatsMsg.getData()).thenReturn(EventMessageCodec.serialize(localMsg));

        handler.onMessage(localNatsMsg);
        verify(listener, never()).onMessage(localMsg);

        // 3. Null / empty message safely skipped
        Message emptyNatsMsg = mock(Message.class);
        when(emptyNatsMsg.getData()).thenReturn(new byte[0]);
        assertThatCode(() -> handler.onMessage(emptyNatsMsg)).doesNotThrowAnyException();
        assertThatCode(() -> handler.onMessage(null)).doesNotThrowAnyException();

        // 4. Exception in listener is swallowed
        doThrow(new RuntimeException("Listener broke")).when(listener).onMessage(any());
        assertThatCode(() -> handler.onMessage(remoteNatsMsg)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NatsEventStore shutdown0 cleans up all dispatchers and subscriptions")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testNatsShutdown() throws Exception {
        Connection nats = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        Subscription sub = mock(Subscription.class);

        when(nats.createDispatcher()).thenReturn(dispatcher);
        when(dispatcher.subscribe(anyString(), any(MessageHandler.class))).thenReturn(sub);

        NatsEventStore store = new NatsEventStore.Builder(nats)
                .nodeId(100L)
                .build();

        EventListener<DispatchMessage> listener = mock(EventListener.class);
        store.subscribe0(EventType.DISPATCH, listener, DispatchMessage.class);

        store.shutdown0();
        verify(nats).closeDispatcher(dispatcher);

        Field activeDispatchersField = NatsEventStore.class.getDeclaredField("activeDispatchers");
        activeDispatchersField.setAccessible(true);
        ConcurrentMap activeDispatchers = (ConcurrentMap) activeDispatchersField.get(store);
        assertThat(activeDispatchers).isEmpty();
    }
}
