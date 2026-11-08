/*
 * Copyright (c) 2025 The Socketio4j Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.socketio4j.socketio.store.event;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RReliableTopic;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadArgs;
import org.redisson.api.stream.StreamReadParams;
import org.redisson.misc.CompletableFutureWrapper;

import com.socketio4j.socketio.store.redis_reliable.RedisPubSubReliableEventStore;
import com.socketio4j.socketio.store.redis_stream.RedisStreamEventStore;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisPollingRecoveryTest {
    @Test
    void reliableListenerRetriesAndContinuesAfterFailures() {
        RedissonClient client = mock(RedissonClient.class);
        RReliableTopic topic = mock(RReliableTopic.class);
        when(client.getReliableTopic(any(String.class))).thenReturn(topic);
        when(topic.addListener(eq(EventMessage.class), any())).thenReturn("id");
        RedisPubSubReliableEventStore store = new RedisPubSubReliableEventStore.Builder(client)
                .nodeId(1).build();
        AtomicInteger attempts = new AtomicInteger();
        store.subscribe0(EventType.CONNECT, message -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("listener failed");
            }
        }, EventMessage.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageListener<EventMessage>> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(EventMessage.class), captor.capture());
        EventMessage event = mock(EventMessage.class);
        when(event.getNodeId()).thenReturn(2L);
        captor.getValue().onMessage("channel", event);
        assertEquals(2, attempts.get());
        captor.getValue().onMessage("channel", event);
        assertEquals(3, attempts.get());
        store.shutdown();
    }

    @Test
    void staleReadCannotDispatchAdvanceOffsetOrStartAnotherPoll() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, EventMessage> stream = mock(RStream.class);
        when(client.<String, EventMessage>getStream(any(String.class))).thenReturn(stream);
        CompletableFuture<Map<StreamMessageId, Map<String, EventMessage>>> oldRead = new CompletableFuture<>();
        CompletableFuture<Map<StreamMessageId, Map<String, EventMessage>>> newRead = new CompletableFuture<>();
        CompletableFuture<Map<StreamMessageId, Map<String, EventMessage>>> nextRead = new CompletableFuture<>();
        CompletableFuture<Map<StreamMessageId, Map<String, EventMessage>>> finalRead = new CompletableFuture<>();
        when(stream.readAsync(any(StreamReadArgs.class))).thenReturn(
                new CompletableFutureWrapper<>(oldRead), new CompletableFutureWrapper<>(newRead),
                new CompletableFutureWrapper<>(nextRead), new CompletableFutureWrapper<>(finalRead));
        RedisStreamEventStore store = new RedisStreamEventStore.Builder(client)
                .nodeId(1).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build();
        AtomicInteger deliveries = new AtomicInteger();
        try {
            store.subscribe0(EventType.CONNECT, message -> deliveries.incrementAndGet(), EventMessage.class);
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(stream, times(1)).readAsync(any(StreamReadArgs.class)));
            store.unsubscribe0(EventType.CONNECT);
            store.subscribe0(EventType.CONNECT, message -> deliveries.incrementAndGet(), EventMessage.class);
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(stream, times(2)).readAsync(any(StreamReadArgs.class)));
            EventMessage stale = mock(EventMessage.class);
            when(stale.getNodeId()).thenReturn(2L);
            oldRead.complete(Collections.singletonMap(new StreamMessageId(10, 0), Collections.singletonMap("event", stale)));
            assertEquals(0, deliveries.get());
            verify(stream, times(2)).readAsync(any(StreamReadArgs.class));
            newRead.complete(Collections.emptyMap());
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(stream, times(3)).readAsync(any(StreamReadArgs.class)));
            ArgumentCaptor<StreamReadArgs> readArgs = ArgumentCaptor.forClass(StreamReadArgs.class);
            verify(stream, times(3)).readAsync(readArgs.capture());
            assertEquals(StreamMessageId.NEWEST, ((StreamReadParams) readArgs.getAllValues().get(2)).getId1());
            EventMessage current = mock(EventMessage.class);
            when(current.getNodeId()).thenReturn(2L);
            nextRead.complete(Collections.singletonMap(new StreamMessageId(5, 0), Collections.singletonMap("event", current)));
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(stream, times(4)).readAsync(any(StreamReadArgs.class)));
            assertEquals(1, deliveries.get());
            verify(current).setOffset("5-0");
        } finally {
            store.shutdown();
        }
    }
}
