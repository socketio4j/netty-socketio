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
package com.socketio4j.socketio.store.hazelcast;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.Message;
import com.hazelcast.topic.MessageListener;
import com.socketio4j.socketio.metrics.SocketIOMetrics;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HazelcastStoreResilienceTest {

    @Test
    @DisplayName("HazelcastStore supports prefix and null safety")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testHazelcastStoreOperations() {
        HazelcastInstance hz = mock(HazelcastInstance.class);
        IMap map = mock(IMap.class);
        UUID sessionId = UUID.randomUUID();

        when(hz.getMap("PREFIX:" + sessionId)).thenReturn(map);
        when(hz.getMap(sessionId.toString())).thenReturn(map);

        // Constructor validation
        assertThatThrownBy(() -> new HazelcastStore(null, hz))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HazelcastStore(sessionId, null))
                .isInstanceOf(NullPointerException.class);

        // Prefixed store
        HazelcastStore prefixedStore = new HazelcastStore(sessionId, hz, "PREFIX:");
        verify(hz).getMap("PREFIX:" + sessionId);

        // Standard store
        HazelcastStore standardStore = new HazelcastStore(sessionId, hz);
        verify(hz).getMap(sessionId.toString());

        // Basic CRUD operations
        prefixedStore.set("key1", "val1");
        verify(map).put("key1", "val1");

        // Null value deletes key
        prefixedStore.set("key1", null);
        verify(map).delete("key1");

        // Null key does nothing
        assertThatCode(() -> prefixedStore.set(null, "val")).doesNotThrowAnyException();
        Object nullVal = prefixedStore.get(null);
        assertThat(nullVal).isNull();
        boolean hasNull = prefixedStore.has(null);
        assertThat(hasNull).isFalse();
        assertThatCode(() -> prefixedStore.del(null)).doesNotThrowAnyException();

        // Get and has
        when(map.get("key1")).thenReturn("val1");
        when(map.containsKey("key1")).thenReturn(true);
        assertThat((String) prefixedStore.get("key1")).isEqualTo("val1");
        assertThat(prefixedStore.has("key1")).isTrue();

        // Del and Destroy
        prefixedStore.del("key2");
        verify(map).delete("key2");
        prefixedStore.destroy();
        verify(map).destroy();
    }

    @Test
    @DisplayName("HazelcastStoreFactory creates prefixed and standard stores")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testHazelcastStoreFactory() {
        HazelcastInstance hz = mock(HazelcastInstance.class);
        EventStore eventStore = mock(EventStore.class);
        IMap map = mock(IMap.class);
        UUID sessionId = UUID.randomUUID();

        when(hz.getMap("CUSTOM:" + sessionId)).thenReturn(map);
        when(hz.getMap(sessionId.toString())).thenReturn(map);

        HazelcastStoreFactory factory = new HazelcastStoreFactory(hz, eventStore, "CUSTOM:");
        Store store = factory.createStore(sessionId);
        assertThat(store).isInstanceOf(HazelcastStore.class);
        verify(hz).getMap("CUSTOM:" + sessionId);

        HazelcastStoreFactory defaultFactory = new HazelcastStoreFactory(hz, eventStore);
        Store defaultStore = defaultFactory.createStore(sessionId);
        assertThat(defaultStore).isInstanceOf(HazelcastStore.class);
        verify(hz).getMap(sessionId.toString());

        factory.shutdown();
        verify(eventStore).shutdown();
    }

    @Test
    @DisplayName("HazelcastPubSubEventStore message listener handles remote, self, null, and exceptions")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testHazelcastPubSubMessageListener() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        ITopic topic = mock(ITopic.class);
        when(sub.getTopic(anyString())).thenReturn(topic);
        when(topic.addMessageListener(any())).thenReturn(UUID.randomUUID());

        HazelcastPubSubEventStore store = new HazelcastPubSubEventStore.Builder(pub, sub)
                .nodeId(100L)
                .build();

        EventListener<DispatchMessage> listener = mock(EventListener.class);
        store.subscribe0(EventType.DISPATCH, listener, DispatchMessage.class);

        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addMessageListener(captor.capture());
        MessageListener hzListener = captor.getValue();

        // Remote message forwarded
        DispatchMessage remoteMsg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        remoteMsg.setNodeId(200L);
        Message<DispatchMessage> hzMessage = mock(Message.class);
        when(hzMessage.getMessageObject()).thenReturn(remoteMsg);
        hzListener.onMessage(hzMessage);
        verify(listener).onMessage(remoteMsg);

        // Self message ignored
        DispatchMessage localMsg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        localMsg.setNodeId(100L);
        Message<DispatchMessage> localHzMessage = mock(Message.class);
        when(localHzMessage.getMessageObject()).thenReturn(localMsg);
        hzListener.onMessage(localHzMessage);
        verify(listener, never()).onMessage(localMsg);

        // Null message / null object ignored
        assertThatCode(() -> hzListener.onMessage(null)).doesNotThrowAnyException();
        Message<DispatchMessage> nullObjMessage = mock(Message.class);
        when(nullObjMessage.getMessageObject()).thenReturn(null);
        assertThatCode(() -> hzListener.onMessage(nullObjMessage)).doesNotThrowAnyException();

        // Exception in listener is swallowed
        doThrow(new RuntimeException("handler blew up")).when(listener).onMessage(remoteMsg);
        assertThatCode(() -> hzListener.onMessage(hzMessage)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("HazelcastPubSubEventStore publish0 guards null and records failure metric")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testHazelcastPubSubPublishFailureMetric() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        SocketIOMetrics metrics = mock(SocketIOMetrics.class);
        ITopic topic = mock(ITopic.class);
        when(pub.getTopic(anyString())).thenReturn(topic);

        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("pubsub fail"));
        when(topic.publishAsync(any())).thenReturn(failedFuture);

        HazelcastPubSubEventStore store = new HazelcastPubSubEventStore.Builder(pub, sub)
                .metrics(metrics)
                .build();

        // Null message does nothing
        assertThatCode(() -> store.publish0(EventType.DISPATCH, null)).doesNotThrowAnyException();

        // Failed publish records metric
        DispatchMessage msg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        store.publish0(EventType.DISPATCH, msg);
        verify(metrics).eventPublishFailed(any());
    }
}
