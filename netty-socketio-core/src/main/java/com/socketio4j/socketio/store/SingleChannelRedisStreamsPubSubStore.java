
package com.socketio4j.socketio.store;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamReadArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.pubsub.PubSubListener;
import com.socketio4j.socketio.store.pubsub.PubSubMessage;
import com.socketio4j.socketio.store.pubsub.PubSubStore;
import com.socketio4j.socketio.store.pubsub.PubSubStoreMode;
import com.socketio4j.socketio.store.pubsub.PubSubType;

import io.netty.util.internal.ObjectUtil;

public class SingleChannelRedisStreamsPubSubStore implements PubSubStore {

    private static final Logger log = LoggerFactory.getLogger(SingleChannelRedisStreamsPubSubStore.class);


    private final RStream<String, PubSubMessage> stream;
    private final Long nodeId;
    private final RedissonClient redissonClient;
    private final int maxRetryCount;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Integer> retryCount = new ConcurrentHashMap<>();



    private StreamMessageId offset; // "$"
    private final Duration readTimeout;
    private final int readBatchSize;

    public SingleChannelRedisStreamsPubSubStore(String streamName, Long nodeId, RedissonClient redissonClient, int maxRetryCount, StreamMessageId offset, Duration readTimeout, int readBatchSize) {
        this.nodeId = nodeId;
        this.redissonClient = redissonClient;
        this.maxRetryCount = maxRetryCount;
        this.offset = offset;
        this.readTimeout = readTimeout;
        this.readBatchSize = readBatchSize;
        this.stream = redissonClient.getStream(streamName);
    }

    @Override
    public PubSubStoreMode getMode() {
        return PubSubStoreMode.SINGLE_CHANNEL;
    }
    // =========================================================================
    // PUBLISH
    // =========================================================================

    @Override
    public void publish(PubSubType type, PubSubMessage msg) {
        msg.setNodeId(nodeId);
        // Stream auto-creates on first add
        stream.add(StreamAddArgs.entry(type.toString(), msg));
    }

    // =========================================================================
    // SUBSCRIBE
    // =========================================================================

    @Override
    public <T extends PubSubMessage> void subscribe(PubSubType type,
                                                    PubSubListener<T> listener,
                                                    Class<T> clazz) {

        ObjectUtil.checkNotNull(listener, "listener");
        log.debug("Starting async Redis Streams worker");
        // Start the worker only once
        if (running.compareAndSet(false, true)) {
            pollAsync(stream, (PubSubListener<PubSubMessage>) listener);
        }

    }


    private void pollAsync(final RStream<String, PubSubMessage> stream, final PubSubListener<PubSubMessage> listener) {
        if (!running.get()) {
            log.debug("Polling stopped");
            return;
        }

        stream.readAsync(StreamReadArgs
                        .greaterThan(offset)
                        .count(readBatchSize)
                        .timeout(readTimeout))
                .whenComplete((messages, error) -> {

                    if (error != null) {
                        log.error("Streams async read failure", error);
                        scheduleNextPoll(stream, listener);
                        return;
                    }

                    if (messages != null && !messages.isEmpty()) {
                        for (Map.Entry<StreamMessageId, Map<String, PubSubMessage>> entry : messages.entrySet()) {

                            StreamMessageId id = entry.getKey();
                            Map<String, PubSubMessage> fields = entry.getValue();
                            Map.Entry<String, PubSubMessage> field = fields.entrySet().iterator().next();

                            String typeString = field.getKey();
                            PubSubMessage msg = field.getValue();
                            PubSubType type = PubSubType.valueOf(typeString.toUpperCase());

                            if (!nodeId.equals(msg.getNodeId())) {
                                // Get existing retry count or 0
                                int attempts = retryCount.getOrDefault(id.toString(), 0);
                                try {
                                    listener.onMessage(msg);
                                    // Success → cleanup retry entry
                                    retryCount.remove(id.toString());
                                } catch (Exception ex) {
                                    log.error("Listener error for type {} (attempt {}/3)", type, attempts + 1, ex);

                                    if (attempts < maxRetryCount-1) {
                                        // Increment retry counter and retry later
                                        retryCount.put(id.toString(), attempts + 1);

                                        // DO NOT move offset → retry same message next poll
                                        continue;
                                    }

                                    // Already tried 3 times → give up this message
                                    log.error("Giving up message {} after 3 attempts ", id, ex);
                                    retryCount.remove(id.toString());
                                }
                            }


                            offset = id;  // move forward safely
                        }
                    }
                    pollAsync(stream, listener);
                });
    }

    private void scheduleNextPoll(final RStream<String, PubSubMessage> stream, final PubSubListener<PubSubMessage> listener) {

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

        exec.schedule(() -> {
            try {
                pollAsync(stream, listener);
            } finally {
                exec.shutdown();
            }
        }, 1, TimeUnit.SECONDS);

    }


    @Override
    public void unsubscribe(PubSubType type) {
        log.debug("Unsubscribing from Redis Streams");
        running.set(false);
    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    @Override
    public void shutdown() {
        log.debug("Shutting down Redis Streams");
        running.set(false);
        redissonClient.shutdown();
    }

}