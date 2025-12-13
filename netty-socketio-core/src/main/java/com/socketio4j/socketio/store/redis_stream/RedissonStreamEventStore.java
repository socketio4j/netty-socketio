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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RReliableTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamTrimArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;

public class RedissonStreamEventStore implements EventStore {

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;
    private final Long nodeId;
    private final EventStoreMode eventStoreMode;
    private final String streamNamePrefix;
    private final Integer streamMaxLength;
    private final Duration trimEvery;
    private final ScheduledExecutorService trimExecutor;
    private static final String DEFAULT_STREAM_NAME_PREFIX = "SOCKETIO4J:";
    private static final int DEFAULT_STREAM_MAX_LENGTH = Integer.MAX_VALUE;
    private final ConcurrentMap<EventType, Queue<Object>> map = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(RedissonStreamEventStore.class);

    private RedissonStreamEventStore(Builder b) {

        Objects.requireNonNull(b.redissonClient, "redissonClient");
        Objects.requireNonNull(b.eventStoreMode, "eventStoreMode");

        this.redissonPub = b.redissonClient;
        this.redissonSub = b.redissonClient;

        if (b.nodeId != null) {
            this.nodeId = b.nodeId;
        } else {
            this.nodeId = getNodeId();
        }
        this.eventStoreMode = b.eventStoreMode;

        if (b.streamNamePrefix == null || b.streamNamePrefix.isEmpty()) {
            this.streamNamePrefix =
                    DEFAULT_STREAM_NAME_PREFIX;
        } else {
            this.streamNamePrefix =
                    b.streamNamePrefix;
        }

        if (b.streamMaxLength == null || b.streamMaxLength <= 0) {
            this.streamMaxLength =
                    DEFAULT_STREAM_MAX_LENGTH;
        } else {
            this.streamMaxLength =
                    b.streamMaxLength;
        }

        this.trimEvery = b.trimEvery;

        this.trimExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socketio4j-redis-stream-trimmer");
            t.setDaemon(true);
            return t;
        });

        if (trimEvery != null) {
            trimExecutor.scheduleAtFixedRate(
                    this::trimAllReliableStreams,
                    1,
                    trimEvery.getSeconds(),
                    TimeUnit.SECONDS
            );
        }
    }

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------

    public  RedissonStreamEventStore(@NotNull RedissonClient redissonClient,
                                     @NotNull EventStoreMode eventStoreMode,
                                     @Nullable Duration trimEvery){
        this(redissonClient, null, eventStoreMode, null, null, trimEvery);
    }

    public RedissonStreamEventStore(@NotNull RedissonClient redissonClient,
                                    @Nullable Long nodeId,
                                    @NotNull EventStoreMode eventStoreMode,
                                    @Nullable String streamNamePrefix,
                                    @Nullable Integer streamMaxLength,
                                    @Nullable Duration trimEvery) {

        Objects.requireNonNull(redissonClient, "redisson client can not be null");

        if (streamNamePrefix == null || streamNamePrefix.isEmpty()) {
            streamNamePrefix = DEFAULT_STREAM_NAME_PREFIX;
            log.warn("streamNamePrefix is null/empty, loaded default : {}", DEFAULT_STREAM_NAME_PREFIX);
        }
        this.streamNamePrefix = streamNamePrefix;

        if (streamMaxLength == null || streamMaxLength <=0) {
            streamMaxLength = DEFAULT_STREAM_MAX_LENGTH;
            log.warn("streamMaxLength is null/less than 1, loaded default : {}", DEFAULT_STREAM_MAX_LENGTH);
        }
        this.streamMaxLength = streamMaxLength;


        Objects.requireNonNull(redissonClient, "redissonCli is null");
        this.redissonPub = redissonClient;
        this.redissonSub = redissonClient;
        if ( nodeId == null ){
            nodeId = getNodeId();
        }
        this.nodeId = nodeId;

        this.eventStoreMode = eventStoreMode;
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socketio4j-redis-stream-trimmer");
            t.setDaemon(true);
            return t;
        });
        this.trimExecutor = executorService;
        this.trimEvery = trimEvery;
        if (trimEvery != null) {
            executorService.scheduleAtFixedRate(
                    this::trimAllReliableStreams,
                    Duration.ofSeconds(1).getSeconds(),
                    trimEvery.getSeconds(),
                    TimeUnit.SECONDS
            );
        }

    }
    private void trimAllReliableStreams() {

        try {
            if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
                trimStream(EventType.ALL_SINGLE_CHANNEL);
            } else {
                for (EventType type : EventType.values()) {
                    trimStream(type);
                }
            }
        } catch (Exception t) {
            log.warn("Redis stream trim cycle failed", t);
        }
    }

    private void trimStream(EventType type) {

        try {
            redissonPub.getStream(getStreamName(type))
                    .trimNonStrictAsync(
                            StreamTrimArgs.maxLen(streamMaxLength).noLimit() // ≈ MAXLEN ~
                    );

            log.debug("Trim requested for {} (maxLen={})", getStreamName(type), streamMaxLength);

        } catch (Exception e) {
            log.warn("Failed to trim Redis stream {}", getStreamName(type), e);
        }
    }

    @Override
    public EventStoreMode getEventStoreMode(){
        return eventStoreMode;
    }

    @Override
    public EventStoreType getEventStoreType() {
        return EventStoreType.STREAM;
    }
    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);
        redissonPub.getReliableTopic(getStreamName(type)).publish(msg);
    }

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, final EventListener<T> listener, Class<T> clazz) {

            RReliableTopic reliableTopic = redissonSub.getReliableTopic(getStreamName(type));
            String id = reliableTopic.addListener(clazz, (channel, msg) -> {
                if (!nodeId.equals(msg.getNodeId())) {
                    listener.onMessage(msg);
                }
            });
            map.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>())
                    .add(id);
    }

    private String getStreamName(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            return streamNamePrefix + EventType.ALL_SINGLE_CHANNEL.name();
        }
        return streamNamePrefix + type.name();
    }

    @Override
    public void unsubscribe0(EventType type) {

        Queue<Object> regIds = map.remove(type);
        if (regIds == null || regIds.isEmpty()) {
            return;
        }

        RReliableTopic topic = redissonSub.getReliableTopic(getStreamName(type));
        if (topic == null) {
            return;
        }
        for (Object id : regIds) {
            try {
                topic.removeListener((String) id);
            } catch (Exception ex) {
                log.warn("Failed to remove listener {} from topic {}", id, getStreamName(type), ex);
            }
        }


    }

    @Override
    public void shutdown0() {
        trimExecutor.shutdown();
        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
        map.clear();
        trimExecutor.shutdownNow();
    }
    public static final class Builder {

        private final RedissonClient redissonClient;
        private final EventStoreMode eventStoreMode;

        private Long nodeId;
        private String streamNamePrefix;
        private Integer streamMaxLength;
        private Duration trimEvery;

        public Builder(@NotNull RedissonClient redissonClient,
                       @NotNull EventStoreMode eventStoreMode) {
            this.redissonClient = redissonClient;
            this.eventStoreMode = eventStoreMode;
        }

        public Builder nodeId(@Nullable Long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder streamNamePrefix(@Nullable String prefix) {
            this.streamNamePrefix = prefix;
            return this;
        }

        public Builder streamMaxLength(@Nullable Integer maxLength) {
            this.streamMaxLength = maxLength;
            return this;
        }

        public Builder trimEvery(@Nullable Duration duration) {
            this.trimEvery = duration;
            return this;
        }

        public RedissonStreamEventStore build() {
            return new RedissonStreamEventStore(this);
        }
    }

}
