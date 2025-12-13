package com.socketio4j.socketio.store.redis_stream;

/**
 * @author https://github.com/sanjomo
 * @date 13/12/25 6:04 pm
 */
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamTrimArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.PublishConfig;
import com.socketio4j.socketio.store.event.PublishMode;

public final class RedisStreamTrimmer {

    private static final Logger log =
            LoggerFactory.getLogger(RedisStreamTrimmer.class);

    private final RedissonClient redisson;
    private final ScheduledExecutorService executor;

    private final int maxLen;
    private final long intervalSeconds;
    private final EventStoreMode mode;
    private final PublishConfig publishConfig;

    public RedisStreamTrimmer(
            RedissonClient redisson,
            PublishConfig publishConfig,
            EventStoreMode mode,
            int maxLen,
            long intervalSeconds
    ) {
        this.redisson = redisson;
        this.publishConfig = publishConfig;
        this.mode = mode;
        this.maxLen = maxLen;
        this.intervalSeconds = intervalSeconds;

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socketio4j-redis-stream-trimmer");
            t.setDaemon(true);
            return t;
        });
    }

    // ----------------------------------------------------
    // Start periodic trimming
    // ----------------------------------------------------
    public void start() {
        executor.scheduleAtFixedRate(
                this::trimAllReliableStreams,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    // ----------------------------------------------------
    // Stop executor
    // ----------------------------------------------------
    public void shutdown() {
        executor.shutdownNow();
    }

    // ----------------------------------------------------
    // Trim logic
    // ----------------------------------------------------
    private void trimAllReliableStreams() {

        try {
            if (EventStoreMode.SINGLE_CHANNEL.equals(mode)) {
                trimStream(EventType.ALL_SINGLE_CHANNEL);
            } else {
                for (EventType type : EventType.values()) {
                    if (PublishMode.RELIABLE.equals(publishConfig.get(type))) {
                        trimStream(type);
                    }
                }
            }
        } catch (Throwable t) {
            log.warn("Redis stream trim cycle failed", t);
        }
    }

    private void trimStream(EventType type) {

        String topicName = type.name();
        String streamKey = "redisson:reliable_topic:" + topicName;

        try {
            redisson.getStream(streamKey)
                    .trimNonStrictAsync(
                            StreamTrimArgs.maxLen(maxLen).noLimit() // ≈ MAXLEN ~
                    );

            log.debug("Trim requested for {} (maxLen={})", streamKey, maxLen);

        } catch (Exception e) {
            log.warn("Failed to trim Redis stream {}", streamKey, e);
        }
    }
}
