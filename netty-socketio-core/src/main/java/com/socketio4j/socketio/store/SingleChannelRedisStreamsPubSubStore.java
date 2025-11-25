
package com.socketio4j.socketio.store;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.netty.util.internal.ObjectUtil;
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
import com.socketio4j.socketio.store.pubsub.PubSubType;
import com.socketio4j.socketio.store.pubsub.PubSubStoreMode;


public class SingleChannelRedisStreamsPubSubStore implements PubSubStore {

    private static final Logger log = LoggerFactory.getLogger(SingleChannelRedisStreamsPubSubStore.class);

    /** Global Redis Stream key */
    private static final String STREAM_NAME = "socketio";

    /** Base name; real consumer group is consumerGroupBase + "-" + nodeId */
    private static final String CONSUMER_GROUP_BASE = "socketio";

    private final Long nodeId;
    private final RedissonClient redissonClient;

    private StreamMessageId offset = StreamMessageId.NEWEST; // "$"

    public SingleChannelRedisStreamsPubSubStore(Long nodeId, RedissonClient redissonClient) {
        this.nodeId = nodeId;
        this.redissonClient = redissonClient;
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
        RStream<String, PubSubMessage> s = redissonClient.getStream(STREAM_NAME);
        // Stream auto-creates on first add
        s.add(StreamAddArgs.entry(type.toString(), msg));
    }

    // =========================================================================
    // SUBSCRIBE
    // =========================================================================

    @Override
    public <T extends PubSubMessage> void subscribe(PubSubType type,
                                                    PubSubListener<T> listener,
                                                    Class<T> clazz) {
        ObjectUtil.checkNotNull(listener, "listener");
        log.debug("starting async Redis Streams worker");
        final RStream<String, PubSubMessage> stream = redissonClient.getStream(STREAM_NAME);
        pollAsync(stream, (PubSubListener<PubSubMessage>) listener);

    }

    private void pollAsync(final RStream<String, PubSubMessage> stream,  final PubSubListener<PubSubMessage> listener) {

        stream.readAsync(StreamReadArgs
                        .greaterThan(offset)
                        .count(100)
                        .timeout(Duration.ofMillis(1000)))
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
                                if (listener != null) {
                                    try {
                                        listener.onMessage(msg);
                                    } catch (Exception ex) {
                                        log.error("Listener error for type {}", type, ex);
                                        // retry same message next poll (no offset update)
                                        continue;
                                    }
                                }
                            }

                            offset = id;  // move forward safely
                        }
                    }

                    // immediately repeat — JS adapter style
                    pollAsync(stream, listener);
                });
    }

    private void scheduleNextPoll(final RStream<String, PubSubMessage> stream, PubSubListener<PubSubMessage> listener) {
        redissonClient.getExecutorService("default").schedule(() -> pollAsync(stream, listener), 1000, TimeUnit.MILLISECONDS);
    }


    @Override
    public void unsubscribe(PubSubType type) {

    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    @Override
    public void shutdown() {

    }

}