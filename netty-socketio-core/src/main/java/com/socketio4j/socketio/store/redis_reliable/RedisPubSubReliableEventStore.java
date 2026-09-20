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
import java.util.List;
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

public class RedisPubSubReliableEventStore implements EventStore {

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;
    private final Long nodeId;
    private final EventStoreMode eventStoreMode;
    private final int partitionCount;
    private final String streamNamePrefix;
    private final Integer streamMaxLength;
    private final Duration trimEvery;
    private final ScheduledExecutorService trimExecutor;
    private static final String DEFAULT_STREAM_NAME_PREFIX = "SOCKETIO4J:";
    private static final int DEFAULT_STREAM_MAX_LENGTH = Integer.MAX_VALUE;
    private final ConcurrentMap<EventType, Queue<String>> map = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RReliableTopic> activeSubTopics = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RReliableTopic> activePubTopics = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RStream<String, EventMessage>> trimTopics = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(RedisPubSubReliableEventStore.class);


    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------


    public RedisPubSubReliableEventStore(@NotNull RedissonClient redissonPub,
                                      @NotNull RedissonClient redissonSub,
                                      @Nullable Long nodeId, EventStoreMode eventStoreMode,
                                      @Nullable String streamNamePrefix,
                                      @Nullable Integer streamMaxLength,
                                      @Nullable Duration trimEvery) {
        this(redissonPub, redissonSub, nodeId, eventStoreMode, streamNamePrefix, streamMaxLength, trimEvery, DEFAULT_PARTITION_COUNT);
    }

    private RedisPubSubReliableEventStore(@NotNull RedissonClient redissonPub,
                                      @NotNull RedissonClient redissonSub,
                                      @Nullable Long nodeId, EventStoreMode eventStoreMode,
                                      @Nullable String streamNamePrefix,
                                      @Nullable Integer streamMaxLength,
                                      @Nullable Duration trimEvery,
                                      int partitionCount) {

        if (eventStoreMode == null) {
            eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        }
        this.eventStoreMode = eventStoreMode;
        this.partitionCount = partitionCount > 0 ? partitionCount : DEFAULT_PARTITION_COUNT;

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

        // Set default trim interval only if:
        // 1. User did not provide a custom trimEvery value (trimEvery is null)
        // 2. Stream max length is limited (not Integer.MAX_VALUE)
        // This ensures we don't override user's explicit configuration
        if (trimEvery == null && streamMaxLength != Integer.MAX_VALUE) {
            trimEvery = Duration.ofSeconds(60);
            log.debug("Auto-trim enabled with default interval: 60 seconds (streamMaxLength: {})", streamMaxLength);
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
    /**
     * Trims all reliable streams to maintain the configured maximum length.
     * 
     * <p>This method is called periodically by the scheduled executor to prevent streams
     * from growing unbounded. The trimming is done asynchronously to avoid blocking.
     */
    private void trimAllReliableStreams() {

        try {
            if (EventStoreMode.SINGLE_CHANNEL.equals(getEventStoreMode())) {
                trimStream(streamNamePrefix + EventType.ALL_SINGLE_CHANNEL.name());
            } else if (EventStoreMode.PARTITIONED_CHANNEL.equals(getEventStoreMode())) {
                trimStream(streamNamePrefix + "lifecycle");
                for (int i = 0; i < partitionCount; i++) {
                    trimStream(streamNamePrefix + "room_" + i);
                }
            } else {
                for (EventType type : EventType.values()) {
                    trimStream(streamNamePrefix + type.name());
                }
            }
        } catch (Exception t) {
            log.warn("Redis stream trim cycle failed", t);
        }
    }

    /**
     * Trims a single Redis stream to the configured maximum length.
     * 
     * <p>Uses {@code trimNonStrictAsync()} with {@code noLimit()} to perform non-blocking
     * trimming. The {@code noLimit()} parameter removes the cap on the number of evicted
     * objects for the trim command, allowing a single trim operation to evict more entries
     * (i.e., be more aggressive) rather than limiting evictions per call.
     * 
     * <p>After trimming, the stream size is checked and logged for monitoring purposes.
     * Failures are logged but don't throw exceptions to prevent interrupting the trim cycle.
     * 
     * @param streamName the stream name to trim
     */
    private void trimStream(String streamName) {
        try {
            RStream<String, EventMessage> stream =
                    trimTopics.computeIfAbsent(streamName, redissonPub::getStream);

            stream.trimNonStrictAsync(
                    StreamTrimArgs.maxLen(streamMaxLength).noLimit()
            ).whenComplete((trimmed, err) -> {
                if (err != null) {
                    log.warn("Trim failed for {}", streamName, err);
                    return;
                }

                // Log stream size after trimming for monitoring
                stream.sizeAsync()
                        .whenComplete((length, sizeErr) -> {
                            if (sizeErr != null) {
                                log.warn("Failed to read stream size {}", streamName, sizeErr);
                                return;
                            }
                            log.debug("Stream {} length={}", streamName, length);
                        });

            });

        } catch (Exception e) {
            log.warn("Failed to trim Redis stream {}", streamName, e);
        }
    }


    @Override
    public EventStoreMode getEventStoreMode(){
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

    @Override
    public PublishMode getPublishMode(){
        return PublishMode.RELIABLE;
    }

    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);
        String channelName = resolveChannelName(streamNamePrefix, type, msg, partitionCount, eventStoreMode);
        RReliableTopic topic = activePubTopics.computeIfAbsent(channelName, redissonPub::getReliableTopic);
        topic.publish(msg);
    }

    @Override
    public <T extends EventMessage> void subscribe0(EventType type, final EventListener<T> listener, Class<T> clazz) {
        List<String> channelNames = resolveSubscriptionChannels(streamNamePrefix, type, partitionCount, eventStoreMode);
        for (String channelName : channelNames) {
            RReliableTopic reliableTopic = redissonSub.getReliableTopic(channelName);
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
                log.warn("Failed to remove listener {} from topic", id, ex);
            }
        }


    }


    @Override
    public void shutdown0() {
        // Gracefully shutdown trim executor
        trimExecutor.shutdown();

        // Unsubscribe from all event types
        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
        map.clear();

        // Clear all topic references
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

        public RedisPubSubReliableEventStore build() {
            return new RedisPubSubReliableEventStore(
                    redissonPub,
                    redissonSub,
                    nodeId,
                    eventStoreMode,
                    streamNamePrefix,
                    streamMaxLength,
                    trimEvery,
                    partitionCount
            );
        }
    }

}
