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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamReadArgs;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.BulkJoinMessage;
import com.socketio4j.socketio.store.event.BulkLeaveMessage;
import com.socketio4j.socketio.store.event.ConnectMessage;
import com.socketio4j.socketio.store.event.DisconnectMessage;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.JoinMessage;
import com.socketio4j.socketio.store.event.LeaveMessage;
import com.socketio4j.socketio.store.event.TestMessage;

import io.netty.util.internal.ObjectUtil;

public class RedisStreamsStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsStore.class);
    private static final String DEFAULT_STREAM_PREFIX = "socketio4j";
    private final ConcurrentMap<EventType, Queue<EventListener<EventMessage>>> map = new ConcurrentHashMap<>();
    private final EventStoreMode eventStoreMode;
    private final String streamName;
    private final Map<NameType, RStream<String, EventMessage>> streams;
    private final Long nodeId;
    private final RedissonClient redissonClient;
    private final int maxRetryCount;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Integer> retryCount = new ConcurrentHashMap<>();

    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    private final ConcurrentHashMap<EventType, StreamMessageId> offsets; // "$"
    private final Duration readTimeout;
    private final int readBatchSize;


    public RedisStreamsStore(String streamName, Long nodeId, RedissonClient redissonClient, int maxRetryCount, StreamMessageId offset, Duration readTimeout, int readBatchSize, EventStoreMode eventStoreMode) {
        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.SINGLE_CHANNEL;
            log.warn("eventStoreMode is null, loaded default {}", EventStoreMode.SINGLE_CHANNEL);
        }
        if (streamName == null || streamName.isEmpty()){
            streamName = DEFAULT_STREAM_PREFIX;
            log.warn("streamname is null/empty, loaded default {}", DEFAULT_STREAM_PREFIX);
        }
        this.eventStoreMode = eventStoreMode;
        this.nodeId = nodeId;
        this.redissonClient = redissonClient;
        this.maxRetryCount = maxRetryCount;

        this.readTimeout = readTimeout;
        this.readBatchSize = readBatchSize;
        this.streamName = streamName;
        List<EventType> eventTypeList = Collections.emptyList();
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            eventTypeList = Collections.singletonList(EventType.ALL_SINGLE_CHANNEL);
        } else if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)) {
            eventTypeList = Arrays.stream(EventType.values())
                    .filter(t -> t != EventType.ALL_SINGLE_CHANNEL)
                    .collect(Collectors.toList());
        }
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            this.streams = Collections.singletonMap(new NameType(getStreamName(EventType.ALL_SINGLE_CHANNEL), EventType.ALL_SINGLE_CHANNEL), redissonClient.getStream(streamName));
        } else {
            Map<NameType, RStream<String, EventMessage>> streamMap = new HashMap<>();
            eventTypeList.forEach(t -> streamMap.put(new NameType(getStreamName(t), t), redissonClient.getStream(getStreamName(t))));
            this.streams = streamMap;
        }
        for (Map.Entry<NameType, RStream<String, EventMessage>> entry : streams.entrySet()){
            try {
                entry.getValue().getInfo(); // check existence
            } catch (RedisException e) {
                if (e.getMessage().contains("no such key")) {
                    // create the stream (empty) via XADD
                    entry.getValue().add(StreamAddArgs.entry("", new TestMessage()));
                } else {
                    throw e;
                }
            }
        }

        this.offsets = new ConcurrentHashMap<>();
        for (EventType eventType : eventTypeList) {
            this.offsets.put(eventType, offset);
        }
        init();
    }

    private void init(){
        // start worker only once
        if (running.compareAndSet(false, true)) {
            streams.forEach((k, v) -> pollAsync(v, k.eventType));
        }
    }

    public RedisStreamsStore(RedissonClient redissonClient, Long nodeId, EventStoreMode eventStoreMode) {
        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.SINGLE_CHANNEL;
        }
        this.eventStoreMode = eventStoreMode;
        this.nodeId = nodeId;
        this.redissonClient = redissonClient;
        this.maxRetryCount = 0; // Default retry off

        this.readTimeout = Duration.ofSeconds(1);
        this.readBatchSize = 100;
        this.streamName = DEFAULT_STREAM_PREFIX;
        List<EventType> eventTypeList = Collections.emptyList();
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            eventTypeList = Collections.singletonList(EventType.ALL_SINGLE_CHANNEL);
        } else if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)) {
            eventTypeList = Arrays.stream(EventType.values())
                    .filter(t -> t != EventType.ALL_SINGLE_CHANNEL)
                    .collect(Collectors.toList());
        }
        this.offsets = new ConcurrentHashMap<>();
        for (EventType eventType : eventTypeList) {
            this.offsets.put(eventType, StreamMessageId.NEWEST);
        }

        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            this.streams = Collections.singletonMap(new NameType(getStreamName(EventType.ALL_SINGLE_CHANNEL), EventType.ALL_SINGLE_CHANNEL), redissonClient.getStream(streamName));
        } else {
            Map<NameType, RStream<String, EventMessage>> streamMap = new HashMap<>();
            eventTypeList.forEach(t -> streamMap.put(new NameType(getStreamName(t), t), redissonClient.getStream(getStreamName(t))));
            this.streams = streamMap;
        }
        init();
    }

    public RedisStreamsStore(RedissonClient redissonClient, EventStoreMode eventStoreMode) {
        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.SINGLE_CHANNEL;
        }
        this.eventStoreMode = eventStoreMode;
        this.nodeId = getNodeId();
        this.redissonClient = redissonClient;
        this.maxRetryCount = 0; // Default retry off

        this.readTimeout = Duration.ofSeconds(1);
        this.readBatchSize = 100;
        this.streamName = DEFAULT_STREAM_PREFIX;
        List<EventType> eventTypeList = Collections.emptyList();
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            eventTypeList = Collections.singletonList(EventType.ALL_SINGLE_CHANNEL);
        } else if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)) {
            eventTypeList = Arrays.stream(EventType.values())
                    .filter(t -> t != EventType.ALL_SINGLE_CHANNEL)
                    .collect(Collectors.toList());
        }
        this.offsets = new ConcurrentHashMap<>();
        for (EventType eventType : eventTypeList) {
            this.offsets.put(eventType, StreamMessageId.NEWEST);
        }

        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            this.streams = Collections.singletonMap(new NameType(getStreamName(EventType.ALL_SINGLE_CHANNEL), EventType.ALL_SINGLE_CHANNEL), redissonClient.getStream(streamName));
        } else {
            Map<NameType, RStream<String, EventMessage>> streamMap = new HashMap<>();
            eventTypeList.forEach(t -> streamMap.put(new NameType(getStreamName(t), t), redissonClient.getStream(getStreamName(t))));
            this.streams = streamMap;
        }
        init();
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

        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            streams.get(
                            new NameType(
                                    getStreamName(EventType.ALL_SINGLE_CHANNEL),
                                    EventType.ALL_SINGLE_CHANNEL))
                    .add(StreamAddArgs.entry(type.toString(), msg));
        } else {
            streams.get(new NameType(getStreamName(type), type)).add(StreamAddArgs.entry(type.toString(), msg));
        }

    }

    private String getStreamName(EventType type) {
        return streamName+":"+type;
    }

    // =========================================================================
    // SUBSCRIBE
    // =========================================================================

    @Override
    public <T extends EventMessage> void subscribe0(EventType type,
                                                   EventListener<T> listener,
                                                   Class<T> clazz) {
        ObjectUtil.checkNotNull(listener, "listener");
        // Single-channel mode subscribes to ALL types
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode) && type != EventType.ALL_SINGLE_CHANNEL) {
                throw new UnsupportedOperationException(
                         "can only subscribe with ALL_SINGLE_CHANNEL when you are on SINGLE_CHANNEL mode");
        } else if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode) &&  type == EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "can not subscribe with ALL_SINGLE_CHANNEL when you are on MULTI_CHANNEL mode");
        }
        map.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>()).add((EventListener<EventMessage>) listener);
        init(); //needed when subscribing after unsubscribing
    }



    private void pollAsync(final RStream<String, EventMessage> stream, EventType type) {
        if (!running.get()) {
            log.debug("Polling stopped {}", type);
            return;
        }
        stream.readAsync(StreamReadArgs
                        .greaterThan(offsets.get(type))
                        .count(readBatchSize)
                        .timeout(readTimeout))
                .whenComplete((messages, error) -> {

                    if (error != null) {
                        log.error("Streams async read failure", error);
                        scheduleNextPoll(stream, type);
                        return;
                    }

                    if (messages != null && !messages.isEmpty()) {

                        for (Map.Entry<StreamMessageId, Map<String, EventMessage>> entry : messages.entrySet()) {

                            StreamMessageId id = entry.getKey();
                            EventMessage msg =
                                    entry.getValue().entrySet().iterator().next().getValue();
                            if (!nodeId.equals(msg.getNodeId())) {
                                for (Map.Entry<EventType, Queue<EventListener<EventMessage>>> lisenerEntry : map.entrySet()) {
                                    EventType listenerEventType = lisenerEntry.getKey();
                                    Queue<EventListener<EventMessage>> listeners = lisenerEntry.getValue();
                                    for (EventListener<EventMessage> listener : listeners) {
                                        if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)) {
                                            if (
                                                    (msg instanceof ConnectMessage && listenerEventType != EventType.CONNECT)
                                                    || (msg instanceof DisconnectMessage && listenerEventType != EventType.DISCONNECT)
                                                    || (msg instanceof BulkJoinMessage && listenerEventType != EventType.BULK_JOIN)
                                                    || (msg instanceof BulkLeaveMessage && listenerEventType != EventType.BULK_LEAVE)
                                                    || (msg instanceof JoinMessage && listenerEventType != EventType.JOIN)
                                                    || (msg instanceof LeaveMessage && listenerEventType != EventType.LEAVE)
                                                    || (msg instanceof DispatchMessage && listenerEventType != EventType.DISPATCH)
                                            ) {
                                                continue;
                                            }
                                        } else if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
                                            if (listenerEventType != EventType.ALL_SINGLE_CHANNEL) {
                                                continue;
                                            }
                                        }
                                        boolean processed;
                                        try {
                                            do {
                                                processed = processMessage(msg, listener, id);
                                            } while (!processed);
                                        } catch (Exception ex) {
                                            log.error("Error processing stream message {} {} {}", msg, id, listener, ex);
                                        } finally {
                                            retryCount.remove(id.toString());
                                        }

                                    }
                                    // success or give-up → advance offset (as we use > )
                                    this.offsets.compute(type, (k, old) -> id);
                                }
                            }
                        }
                    }
                    pollAsync(stream, type);
                });
    }



    private boolean processMessage(EventMessage msg,
                                   EventListener<EventMessage> listener,
                                   StreamMessageId id) {
        String key = id.toString();
        int attempts = retryCount.getOrDefault(key, 0);
        msg.setOffset(key);
        try {
            listener.onMessage(msg);
            retryCount.remove(key);
            return true;  // success
        } catch (Exception ex) {

            int nextAttempt = attempts + 1;

            if (nextAttempt <= maxRetryCount) {
                log.error("Listener failed for {} (attempt {}/{}) → will retry on next poll",
                        id, nextAttempt, maxRetryCount, ex);

                retryCount.put(key, nextAttempt);
                return false;  // retry in next poll
            }

            // Max retries reached
            log.error("Giving up message {} after {} attempts", id, maxRetryCount, ex);

            retryCount.remove(key);

            // TODO: DLQ here

            return true; // "processed" (give up) → so offset moves
        }
    }



    private void scheduleNextPoll(final RStream<String, EventMessage> stream, final EventType type) {
        if (running.get()) {
            executorService.schedule(() -> pollAsync(stream, type), 1, TimeUnit.SECONDS);
        }
    }


    @Override
    public void unsubscribe0(EventType type) {
        // Single-channel mode subscribes to ALL types
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            if (type != EventType.ALL_SINGLE_CHANNEL) {
                throw new UnsupportedOperationException(
                        "Single-channel mode only supports EventType.ALL_SINGLE_CHANNEL - no individual un-subscribes");
            }
            map.clear();
            log.debug("Unsubscribing from Redis Streams");

        } else {
            map.remove(type);
        }
        if (map.isEmpty()) {
            running.set(false);
        }
    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    @Override
    public void shutdown0() {
        log.debug("Shutting down Redis Streams");
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            unsubscribe(EventType.ALL_SINGLE_CHANNEL);
        }

        if (map.isEmpty()) {
            running.set(false);
        }
        //streams.clear();
        offsets.clear();
        executorService.shutdownNow();
    }

    private static final class NameType {
        String name;
        EventType eventType;

        NameType(String name, EventType eventType) {
            this.name = name;
            this.eventType = eventType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NameType)) return false;
            NameType other = (NameType) o;
            return Objects.equals(name, other.name)
                    && eventType == other.eventType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, eventType);
        }
    }
}