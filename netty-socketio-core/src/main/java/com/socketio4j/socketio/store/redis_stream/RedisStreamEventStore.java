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
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;
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

/**
 * Production-grade Redis Stream-based {@link EventStore}.
 * <p>
 * Implements durable, bounded, zero-loss pub/sub event broadcasting across cluster nodes
 * using Redis Streams with standalone fan-out {@code XREAD}.
 */
public class RedisStreamEventStore implements EventStore {

    private static final Logger log =
            LoggerFactory.getLogger(RedisStreamEventStore.class);

    // ---------------------------------------------------------------------
    // Defaults
    // ---------------------------------------------------------------------

    private static final String DEFAULT_PREFIX = "SOCKETIO4J:";
    private static final int DEFAULT_MAX_LEN = 100_000;

    // ---------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;
    private final Long nodeId;
    private final EventStoreMode eventStoreMode;
    private final String streamNamePrefix;
    private final int streamMaxLength;

    // ---------------------------------------------------------------------
    // Runtime
    // ---------------------------------------------------------------------

    private final AtomicBoolean storeRunning = new AtomicBoolean(true);
    private final ConcurrentMap<String, AtomicBoolean> activePollers =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, RStream<String, EventMessage>> pubStreams =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RStream<String, EventMessage>> subStreams =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<EventType, Queue<ListenerRegistration<? extends EventMessage>>> listeners =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, StreamMessageId> offsets =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService executor;
    private final int partitionCount;

    // ---------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------

    public RedisStreamEventStore(
            @NotNull RedissonClient redissonPub,
            @NotNull RedissonClient redissonSub,
            @Nullable Long nodeId,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable String streamNamePrefix,
            @Nullable Integer streamMaxLength
    ) {
        this(redissonPub, redissonSub, nodeId, eventStoreMode, streamNamePrefix, streamMaxLength, DEFAULT_PARTITION_COUNT);
    }

    private RedisStreamEventStore(
            @NotNull RedissonClient redissonPub,
            @NotNull RedissonClient redissonSub,
            @Nullable Long nodeId,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable String streamNamePrefix,
            @Nullable Integer streamMaxLength,
            int partitionCount
    ) {

        this.redissonPub = Objects.requireNonNull(redissonPub, "redissonPub");
        this.redissonSub = Objects.requireNonNull(redissonSub, "redissonSub");

        if (nodeId == null) {
            nodeId = getNodeId();
            log.warn("nodeId is null, loaded default : {}", nodeId);
        }
        this.nodeId = nodeId;

        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.SINGLE_CHANNEL;
            log.warn("mode is null, loaded default : {}", EventStoreMode.SINGLE_CHANNEL);
        }
        this.eventStoreMode = eventStoreMode;

        this.partitionCount = partitionCount > 0 ? partitionCount : DEFAULT_PARTITION_COUNT;

        if (streamNamePrefix == null || streamNamePrefix.isEmpty()) {
            streamNamePrefix = DEFAULT_PREFIX;
            log.warn("prefix is null/empty, loaded default : {}", DEFAULT_PREFIX);
        }
        this.streamNamePrefix = streamNamePrefix;

        if (streamMaxLength == null || streamMaxLength <= 0) {
            streamMaxLength = DEFAULT_MAX_LEN;
            log.warn(
                    "streamMaxLength is null/less than 1, loaded default : {}",
                    DEFAULT_MAX_LEN
            );
        }
        this.streamMaxLength = streamMaxLength;

        final AtomicInteger threadSeq = new AtomicInteger(1);
        this.executor = Executors.newScheduledThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
                r -> {
                    Thread th = new Thread(r);
                    th.setName("socketio4j-redis-stream-worker-" + threadSeq.getAndIncrement());
                    th.setDaemon(true);
                    return th;
                }
        );

        initStreams();
    }

    // ---------------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------------

    private void initStreams() {

        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            initStream(streamNamePrefix + EventType.ALL_SINGLE_CHANNEL.name());
        } else if (EventStoreMode.PARTITIONED_CHANNEL.equals(eventStoreMode)) {
            for (int i = 0; i < partitionCount; i++) {
                initStream(streamNamePrefix + "room_" + i);
            }
            initStream(streamNamePrefix + "lifecycle");
        } else {
            Arrays.stream(EventType.values())
                    .filter(t -> t != EventType.ALL_SINGLE_CHANNEL)
                    .forEach(t -> initStream(streamNamePrefix + t.name()));
        }
    }

    private void initStream(String streamName) {
        subStreams.put(streamName, redissonSub.getStream(streamName));
        pubStreams.put(streamName, redissonPub.getStream(streamName));
    }

    // ---------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------

    @Override
    public EventStoreMode getEventStoreMode() {
        return eventStoreMode;
    }

    @Override
    public int getPartitionCount() {
        return partitionCount;
    }

    @Override
    public EventStoreType getEventStoreType() {
        return EventStoreType.STREAM;
    }

    // ---------------------------------------------------------------------
    // Publish
    // ---------------------------------------------------------------------

    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);

        String streamName = resolveChannelName(streamNamePrefix, type, msg, partitionCount, eventStoreMode);
        pubStreams.computeIfAbsent(
                streamName,
                redissonPub::getStream
        ).add(StreamAddArgs.entry(type.name(), msg).trimNonStrict().maxLen(streamMaxLength).noLimit());

    }

    // ---------------------------------------------------------------------
    // Subscribe
    // ---------------------------------------------------------------------

    @Override
    public <T extends EventMessage> void subscribe0(
            EventType type,
            EventListener<T> listener,
            Class<T> clazz
    ) {
        if (!storeRunning.get()) {
            throw new IllegalStateException("RedisStreamEventStore has been shutdown");
        }

        Objects.requireNonNull(listener, "listener cannot be null");
        Objects.requireNonNull(clazz, "clazz cannot be null");

        validateSubscribe(type);

        listeners
                .computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>())
                .add(new ListenerRegistration<>(listener, clazz));

        List<String> streamNames = resolveSubscriptionChannels(streamNamePrefix, type, partitionCount, eventStoreMode);
        for (String streamName : streamNames) {
            offsets.computeIfAbsent(streamName, this::resolveInitialOffset);
            ensurePoller(streamName, type);
        }
    }

    /**
     * Resolves the initial read offset for a given stream name.
     * Queries the existing stream info if present; falls back safely to {@link StreamMessageId#ALL}
     * for brand-new or empty streams to prevent message drops on cold start.
     */
    private StreamMessageId resolveInitialOffset(String streamName) {
        try {
            RStream<String, EventMessage> stream =
                    subStreams.computeIfAbsent(
                            streamName,
                            redissonSub::getStream
                    );
            if (stream.isExists()) {
                StreamMessageId lastId = stream.getInfo().getLastGeneratedId();
                if (lastId != null) {
                    return lastId;
                }
            }
        } catch (Exception e) {
            log.debug("Could not query existing stream offset for {}, defaulting to ALL (0-0): {}",
                    streamName, e.getMessage());
        }
        return StreamMessageId.ALL;
    }

    private void ensurePoller(String streamName, EventType type) {
        AtomicBoolean pollerActive = activePollers.computeIfAbsent(streamName, k -> new AtomicBoolean(false));
        if (pollerActive.compareAndSet(false, true)) {
            RStream<String, EventMessage> stream =
                    subStreams.computeIfAbsent(
                            streamName,
                            redissonSub::getStream
                    );
            executor.execute(() -> pollLoop(stream, streamName, type, pollerActive, 0));
        }
    }

    private void pollLoop(
            RStream<String, EventMessage> stream,
            String streamName,
            EventType type,
            AtomicBoolean pollerActive,
            int retryAttempt
    ) {

        if (!storeRunning.get() || !pollerActive.get() || Thread.currentThread().isInterrupted()) {
            return;
        }

        StreamMessageId offset = offsets.get(streamName);
        if (offset == null) {
            offset = StreamMessageId.ALL;
        }

        stream.readAsync(
                StreamReadArgs
                        .greaterThan(offset)
                        .timeout(Duration.ofSeconds(2))
                        .count(100)
        ).whenComplete((records, err) -> {

            if (err != null) {
                if (!storeRunning.get() || !pollerActive.get() || isRedissonShutdown(err)) {
                    log.debug("XREAD cancelled during store shutdown for {}", streamName);
                    return;
                }
                log.error("XREAD failed for stream {}: {}", streamName, err.getMessage());
                scheduleRetry(stream, streamName, type, pollerActive, retryAttempt + 1);
                return;
            }

            if (records != null && !records.isEmpty()) {
                records.forEach((id, map) -> {
                    if (map == null || map.isEmpty()) {
                        offsets.put(streamName, id);
                        return;
                    }

                    for (EventMessage msg : map.values()) {
                        if (msg == null) {
                            continue;
                        }
                        try {
                            if (!nodeId.equals(msg.getNodeId())) {
                                dispatch(type, msg, id);
                            }
                        } catch (Throwable t) {
                            log.error("Unexpected error handling event {} with id {} on node {}: {}",
                                    type, id, nodeId, t.getMessage(), t);
                        }
                    }
                    offsets.put(streamName, id);
                });
            }

            if (storeRunning.get() && pollerActive.get()) {
                executor.execute(() -> pollLoop(stream, streamName, type, pollerActive, 0));
            }
        });
    }

    private <T extends EventMessage> void dispatch(
            EventType type,
            EventMessage msg,
            StreamMessageId id
    ) {

        Queue<ListenerRegistration<? extends EventMessage>> regs =
                listeners.get(type);

        if (regs == null || regs.isEmpty()) {
            return;
        }

        msg.setOffset(id.toString());

        for (ListenerRegistration<? extends EventMessage> reg : regs) {
            if (reg.getClazz().isInstance(msg)) {
                try {
                    ((ListenerRegistration<T>) reg)
                            .getListener()
                            .onMessage((T) msg);
                } catch (Throwable t) {
                    log.error("Listener {} threw exception processing event {} on node {}: {}",
                            reg.getListener(), type, nodeId, t.getMessage(), t);
                }
            }
        }
    }

    private void scheduleRetry(
            RStream<String, EventMessage> stream,
            String streamName,
            EventType type,
            AtomicBoolean pollerActive,
            int retryAttempt
    ) {
        if (!storeRunning.get() || !pollerActive.get()) {
            return;
        }

        // Exponential backoff with jitter: 50ms, 100ms, 200ms, ... capped at 2000ms
        long baseDelay = Math.min(2000L, 50L * (1L << Math.min(retryAttempt, 5)));
        long jitter = ThreadLocalRandom.current().nextLong(25);
        long delay = baseDelay + jitter;

        executor.schedule(
                () -> pollLoop(stream, streamName, type, pollerActive, retryAttempt),
                delay,
                TimeUnit.MILLISECONDS
        );
    }

    // ---------------------------------------------------------------------
    // Unsubscribe / Shutdown
    // ---------------------------------------------------------------------

    @Override
    public void unsubscribe0(EventType type) {

        listeners.remove(type);

        List<String> streamNames = resolveSubscriptionChannels(streamNamePrefix, type, partitionCount, eventStoreMode);
        for (String streamName : streamNames) {
            offsets.remove(streamName);
            AtomicBoolean pollerActive = activePollers.remove(streamName);
            if (pollerActive != null) {
                pollerActive.set(false);
            }
        }
    }

    @Override
    public void shutdown0() {
        if (!storeRunning.compareAndSet(true, false)) {
            return;
        }

        activePollers.values().forEach(b -> b.set(false));
        activePollers.clear();

        listeners.clear();
        offsets.clear();
        pubStreams.clear();
        subStreams.clear();

        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private boolean isRedissonShutdown(Throwable t) {
        if (t == null) {
            return false;
        }

        Throwable current = t;
        while (current != null) {
            if (current instanceof org.redisson.RedissonShutdownException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }

        return t.getMessage() != null && t.getMessage().contains("Redisson is shutdown");
    }

    private String streamName(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)
                || EventStoreMode.PARTITIONED_CHANNEL.equals(eventStoreMode)) {
            return streamNamePrefix + EventType.ALL_SINGLE_CHANNEL.name();
        }
        return streamNamePrefix + type.name();
    }

    private EventType resolve(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)
                || EventStoreMode.PARTITIONED_CHANNEL.equals(eventStoreMode)) {
            return EventType.ALL_SINGLE_CHANNEL;
        }
        return type;
    }

    private void validateSubscribe(EventType type) {
        if ((EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)
                || EventStoreMode.PARTITIONED_CHANNEL.equals(eventStoreMode))
                && type != EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "Only ALL_SINGLE_CHANNEL allowed in " + eventStoreMode + " mode");
        }
        if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)
                && type == EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "ALL_SINGLE_CHANNEL not allowed in MULTI_CHANNEL mode");
        }
    }

    public static final class Builder {

        // -------------------------
        // Required
        // -------------------------
        private final RedissonClient redissonPub;
        private final RedissonClient redissonSub;

        // -------------------------
        // Optional (nullable → constructor applies defaults + WARN)
        // -------------------------
        private Long nodeId;
        private EventStoreMode mode;
        private String prefix;
        private Integer streamMaxLen;
        private Duration trimEvery;
        private int partitionCount = DEFAULT_PARTITION_COUNT;

        // -------------------------
        // Constructors
        // -------------------------

        public Builder(@NotNull RedissonClient redissonClient) {
            this(redissonClient, redissonClient);
        }

        public Builder(@NotNull RedissonClient redissonPub,
                       @NotNull RedissonClient redissonSub) {
            this.redissonPub = Objects.requireNonNull(redissonPub, "redissonPub");
            this.redissonSub = Objects.requireNonNull(redissonSub, "redissonSub");
        }

        // -------------------------
        // Optional setters
        // -------------------------

        public Builder nodeId(long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder eventStoreMode(@NotNull EventStoreMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder prefix(@NotNull String prefix) {
            if (prefix.isEmpty()) {
                throw new IllegalArgumentException("prefix cannot be empty");
            }
            this.prefix = prefix;
            return this;
        }

        public Builder streamMaxLength(int streamMaxLen) {
            if (streamMaxLen <= 0) {
                throw new IllegalArgumentException("streamMaxLen must be > 0");
            }
            this.streamMaxLen = streamMaxLen;
            return this;
        }

        public Builder partitionCount(int partitionCount) {
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("partitionCount must be > 0");
            }
            this.partitionCount = partitionCount;
            return this;
        }

        // -------------------------
        // Build
        // -------------------------

        public RedisStreamEventStore build() {
            return new RedisStreamEventStore(
                    redissonPub,
                    redissonSub,
                    nodeId,
                    mode,
                    prefix,
                    streamMaxLen,
                    partitionCount
            );
        }
    }

}
