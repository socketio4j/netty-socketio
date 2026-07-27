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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.AbstractEventStore;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.ListenerRegistry;


public class RedisStreamEventStore extends AbstractEventStore {

    private static final Logger log =
            LoggerFactory.getLogger(RedisStreamEventStore.class);

    // ---------------------------------------------------------------------
    // Defaults
    // ---------------------------------------------------------------------

    private static final String DEFAULT_PREFIX = "SOCKETIO4J:";
    private static final int DEFAULT_MAX_LEN = Integer.MAX_VALUE;

    // ---------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;
    private final int streamMaxLength;

    // ---------------------------------------------------------------------
    // Runtime
    // ---------------------------------------------------------------------

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final ConcurrentMap<EventType, RStream<String, EventMessage>> pubStreams =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<EventType, RStream<String, EventMessage>> subStreams =
            new ConcurrentHashMap<>();

    private final ListenerRegistry listeners = new ListenerRegistry();

    private final ConcurrentMap<EventType, StreamMessageId> offsets =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<EventType, ScheduledExecutorService> pollers =
            new ConcurrentHashMap<>();

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
        super(nodeId, eventStoreMode, EventStoreMode.SINGLE_CHANNEL, streamNamePrefix, DEFAULT_PREFIX);

        this.redissonPub = Objects.requireNonNull(redissonPub, "redissonPub");
        this.redissonSub = Objects.requireNonNull(redissonSub, "redissonSub");

        if (streamMaxLength == null || streamMaxLength <= 0) {
            streamMaxLength = DEFAULT_MAX_LEN;
            log.warn(
                    "streamMaxLength is null/less than 1, loaded default : {}",
                    DEFAULT_MAX_LEN
            );
        }
        this.streamMaxLength = streamMaxLength;


        initStreams();
    }

    // ---------------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------------

    private void initStreams() {

        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            initStream(EventType.ALL_SINGLE_CHANNEL);
        } else {
            Arrays.stream(EventType.values())
                    .filter(t -> t != EventType.ALL_SINGLE_CHANNEL)
                    .forEach(this::initStream);
        }
    }

    private void initStream(EventType type) {
        subStreams.put(type, redissonSub.getStream(channelName(type)));
        pubStreams.put(type, redissonPub.getStream(channelName(type)));
        offsets.put(type, StreamMessageId.NEWEST);
    }

    // ---------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------

    @Override
    public EventStoreType getEventStoreType() {
        return EventStoreType.STREAM;
    }

    // ---------------------------------------------------------------------
    // Publish
    // ---------------------------------------------------------------------

    @Override
    public void publish0(EventType type, EventMessage msg) {
        stampNodeId(msg);

        pubStreams.computeIfAbsent(
                resolveType(type),
                t -> redissonPub.getStream(channelName(t))
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

        Objects.requireNonNull(listener);
        Objects.requireNonNull(clazz);

        validateSubscribe(type);

        listeners.register(type, listener, clazz);

        ensurePoller(type);
    }

    private void ensurePoller(EventType type) {

        pollers.compute(type, (t, exec) -> {
            if (exec == null || exec.isShutdown()) {

                ScheduledExecutorService newExec =
                        Executors.newSingleThreadScheduledExecutor(r -> {
                            Thread th = new Thread(r);
                            th.setName("socketio4j-redis-stream-" + t.name());
                            th.setDaemon(true);
                            return th;
                        });

                RStream<String, EventMessage> stream =
                        subStreams.computeIfAbsent(
                                t,
                                k -> redissonSub.getStream(channelName(k))
                        );
                newExec.execute(() -> pollLoop(stream, t));
                return newExec;

            }
            return exec;
        });
    }

    private void pollLoop(RStream<String, EventMessage> stream, EventType type) {

        if (!running.get() || Thread.currentThread().isInterrupted()) {
            return;
        }

        stream.readAsync(
                StreamReadArgs
                        .greaterThan(offsets.get(type))
                        .timeout(Duration.ofSeconds(10))
                        .count(100)
        ).whenComplete((records, err) -> {

            if (err != null) {
                log.error("XREAD failed {}", type, err);
                scheduleRetry(stream, type);
                return;
            }

            if (records != null && !records.isEmpty()) {
                records.forEach((id, map) -> {
                    if (map.isEmpty()) {
                        offsets.put(type, id);
                        return;
                    }

                    EventMessage msg = map.values().iterator().next();
                    try {
                        if (isRemote(msg)) {
                            dispatch(type, msg, id);
                        }
                    } finally {
                        offsets.put(type, id);
                    }
                });
            }

            ScheduledExecutorService exec = pollers.get(type);
            if (exec != null && running.get()) {
                exec.execute(() -> pollLoop(stream, type));
            }
        });
    }

    private void dispatch(
            EventType type,
            EventMessage msg,
            StreamMessageId id
    ) {
        msg.setOffset(id.toString());
        listeners.dispatch(type, msg);
    }

    private void scheduleRetry(RStream<String, EventMessage> stream, EventType type) {
        ScheduledExecutorService exec = pollers.get(type);
        if (exec != null && running.get()) {
            exec.schedule(() -> pollLoop(stream, type), 1, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------------
    // Unsubscribe / Shutdown
    // ---------------------------------------------------------------------

    @Override
    public void unsubscribe0(EventType type) {

        listeners.remove(type);

        ScheduledExecutorService exec = pollers.remove(type);
        if (exec != null) {
            exec.shutdownNow();
        }

        if (listeners.isEmpty()) {
            running.set(false);
        }
    }

    @Override
    public void shutdown0() {
        running.set(false);
        pollers.values().forEach(ScheduledExecutorService::shutdownNow);
        pollers.clear();
        listeners.clear();
        offsets.clear();
        pubStreams.clear();
        subStreams.clear();
    }

    // ---------------------------------------------------------------------
    // Utils
    // ---------------------------------------------------------------------

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
                    streamMaxLen
            );
        }
    }

}
