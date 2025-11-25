
package com.socketio4j.socketio.store;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
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
import com.socketio4j.socketio.store.pubsub.PubSubType;
import com.socketio4j.socketio.store.pubsub.PubSubStoreMode;


public class SingleChannelRedisStreamsPubSubStore implements PubSubStore {

    private static final Logger log = LoggerFactory.getLogger(SingleChannelRedisStreamsPubSubStore.class);

    /** Global Redis Stream key */
    private static final String STREAM_NAME = "socketio";

    /** Base name; real consumer group is consumerGroupBase + "-" + nodeId */
    private static final String CONSUMER_GROUP_BASE = "socketio";

    private final Long nodeId;
    private final RedissonClient redisson;

    private final  AtomicReference<PubSubListener<PubSubMessage>> listeners = new AtomicReference<>();

    // worker thread for the only stream
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    private StreamMessageId offset = StreamMessageId.NEWEST; // "$"

    public SingleChannelRedisStreamsPubSubStore(Long nodeId, RedissonClient redisson) {
        this.nodeId = nodeId;
        this.redisson = redisson;
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
        RStream<String, PubSubMessage> s = redisson.getStream(STREAM_NAME);
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

        listeners.set((PubSubListener<PubSubMessage>) listener);

        if (worker.get() == null) {
            Thread newWorker = startWorker();
            if (!worker.compareAndSet(null, newWorker)) {
                // another thread installed one → stop the newly created worker
                newWorker.interrupt();
            }
        }


    }

    // =========================================================================
    // WORKER LOOP (ONE for all types)
    // =========================================================================

    private Thread startWorker() {
        log.debug("starting worker thread");
        final RStream<String, PubSubMessage> stream = redisson.getStream(STREAM_NAME);

        Runnable task = () -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Map<StreamMessageId, Map<String, PubSubMessage>> messages =
                            stream.read(
                                    StreamReadArgs
                                            .greaterThan(offset)
                                            .count(100)
                                            .timeout(Duration.ofMillis(1000)));



                    for (Map.Entry<StreamMessageId, Map<String, PubSubMessage>> entry : messages.entrySet()) {
                        StreamMessageId id = entry.getKey();
                        Map<String, PubSubMessage> fields = entry.getValue();
                        Map.Entry<String, PubSubMessage> field = fields.entrySet().iterator().next();
                        String typeString = field.getKey();
                        PubSubMessage msg = field.getValue();
                        PubSubType type = PubSubType.valueOf(typeString.toUpperCase());

// (Optional) Skip messages from same node — disable for single-node testing
                        if (!nodeId.equals(msg.getNodeId())) {
                            PubSubListener<PubSubMessage> ls = listeners.get();
                                try {
                                    ls.onMessage(msg);
                                } catch (Exception ex) {
                                    log.error("Listener error for type {}", type, ex);
                                    continue; // DO NOT ack on failure → retry later
                                }

                        }
                        offset = id;

                    }

                } catch (Exception e) {
                    log.error("Streams worker failure", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }


            }
            log.info("Redis Streams worker stopped");
            // On exit, allow recreation, no zombie
            worker.compareAndSet(Thread.currentThread(), null);
        };

        return createVirtualOrPlatformThread(task, "redis-stream-worker-" + nodeId);
    }

    // =========================================================================
    // THREAD CREATION (Virtual if supported, otherwise platform thread)
    // =========================================================================

    private Thread createVirtualOrPlatformThread(Runnable task, String name) {
        Thread vt = null;
        try {
            // Virtual threads (Java 21+) via reflection: Thread.ofVirtual().name(name).unstarted(task)
            Method ofVirtual = Thread.class.getMethod("ofVirtual");
            Object builder = ofVirtual.invoke(null);
            Method nameMethod = builder.getClass().getMethod("name", String.class);
            Object named = nameMethod.invoke(builder, name);
            Method unstarted = named.getClass().getMethod("unstarted", Runnable.class);
            vt = (Thread) unstarted.invoke(named, task);
            vt.setDaemon(true);
            vt.start();
            return vt;
        } catch (Exception ignore) {
            if (vt != null && vt.isAlive()) {
                vt.interrupt();
            }
            // Fallback for Java 8–20
            Thread t = new Thread(task, name);
            t.setDaemon(true);
            t.start();
            return t;
        }
    }

    // =========================================================================
    // UNSUBSCRIBE
    // =========================================================================

    @Override
    public void unsubscribe(PubSubType type) {
        listeners.set(null);

        Thread w = worker.getAndSet(null);
        if (w != null) {
            w.interrupt();
        }


    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    @Override
    public void shutdown() {
        listeners.set(null);

        Thread w = worker.getAndSet(null);
        if (w != null) {
            w.interrupt();
        }


        try {
            String groupName = CONSUMER_GROUP_BASE + "-" + nodeId;
            RStream<String, PubSubMessage> stream = redisson.getStream(STREAM_NAME);
            stream.removeGroup(groupName);
            log.info("Removed Redis consumer group {}", groupName);
        } catch (Exception e) {
            log.warn("Failed to remove consumer group on shutdown (might not exist): {}", e.getMessage());
        }
    }

}