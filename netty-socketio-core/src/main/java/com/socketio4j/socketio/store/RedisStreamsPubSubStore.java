
package com.socketio4j.socketio.store;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.pubsub.PubSubListener;
import com.socketio4j.socketio.store.pubsub.PubSubMessage;
import com.socketio4j.socketio.store.pubsub.PubSubStore;
import com.socketio4j.socketio.store.pubsub.PubSubType;

public class RedisStreamsPubSubStore implements PubSubStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsPubSubStore.class);

    /** Global Redis Stream key */
    private static final String STREAM_NAME = "socketio";

    /** Base name; real consumer group is consumerGroupBase + "-" + nodeId */
    private static final String CONSUMER_GROUP_BASE = "socketio";

    private final Long nodeId;
    private final RedissonClient redisson;

    // type -> list of listeners
    private final ConcurrentMap<PubSubType, List<PubSubListener<?>>> listeners = new ConcurrentHashMap<>();

    // worker thread for the only stream
    private Thread worker;

    public RedisStreamsPubSubStore(Long nodeId, RedissonClient redisson) {
        this.nodeId = nodeId;
        this.redisson = redisson;
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
        log.trace("subscribe type {}, listener {}", type, listener);
        listeners
                .computeIfAbsent(type, t -> new CopyOnWriteArrayList<>())
                .add(listener);

        // Start worker once
        if (worker == null) {
            log.debug("worker is null");
            worker = startWorker();
        } else {
            log.debug("worker is already started");
        }

    }

    // =========================================================================
    // WORKER LOOP (ONE for all types)
    // =========================================================================

    private Thread startWorker() {
log.debug("starting worker thread");
        final String groupName = CONSUMER_GROUP_BASE + "-" + nodeId;
        final String consumerName = nodeId.toString();
        final RStream<String, PubSubMessage> stream = redisson.getStream(STREAM_NAME);

        try {
            // Create group if missing; mkStream=true creates empty stream if none exists
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
        } catch (Exception ignore) {
            // group already exists
        }
        log.debug("Subscribing to group {}", groupName);
        Runnable task = () -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Map<StreamMessageId, Map<String, PubSubMessage>> messages =
                            stream.readGroup(
                                    groupName,
                                    consumerName,
                                    StreamReadGroupArgs.neverDelivered()
                                            .count(20)
                                            .timeout(Duration.ofSeconds(5)));

                    if (messages == null || messages.isEmpty()) {
                        log.debug("No messages found for group {}", groupName);
                        continue;
                    } else {
                        log.debug("Found {} messages for group {}", messages.size(), groupName);
                    }

                    for (Map.Entry<StreamMessageId, Map<String, PubSubMessage>> entry : messages.entrySet()) {
                        StreamMessageId id = entry.getKey();
                        Map<String, PubSubMessage> fields = entry.getValue();
// Each stream entry contains exactly 1 field: {"TYPE": msg}
                        Map.Entry<String, PubSubMessage> field = fields.entrySet().iterator().next();
                        String typeString = field.getKey();
                        PubSubMessage msg = field.getValue();
                        PubSubType type = PubSubType.valueOf(typeString.toUpperCase());

// (Optional) Skip messages from same node — disable for single-node testing
                        if (!nodeId.equals(msg.getNodeId())) {
                            List<PubSubListener<?>> ls = listeners.getOrDefault(type, Collections.emptyList());
                            for (PubSubListener<?> raw : ls) {
                                @SuppressWarnings("unchecked")
                                PubSubListener<PubSubMessage> listener =
                                        (PubSubListener<PubSubMessage>) raw;
                                try {
                                    listener.onMessage(msg);
                                } catch (Exception ex) {
                                    log.error("Listener error for type {}", type, ex);
                                    continue; // DO NOT ack on failure → retry later
                                }
                            }
                        }

// ACK only after successful dispatch
                        stream.ack(groupName, id);
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
        };

        return createVirtualOrPlatformThread(task, "redis-stream-worker-" + nodeId);
    }

    // =========================================================================
    // THREAD CREATION (Virtual if supported, otherwise platform thread)
    // =========================================================================

    private Thread createVirtualOrPlatformThread(Runnable task, String name) {
        try {
            // Virtual threads (Java 21+) via reflection: Thread.ofVirtual().name(name).unstarted(task)
            Method ofVirtual = Thread.class.getMethod("ofVirtual");
            Object builder = ofVirtual.invoke(null);
            Method nameMethod = builder.getClass().getMethod("name", String.class);
            Object named = nameMethod.invoke(builder, name);
            Method unstarted = named.getClass().getMethod("unstarted", Runnable.class);
            Thread vt = (Thread) unstarted.invoke(named, task);
            vt.setDaemon(true);
            vt.start();
            return vt;
        } catch (Exception ignore) {
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
        listeners.remove(type);

        // stop worker if no listeners remain
        if (listeners.isEmpty() && worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    @Override
    public void shutdown() {
        listeners.clear();

        if (worker != null) {
            worker.interrupt();
            worker = null;
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
