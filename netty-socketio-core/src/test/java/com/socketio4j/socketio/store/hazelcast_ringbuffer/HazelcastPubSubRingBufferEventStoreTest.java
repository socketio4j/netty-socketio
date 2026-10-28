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
package com.socketio4j.socketio.store.hazelcast_ringbuffer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hazelcast.config.Config;
import com.hazelcast.config.ReliableTopicConfig;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.Ringbuffer;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.Message;
import com.hazelcast.topic.ReliableMessageListener;
import com.hazelcast.topic.TopicOverloadPolicy;
import com.socketio4j.socketio.metrics.SocketIOMetrics;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventStoreMode;
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

class HazelcastPubSubRingBufferEventStoreTest {

    @Test
    @DisplayName("Default ring buffer configuration values")
    void testDefaultRingBufferConfiguration() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        when(pub.getConfig()).thenThrow(new UnsupportedOperationException());

        HazelcastPubSubRingBufferEventStore store = new HazelcastPubSubRingBufferEventStore.Builder(pub, sub).build();

        assertThat(store.getRingBufferCapacity()).isEqualTo(RingbufferConfig.DEFAULT_CAPACITY);
        assertThat(store.getRingBufferTimeToLiveSeconds()).isEqualTo(RingbufferConfig.DEFAULT_TTL_SECONDS);
        assertThat(store.getTopicOverloadPolicy()).isEqualTo(TopicOverloadPolicy.BLOCK);
    }

    @Test
    @DisplayName("Custom ring buffer configuration via Builder")
    void testCustomRingBufferConfiguration() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        when(pub.getConfig()).thenThrow(new UnsupportedOperationException());

        HazelcastPubSubRingBufferEventStore store = new HazelcastPubSubRingBufferEventStore.Builder(pub, sub)
                .ringBufferCapacity(50000)
                .ringBufferTimeToLiveSeconds(120)
                .topicOverloadPolicy(TopicOverloadPolicy.DISCARD_OLDEST)
                .build();

        assertThat(store.getRingBufferCapacity()).isEqualTo(50000);
        assertThat(store.getRingBufferTimeToLiveSeconds()).isEqualTo(120);
        assertThat(store.getTopicOverloadPolicy()).isEqualTo(TopicOverloadPolicy.DISCARD_OLDEST);
    }

    @Test
    @DisplayName("Invalid capacity or ttl throws IllegalArgumentException")
    void testValidation() {
        HazelcastInstance hz = mock(HazelcastInstance.class);

        assertThatThrownBy(() -> new HazelcastPubSubRingBufferEventStore.Builder(hz).ringBufferCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HazelcastPubSubRingBufferEventStore.Builder(hz).ringBufferCapacity(-10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HazelcastPubSubRingBufferEventStore.Builder(hz).ringBufferTimeToLiveSeconds(-1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HazelcastPubSubRingBufferEventStore.Builder(hz).topicOverloadPolicy(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Applies sizing to Hazelcast member Config when available")
    void testAppliesToMemberConfig() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        Config config = mock(Config.class);
        RingbufferConfig rbConfig = mock(RingbufferConfig.class);
        ReliableTopicConfig topicConfig = mock(ReliableTopicConfig.class);

        when(pub.getConfig()).thenReturn(config);
        when(config.getRingbufferConfig(anyString())).thenReturn(rbConfig);
        when(config.getReliableTopicConfig(anyString())).thenReturn(topicConfig);

        new HazelcastPubSubRingBufferEventStore.Builder(pub, sub)
                .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                .ringBufferCapacity(25000)
                .ringBufferTimeToLiveSeconds(300)
                .topicOverloadPolicy(TopicOverloadPolicy.DISCARD_NEWEST)
                .build();

        verify(config).getRingbufferConfig("_hz_rb_SOCKETIO4J:ALL_SINGLE_CHANNEL");
        verify(rbConfig).setCapacity(25000);
        verify(rbConfig).setTimeToLiveSeconds(300);
        verify(topicConfig).setTopicOverloadPolicy(TopicOverloadPolicy.DISCARD_NEWEST);
    }

    @Test
    @DisplayName("Client mode gracefully inspects cluster Ringbuffer without failing")
    void testClientModeInspection() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        when(pub.getConfig()).thenThrow(new UnsupportedOperationException("Client does not support getConfig"));

        @SuppressWarnings("rawtypes")
        Ringbuffer ringbuffer = mock(Ringbuffer.class);
        when(ringbuffer.capacity()).thenReturn(10000L);
        when(pub.getRingbuffer(anyString())).thenReturn(ringbuffer);

        HazelcastPubSubRingBufferEventStore store = new HazelcastPubSubRingBufferEventStore.Builder(pub, sub)
                .ringBufferCapacity(10000)
                .build();

        assertThat(store.getRingBufferCapacity()).isEqualTo(10000);
    }

    @Test
    @DisplayName("subscribe0 registers a ReliableMessageListener that is loss-tolerant and non-terminal")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testReliableMessageListenerProperties() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        when(pub.getConfig()).thenThrow(new UnsupportedOperationException());

        ITopic topic = mock(ITopic.class);
        when(sub.getReliableTopic(anyString())).thenReturn(topic);
        when(topic.addMessageListener(any())).thenReturn(UUID.randomUUID());

        HazelcastPubSubRingBufferEventStore store = new HazelcastPubSubRingBufferEventStore.Builder(pub, sub)
                .nodeId(100L)
                .build();

        EventListener<DispatchMessage> listener = mock(EventListener.class);
        store.subscribe0(EventType.DISPATCH, listener, DispatchMessage.class);

        ArgumentCaptor<ReliableMessageListener> captor = ArgumentCaptor.forClass(ReliableMessageListener.class);
        verify(topic).addMessageListener(captor.capture());

        ReliableMessageListener<DispatchMessage> reliableListener = captor.getValue();
        assertThat(reliableListener.isLossTolerant()).isTrue();
        assertThat(reliableListener.isTerminal(new RuntimeException("transient failure"))).isFalse();
        assertThat(reliableListener.retrieveInitialSequence()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("ReliableMessageListener forwards remote events and ignores self events")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testReliableMessageListenerMessageHandling() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        when(pub.getConfig()).thenThrow(new UnsupportedOperationException());

        ITopic topic = mock(ITopic.class);
        when(sub.getReliableTopic(anyString())).thenReturn(topic);
        when(topic.addMessageListener(any())).thenReturn(UUID.randomUUID());

        HazelcastPubSubRingBufferEventStore store = new HazelcastPubSubRingBufferEventStore.Builder(pub, sub)
                .nodeId(100L)
                .build();

        EventListener<DispatchMessage> listener = mock(EventListener.class);
        store.subscribe0(EventType.DISPATCH, listener, DispatchMessage.class);

        ArgumentCaptor<ReliableMessageListener> captor = ArgumentCaptor.forClass(ReliableMessageListener.class);
        verify(topic).addMessageListener(captor.capture());
        ReliableMessageListener<DispatchMessage> reliableListener = captor.getValue();

        // 1. Remote message from nodeId 200 -> forwarded to listener
        DispatchMessage remoteMsg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        remoteMsg.setNodeId(200L);
        Message<DispatchMessage> hzMessage = mock(Message.class);
        when(hzMessage.getMessageObject()).thenReturn(remoteMsg);
        reliableListener.onMessage(hzMessage);
        verify(listener).onMessage(remoteMsg);

        // 2. Local message from nodeId 100 -> ignored
        DispatchMessage localMsg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        localMsg.setNodeId(100L);
        Message<DispatchMessage> localHzMessage = mock(Message.class);
        when(localHzMessage.getMessageObject()).thenReturn(localMsg);
        reliableListener.onMessage(localHzMessage);
        verify(listener, never()).onMessage(localMsg);

        // 3. Null message or null message object -> safely ignored
        assertThatCode(() -> reliableListener.onMessage(null)).doesNotThrowAnyException();
        Message<DispatchMessage> nullObjMessage = mock(Message.class);
        when(nullObjMessage.getMessageObject()).thenReturn(null);
        assertThatCode(() -> reliableListener.onMessage(nullObjMessage)).doesNotThrowAnyException();

        // 4. Exception in listener -> swallowed, not thrown
        doThrow(new RuntimeException("User handler exploded")).when(listener).onMessage(remoteMsg);
        assertThatCode(() -> reliableListener.onMessage(hzMessage)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish0 guards null messages and records metrics on failure")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testPublishNullGuardAndFailureMetrics() {
        HazelcastInstance pub = mock(HazelcastInstance.class);
        HazelcastInstance sub = mock(HazelcastInstance.class);
        when(pub.getConfig()).thenThrow(new UnsupportedOperationException());

        SocketIOMetrics metrics = mock(SocketIOMetrics.class);
        ITopic topic = mock(ITopic.class);
        when(pub.getReliableTopic(anyString())).thenReturn(topic);

        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("publish error"));
        when(topic.publishAsync(any())).thenReturn(failedFuture);

        HazelcastPubSubRingBufferEventStore store = new HazelcastPubSubRingBufferEventStore.Builder(pub, sub)
                .metrics(metrics)
                .build();

        // Null message does nothing
        assertThatCode(() -> store.publish0(EventType.DISPATCH, null)).doesNotThrowAnyException();

        // Failed publication records metric
        DispatchMessage msg = new DispatchMessage("room1", new Packet(PacketType.MESSAGE), "/chat");
        store.publish0(EventType.DISPATCH, msg);
        verify(metrics).eventPublishFailed(any());
    }
}
