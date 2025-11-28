
package com.socketio4j.socketio.store;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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

    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<PubSubListener<PubSubMessage>> listenerRef = new AtomicReference<>();

    private StreamMessageId offset; // "$"
    private final Duration readTimeout;
    private final int readBatchSize;
    private final List<PubSubType> enabledTypes;

    public SingleChannelRedisStreamsPubSubStore(String streamName, Long nodeId, RedissonClient redissonClient, int maxRetryCount, StreamMessageId offset, Duration readTimeout, int readBatchSize, List<PubSubType> enabledTypes) {
        this.nodeId = nodeId;
        this.redissonClient = redissonClient;
        this.maxRetryCount = maxRetryCount;
        this.offset = offset;
        this.readTimeout = readTimeout;
        this.readBatchSize = readBatchSize;
        this.stream = redissonClient.getStream(streamName);
        if (enabledTypes.contains(PubSubType.ALL_SINGLE_CHANNEL)) {
            this.enabledTypes =  Arrays.stream(PubSubType.values())
                    .filter(t -> t != PubSubType.ALL_SINGLE_CHANNEL)
                    .collect(Collectors.toList());
        } else {
            this.enabledTypes = enabledTypes;
        }

    }

    @Override
    public PubSubStoreMode getMode() {
        return PubSubStoreMode.SINGLE_CHANNEL;
    }

    @Override
    public List<PubSubType> getEnabledTypes() {
        return enabledTypes;
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
        // Single-channel mode subscribes to ALL types
        if (type != PubSubType.ALL_SINGLE_CHANNEL) {
                throw new UnsupportedOperationException(
                         "Single-channel mode only supports PubSubType.ALL_SINGLE_CHANNEL - no individual subscribes");
        }
        log.debug("Starting async Redis Streams worker");

        // install listener only once
        if (!listenerRef.compareAndSet(null, (PubSubListener<PubSubMessage>) listener)) {
            log.warn("Ignoring additional subscribe() calls. Only one listener is allowed.");
            return;
        }

        // start worker only once
        if (running.compareAndSet(false, true)) {
            pollAsync(stream, listenerRef.get());
        }
    }



    private void pollAsync(final RStream<String, PubSubMessage> stream,
                           final PubSubListener<PubSubMessage> listener) {
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
                            PubSubMessage msg =
                                    entry.getValue().entrySet().iterator().next().getValue();

                            if (!nodeId.equals(msg.getNodeId())) {

                                boolean processed;
                                do {
                                    processed = processMessage(msg, listener, id);
                                } while (!processed);


                            }

                            // success or give-up → advance offset (as we use > )
                            offset = id;
                        }
                    }

                    pollAsync(stream, listener);
                });
    }



    private boolean processMessage(PubSubMessage msg,
                                   PubSubListener<PubSubMessage> listener,
                                   StreamMessageId id) {

        String key = id.toString();
        int attempts = retryCount.getOrDefault(key, 0);

        try {
            listener.onMessage(msg);
            retryCount.remove(key);
            return true;  // success
        } catch (Exception ex) {

            int nextAttempt = attempts + 1;

            if (nextAttempt < maxRetryCount) {
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



    private void scheduleNextPoll(final RStream<String, PubSubMessage> stream, final PubSubListener<PubSubMessage> listener) {
        if (running.get()) {
            executorService.schedule(() -> pollAsync(stream, listener), 1, TimeUnit.SECONDS);
        }

    }


    @Override
    public void unsubscribe(PubSubType type) {
        // Single-channel mode subscribes to ALL types
        if (type != PubSubType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "Single-channel mode only supports PubSubType.ALL_SINGLE_CHANNEL - no individual un-subscribes");
        }
        log.debug("Unsubscribing from Redis Streams");
        running.set(false);
        listenerRef.set(null);
    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    @Override
    public void shutdown() {
        log.debug("Shutting down Redis Streams");
        unsubscribe(PubSubType.ALL_SINGLE_CHANNEL);
        executorService.shutdownNow();
        redissonClient.shutdown();
    }

}