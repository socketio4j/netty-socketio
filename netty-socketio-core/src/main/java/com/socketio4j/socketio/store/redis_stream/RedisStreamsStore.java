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
package com.socketio4j.socketio.store.redis_stream;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamReadArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.ListenerRegistration;


public class RedisStreamsStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsStore.class);
    private static final String DEFAULT_STREAM_PREFIX = "socketio4j";
    private final ConcurrentMap<EventType, Queue<ListenerRegistration<? extends EventMessage>>> eventListenerMap = new ConcurrentHashMap<>();
    private final EventStoreMode eventStoreMode;
    private final String streamName;
    private final Map<EventType, RStream<String, EventMessage>> streams;
    private final Long nodeId;
    private final RedissonClient redissonClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<EventType, ScheduledExecutorService> executors = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<EventType, StreamMessageId> offsets; // "$"
    private final Duration readTimeout;
    private final int readBatchSize;

    public RedisStreamsStore(@Nullable String streamName,
                             @Nullable Long nodeId,
                             RedissonClient redissonClient,
                             @Nullable StreamMessageId offset,
                             @Nullable Duration readTimeout,
                             int readBatchSize,
                             EventStoreMode eventStoreMode) {

        Objects.requireNonNull(redissonClient, "redissonClient cannot be null");

        this.redissonClient = redissonClient;

        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.SINGLE_CHANNEL;
            log.warn("eventStoreMode is null, loaded default {}", EventStoreMode.SINGLE_CHANNEL);
        }
        if (streamName == null || streamName.isEmpty()) {
            streamName = DEFAULT_STREAM_PREFIX;
            log.warn("streamName is null/empty, loaded default {}", DEFAULT_STREAM_PREFIX);
        }
        if (nodeId == null) {
            nodeId = getNodeId();
            log.warn("nodeId is null, loaded default {}", nodeId);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(10);
            log.warn("readTimeout is null, loaded default {}", readTimeout);
        }
        if (readBatchSize <= 0) {
            readBatchSize = 100;
            log.warn("readBatchSize is null, loaded default {}", readBatchSize);
        }
        if (offset == null) {
            offset = StreamMessageId.NEWEST;
            log.warn("offset is null, loaded default {}", StreamMessageId.NEWEST);
        }

        this.eventStoreMode = eventStoreMode;
        this.streamName = streamName;
        this.nodeId = nodeId;
        this.readTimeout = readTimeout;
        this.readBatchSize = readBatchSize;

        List<EventType> eventTypeList = Collections.emptyList();
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            eventTypeList = Collections.singletonList(EventType.ALL_SINGLE_CHANNEL);
        } else if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)) {
            eventTypeList = Arrays.stream(EventType.values()).filter(t -> t != EventType.ALL_SINGLE_CHANNEL).collect(Collectors.toList());
        }
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            this.streams = Collections.singletonMap(EventType.ALL_SINGLE_CHANNEL, redissonClient.getStream(streamName));
        } else {
            this.streams = new HashMap<>();
            eventTypeList.forEach(t -> this.streams.put(t, this.redissonClient.getStream(getStreamName(t))));
        }

        this.offsets = new ConcurrentHashMap<>();
        for (EventType eventType : eventTypeList) {
            this.offsets.put(eventType, offset);
        }
        init();
    }

    public RedisStreamsStore(RedissonClient redissonClient, EventStoreMode eventStoreMode) {
        this("", null, redissonClient, StreamMessageId.NEWEST, Duration.ofSeconds(10), 100, eventStoreMode);
    }

    private void init() {
        if (running.compareAndSet(false, true)) {
            streams.forEach((eventType, stream) -> {
                executors.computeIfAbsent(eventType, t ->
                        Executors.newSingleThreadScheduledExecutor(r -> {
                            Thread th = new Thread(r);
                            th.setName("redis-stream-" + t.name());
                            return th;
                        })
                );

                executors.get(eventType).submit(() -> pollAsync(stream, eventType));
            });
        }
    }


    @Override
    public EventStoreMode getMode() {
        return eventStoreMode;
    }

    @Override
    public EventStoreType getStoreType() {
        return EventStoreType.STREAM;
    }
    // =========================================================================
    // PUBLISH
    // =========================================================================

    @Override
    public void publish0(EventType type, EventMessage msg) {

        msg.setNodeId(nodeId);
        if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)
                 && type == EventType.ALL_SINGLE_CHANNEL) {
                       throw new IllegalArgumentException(
                               "ALL_SINGLE_CHANNEL cannot be published in MULTI_CHANNEL mode");
        }
        // Single-channel mode subscribes to ALL types
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            streams.get(EventType.ALL_SINGLE_CHANNEL).add(StreamAddArgs.entry(type.toString(), msg));
        } else {
            streams.get(type).add(StreamAddArgs.entry(type.toString(), msg));
        }
    }

    private String getStreamName(EventType type) {
        return streamName + ":" + type.name();
    }

    // =========================================================================
    // SUBSCRIBE
    // =========================================================================

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, EventListener<T> listener, Class<T> clazz) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(clazz, "clazz");
        // Single-channel mode subscribes to ALL types
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode) && type != EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException("can only subscribe with ALL_SINGLE_CHANNEL when you are on "+EventStoreMode.SINGLE_CHANNEL+ " mode");
        } else if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode) && type == EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException("can not subscribe with ALL_SINGLE_CHANNEL when you are on "+EventStoreMode.MULTI_CHANNEL+ " mode");
        }
        eventListenerMap.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>()).add(new ListenerRegistration<>(listener, clazz));
        ensurePollerRunning(type);
    }

    private void ensurePollerRunning(EventType type) {
        executors.compute(type, (k, existingExecutor) -> {
            if (existingExecutor == null || existingExecutor.isShutdown() || existingExecutor.isTerminated()) {
                ScheduledExecutorService newExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread th = new Thread(r);
                    th.setName("redis-stream-" + type.name());
                    return th;
                });

                RStream<String, EventMessage> stream = streams.getOrDefault(type, redissonClient.getStream(getStreamName(type)));
                newExecutor.execute(() -> pollAsync(stream, type));
                return newExecutor;
            }
            return existingExecutor;
        });
        offsets.computeIfAbsent(type, t -> offsets.getOrDefault(type, StreamMessageId.NEWEST));
    }


    private void pollAsync(final RStream<String, EventMessage> stream, EventType type) {

        if (!running.get() || Thread.currentThread().isInterrupted()) {
            log.debug("Polling stopped {}", type);
            return;
        }

        stream.readAsync(StreamReadArgs.greaterThan(offsets.get(type)).count(readBatchSize).timeout(readTimeout)).whenComplete((messages, error) -> {

            if (error != null) {
                log.error("Streams async read failure", error);
                scheduleNextPoll(stream, type);
                return;
            }

            if (messages != null && !messages.isEmpty()) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    log.debug("Polling stopped {}", type);
                    return;
                }
                for (Map.Entry<StreamMessageId, Map<String, EventMessage>> entry : messages.entrySet()) {

                    StreamMessageId id = entry.getKey();
                    Map<String, EventMessage> msgMap = entry.getValue();
                    if (msgMap.isEmpty()) {
                        log.warn("Empty message map for StreamMessageId: {}", id);
                        this.offsets.compute(type, (k, old) -> id);
                        continue;
                    }
                    EventMessage msg = msgMap.entrySet().iterator().next().getValue();
                    try {
                        if (nodeId.equals(msg.getNodeId())) {
                            this.offsets.compute(type, (k, old) -> id);
                            continue;
                        }

                        Queue<ListenerRegistration<? extends EventMessage>> eventListeners = eventListenerMap.get(type);
                        if (eventListeners == null) {
                            continue;
                        }
                        for (ListenerRegistration<? extends EventMessage> listenerEntry : eventListeners) {
                            try {
                                dispatchTyped(listenerEntry, msg, id);
                            } catch (Exception ex) {
                                log.error("Error processing stream message {} {} {}", msg, id, listenerEntry, ex);
                            }
                        }
                    } finally {
                        // advance offset (as we use > )
                        this.offsets.compute(type, (k, old) -> id);
                    }
                }
            }

            ScheduledExecutorService exec = executors.get(type);
            if (exec != null && running.get() && !Thread.currentThread().isInterrupted()) {
                exec.submit(() -> pollAsync(stream, type));
            }
        });
    }


    private <T extends EventMessage> void dispatchTyped(
            ListenerRegistration<T> listenerRegistration,
            EventMessage msg,
            StreamMessageId id) {
        if (!listenerRegistration.getClazz().isInstance(msg)) {
            return; // skip incompatible listener
        }
        msg.setOffset(id.toString());
        listenerRegistration.getListener().onMessage((T) msg); //Safe because of instance check
    }



    private void scheduleNextPoll(RStream<String, EventMessage> stream, EventType type) {
        ScheduledExecutorService exec = executors.get(type);
        if (exec != null && running.get()) {
            exec.schedule(() -> pollAsync(stream, type), 1, TimeUnit.SECONDS);
        }
    }


    @Override
    public void unsubscribe0(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            if (type != EventType.ALL_SINGLE_CHANNEL) {
                throw new UnsupportedOperationException("Single-channel mode only supports EventType.ALL_SINGLE_CHANNEL - no individual un-subscribes");
            }
            eventListenerMap.clear();
            log.debug("Unsubscribing from Redis Streams");
            executors.values().forEach(this::shutdownExecutorGracefully);
            executors.clear();
        } else {
            eventListenerMap.remove(type);
            ScheduledExecutorService exec = executors.remove(type);
            shutdownExecutorGracefully(exec);
        }
        if (eventListenerMap.size() != executors.size()) {
            log.warn("listener & executor size mismatch {} != {}", eventListenerMap.size(), executors.size());
        }
        if (eventListenerMap.isEmpty() && executors.isEmpty()) {
            running.set(false);
        }
    }

    @Override
    public void shutdown0() {
        running.set(false);
        executors.values().forEach(this::shutdownExecutorGracefully);
        executors.clear();
    }


    private void shutdownExecutorGracefully(ExecutorService exec) {
        if (exec == null) {
            return;
        }

        try {
            exec.shutdown(); // disallow new tasks
            if (!exec.awaitTermination((long) 5, TimeUnit.SECONDS)) {
                exec.shutdownNow(); // interrupt running tasks
                if (!exec.awaitTermination((long) 5, TimeUnit.SECONDS)) {
                    log.warn("Executor did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            log.debug("Executor shutdown attempt finished.");
        }
    }


}