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
package com.socketio4j.socketio.store.redis_reliable;

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
import org.redisson.api.RStream;
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
import com.socketio4j.socketio.store.event.PublishMode;

public class RedissonReliableEventStore implements EventStore {

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
    private final ConcurrentMap<EventType, Queue<String>> map = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RReliableTopic> activeSubTopics = new ConcurrentHashMap<>();
    private final ConcurrentMap<EventType, RReliableTopic> activePubTopics = new ConcurrentHashMap<>();
    private final ConcurrentMap<EventType, RStream<String, EventMessage>> trimTopics = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(RedissonReliableEventStore.class);


    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------


    public RedissonReliableEventStore(@NotNull RedissonClient redissonPub,
                                      @NotNull RedissonClient redissonSub,
                                      @Nullable Long nodeId, EventStoreMode eventStoreMode,
                                      @Nullable String streamNamePrefix,
                                      @Nullable Integer streamMaxLength,
                                      @Nullable Duration trimEvery) {

        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        }
        this.eventStoreMode = eventStoreMode;

        Objects.requireNonNull(redissonPub, "redissonPub client can not be null");
        Objects.requireNonNull(redissonSub, "redissonSub client can not be null");

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

        if (streamMaxLength != Integer.MAX_VALUE) {
            trimEvery = Duration.ofSeconds(60);
        }

        this.redissonPub = redissonPub;
        this.redissonSub = redissonSub;
        if (nodeId == null) {
            nodeId = getNodeId();
        }
        this.nodeId = nodeId;

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
            if (EventStoreMode.SINGLE_CHANNEL.equals(getEventStoreMode())) {
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
    private RStream<String, EventMessage> createStream(EventType type) {
        return redissonPub.getStream(getStreamName(type));
    }

    private void trimStream(EventType type) {
        try {
            RStream<String, EventMessage> stream =
                    trimTopics.computeIfAbsent(type, this::createStream);

            stream.trimNonStrictAsync(
                    StreamTrimArgs.maxLen(streamMaxLength).noLimit()
            ).whenComplete((trimmed, err) -> {
                if (err != null) {
                    log.warn("Trim failed for {}", getStreamName(type), err);
                    return;
                }

                stream.sizeAsync()
                        .whenComplete((length, sizeErr) -> {
                            if (sizeErr != null) {
                                log.warn("Failed to read stream size {}", getStreamName(type), sizeErr);
                                return;
                            }
                            log.debug("Stream {} length={}", getStreamName(type), length);
                        });

            });

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
    public PublishMode getPublishMode(){
        return PublishMode.RELIABLE;
    }
    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);
        RReliableTopic topic = activePubTopics.computeIfAbsent(type, k -> {
            String topicName = getStreamName(k);
            return redissonPub.getReliableTopic(topicName);
        });
        topic.publish(msg);
    }

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, final EventListener<T> listener, Class<T> clazz) {

            RReliableTopic reliableTopic = redissonSub.getReliableTopic(getStreamName(type));
            Objects.requireNonNull(reliableTopic, "reliableTopic can not be null");
            String id = reliableTopic.addListener(clazz, (channel, msg) -> {
                if (!nodeId.equals(msg.getNodeId())) {
                    listener.onMessage(msg);
                }
            });
            activeSubTopics.put(id, reliableTopic);
            map.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>())
                    .add(id);
    }

    private String getStreamName(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(getEventStoreMode())) {
            return streamNamePrefix + EventType.ALL_SINGLE_CHANNEL.name();
        }
        return streamNamePrefix + type.name();
    }

    @Override
    public void unsubscribe0(EventType type) {

        Queue<String> regIds = map.remove(type);
        if (regIds == null || regIds.isEmpty()) {
            return;
        }
        for (String id : regIds) {
            RReliableTopic topic = activeSubTopics.remove(id);
            if (topic == null) {
                continue;
            }
            try {
                topic.removeListener(id);
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
        activePubTopics.clear();
        activeSubTopics.clear();
        trimTopics.clear();
    }

    public static final class Builder {

        // -------------------------
        // Required
        // -------------------------
        private final RedissonClient redissonPub;
        private final RedissonClient redissonSub;

        // -------------------------
        // Optional (defaults)
        // -------------------------
        private Long nodeId;
        private EventStoreMode eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        private String streamNamePrefix = DEFAULT_STREAM_NAME_PREFIX;
        private Integer streamMaxLength = DEFAULT_STREAM_MAX_LENGTH;
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
            this.eventStoreMode = Objects.requireNonNull(mode, "eventStoreMode");
            return this;
        }

        public Builder streamNamePrefix(@NotNull String prefix) {
            if (prefix.isEmpty()) {
                throw new IllegalArgumentException("streamNamePrefix cannot be empty");
            }
            this.streamNamePrefix = prefix;
            return this;
        }

        public Builder streamMaxLength(int maxLength) {
            if (maxLength <= 0) {
                throw new IllegalArgumentException("streamMaxLength must be > 0");
            }
            this.streamMaxLength = maxLength;
            return this;
        }

        public Builder trimEvery(@NotNull Duration duration) {
            this.trimEvery = duration;
            return this;
        }

        // -------------------------
        // Build
        // -------------------------

        public RedissonReliableEventStore build() {
            return new RedissonReliableEventStore(
                    redissonPub,
                    redissonSub,
                    nodeId,
                    eventStoreMode,
                    streamNamePrefix,
                    streamMaxLength,
                    trimEvery
            );
        }
    }

}
