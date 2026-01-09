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
package com.socketio4j.socketio.store.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.WakeupException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.ListenerRegistration;

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:09 pm
 */

public final class KafkaEventStore implements EventStore {

    private static final Logger log =
            LoggerFactory.getLogger(KafkaEventStore.class);

    // ---------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------

    private final KafkaProducer<String, EventMessage> producer;
    private final Properties consumerProps;
    private final String topicPrefix;
    private final Long nodeId;
    private final EventStoreMode mode;

    // ---------------------------------------------------------------------
    // Runtime
    // ---------------------------------------------------------------------

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final ConcurrentMap<EventType, KafkaConsumer<String, EventMessage>> consumers =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<EventType, ExecutorService> pollers =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<EventType, Queue<ListenerRegistration<? extends EventMessage>>> listeners =
            new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------

    public KafkaEventStore(
            @NotNull KafkaProducer<String, EventMessage> producer,
            @NotNull Properties consumerProps,
            @Nullable Long nodeId,
            @Nullable EventStoreMode mode,
            @Nullable String topicPrefix
    ) {

        this.producer = Objects.requireNonNull(producer);
        this.consumerProps = Objects.requireNonNull(consumerProps);

        if (nodeId == null){
            nodeId = getNodeId();
        }
        this.nodeId = Objects.requireNonNull(nodeId);
        if (mode == null) {
           mode =  EventStoreMode.MULTI_CHANNEL;
        }
        this.mode = Objects.requireNonNull(mode);
        if (topicPrefix == null || topicPrefix.isEmpty()) {
            topicPrefix = "SOCKETIO4J-";
        }
        this.topicPrefix = Objects.requireNonNull(topicPrefix);
    }

    // ---------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------

    @Override
    public EventStoreMode getEventStoreMode() {
        return mode;
    }

    @Override
    public EventStoreType getEventStoreType() {
        return EventStoreType.STREAM;
    }

    // ---------------------------------------------------------------------
    // Publish (async, non-blocking)
    // ---------------------------------------------------------------------

    /**
     * Publishes an event message to Kafka asynchronously.
     * 
     * <p>Design decisions:
     * <ul>
     *   <li><b>Async non-blocking</b>: Uses {@code producer.send()} with a callback to avoid
     *       blocking the Netty event loop. Failures are logged but don't throw exceptions.</li>
     *   <li><b>Node ID filtering</b>: Sets the nodeId on the message so consumers can filter out
     *       messages from their own node, preventing duplicate processing.</li>
     *   <li><b>Topic resolution</b>: In SINGLE_CHANNEL mode, all events go to one topic;
     *       in MULTI_CHANNEL mode, each event type has its own topic.</li>
     * </ul>
     * 
     * @param type the event type
     * @param msg the message to publish
     */
    @Override
    public void publish0(EventType type, EventMessage msg) {

        msg.setNodeId(nodeId);

        String topic = topic(resolve(type));
        ProducerRecord<String, EventMessage> record =
                new ProducerRecord<>(topic, type.name(), msg);

        producer.send(record, (metadata, ex) -> {
            if (ex != null) {
                log.error("Kafka publish failed {}", type, ex);
            } else {
                log.debug(
                        "Kafka published topic={} partition={} offset={}",
                        metadata.topic(),
                        metadata.partition(),
                        metadata.offset()
                );
            }
        });
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

        listeners
                .computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>())
                .add(new ListenerRegistration<>(listener, clazz));

        ensureConsumer(type);
    }

    private void ensureConsumer(EventType type) {

        pollers.compute(type, (t, exec) -> {
            if (exec == null || exec.isShutdown()) {

                ExecutorService executor =
                        Executors.newSingleThreadExecutor(r -> {
                            Thread th = new Thread(r);
                            th.setDaemon(true);
                            th.setName("socketio4j-kafka-" + t.name());
                            return th;
                        });

                executor.execute(() -> {
                    KafkaConsumer<String, EventMessage> consumer =
                            createConsumer(t);
                    consumers.put(t, consumer);
                    pollLoop(t, consumer);
                });

                return executor;
            }
            return exec;
        });
    }

    /**
     * Creates a Kafka consumer for the specified event type.
     * 
     * <p>Design decisions:
     * <ul>
     *   <li><b>No consumer group</b>: Uses {@code assign()} instead of {@code subscribe()} to directly
     *       assign partitions. This ensures each node consumes all messages (broadcast mode), similar
     *       to Redis Stream's XREAD pattern. Consumer groups would cause messages to be consumed by only
     *       one node, breaking distributed synchronization.</li>
     *   <li><b>Start from newest</b>: Uses {@code seekToEnd()} to start consuming from the latest offset,
     *       ignoring historical messages. This matches Redis Stream's NEWEST behavior and ensures nodes
     *       only process new events after startup.</li>
     *   <li><b>Topic auto-creation</b>: Retries partition discovery to handle cases where topics are
     *       auto-created by Kafka (when auto.create.topics.enable=true).</li>
     * </ul>
     * 
     * @param type the event type to create a consumer for
     * @return a configured Kafka consumer
     * @throws IllegalStateException if topic partitions cannot be discovered after retries
     */
    private KafkaConsumer<String, EventMessage> createConsumer(EventType type) {

        Properties props = new Properties();
        props.putAll(consumerProps);

        // IMPORTANT: Remove group.id to use direct partition assignment (XREAD-style)
        // Consumer groups would cause messages to be consumed by only one node,
        // but we need all nodes to receive all messages for distributed synchronization
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);

        KafkaConsumer<String, EventMessage> consumer =
                new KafkaConsumer<>(props);

        String topic = topic(resolve(type));

        // Discover partitions with retry logic
        // Topics may be auto-created by Kafka, so we retry a few times
        List<PartitionInfo> infos = null;
        int maxRetries = 5;
        long retryDelayMs = 500;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            infos = consumer.partitionsFor(topic);
            if (infos != null && !infos.isEmpty()) {
                break;
            }
            
            if (attempt < maxRetries) {
                log.debug(
                    "Topic {} not found (attempt {}/{}), retrying in {}ms...",
                    topic, attempt, maxRetries, retryDelayMs
                );
                try {
                    Thread.sleep(retryDelayMs);
                    retryDelayMs *= 2; // Exponential backoff
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                        "Interrupted while waiting for topic " + topic + " to be available"
                    );
                }
            }
        }

        if (infos == null || infos.isEmpty()) {
            throw new IllegalStateException(
                "No partitions found for topic " + topic + 
                " after " + maxRetries + " attempts. " +
                "Please ensure the topic exists or enable auto-creation (auto.create.topics.enable=true)"
            );
        }

        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : infos) {
            partitions.add(new TopicPartition(topic, info.partition()));
        }

        // Assign directly (XREAD-style) - all nodes consume all messages
        consumer.assign(partitions);

        // Start from newest (like StreamMessageId.NEWEST) - ignore historical messages
        consumer.seekToEnd(partitions);

        log.debug("Created consumer for topic {} with {} partitions", topic, partitions.size());
        return consumer;
    }

    // ---------------------------------------------------------------------
    // Poll loop (never blocks Netty)
    // ---------------------------------------------------------------------

    /**
     * Main polling loop for consuming messages from Kafka.
     * 
     * <p>Design decisions:
     * <ul>
     *   <li><b>Runs in separate thread</b>: Each event type has its own daemon thread to avoid
     *       blocking the Netty event loop. Threads are named for easy debugging.</li>
     *   <li><b>Node ID filtering</b>: Skips messages from the same node to prevent duplicate
     *       processing. Local operations are already applied, so we only need to process
     *       messages from other nodes.</li>
     *   <li><b>Poison pill handling</b>: When deserialization fails, we skip the bad message
     *       and continue (similar to Redis Stream's behavior). This prevents a single corrupted
     *       message from blocking the entire consumer.</li>
     *   <li><b>Short poll timeout</b>: 1 second timeout ensures the loop can check the
     *       running flag frequently for graceful shutdown.</li>
     * </ul>
     * 
     * @param type the event type being consumed
     * @param consumer the Kafka consumer instance
     */
    private void pollLoop(EventType type,
                          KafkaConsumer<String, EventMessage> consumer) {

        try {
            while (running.get()) {
                try {
                    ConsumerRecords<String, EventMessage> records =
                            consumer.poll(Duration.ofSeconds(1));

                    for (ConsumerRecord<String, EventMessage> rec : records) {

                        EventMessage msg = rec.value();
                        // Skip null messages and messages from this node (already processed locally)
                        if (msg == null || nodeId.equals(msg.getNodeId())) {
                            continue;
                        }

                        // Set offset for potential message replay or debugging
                        msg.setOffset(
                                rec.topic() + ":" +
                                        rec.partition() + ":" +
                                        rec.offset()
                        );

                        dispatch(type, msg);
                    }

                } catch (RecordDeserializationException e) {
                    // Handle poison pill messages (corrupted/unparseable data)
                    TopicPartition tp = e.topicPartition();
                    long badOffset = e.offset();

                    log.error(
                            "Deserialization failed at {} offset {}, skipping",
                            tp, badOffset, e
                    );

                    // Skip the bad message and continue (Redis Stream-style)
                    consumer.seek(tp, badOffset + 1);

                    // Continue loop → next poll()
                } catch (WakeupException e) {
                    // Expected during shutdown - consumer.wakeup() was called
                    break;
                }
            }
        } catch (Exception t) {
            log.error("Kafka poll loop crashed {}", type, t);
        } finally {
            try {
                consumer.close();
            } catch (Exception e) {
                log.warn("Error closing Kafka consumer {}", type, e);
            }
        }
    }


    // ---------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------


    private <T extends EventMessage> void dispatch(
            EventType type,
            EventMessage msg
    ) {

        Queue<ListenerRegistration<? extends EventMessage>> regs =
                listeners.get(type);

        if (regs == null) {
            return;
        }

        for (ListenerRegistration<? extends EventMessage> reg : regs) {
            if (reg.getClazz().isInstance(msg)) {
                ((ListenerRegistration<T>) reg)
                        .getListener()
                        .onMessage((T) msg);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Unsubscribe / Shutdown
    // ---------------------------------------------------------------------

    @Override
    public void unsubscribe0(EventType type) {

        listeners.remove(type);

        KafkaConsumer<?, ?> consumer = consumers.remove(type);
        if (consumer != null) {
            consumer.wakeup(); // primary shutdown signal
        }

        ExecutorService exec = pollers.remove(type);
        if (exec != null) {
            exec.shutdown(); // graceful

            if (exec.isTerminated()) {
                log.info("exec {} terminated", type);
            }
        }
    }


    @Override
    public void shutdown0() {
        running.set(false);

        listeners.clear();

        consumers.values().forEach(KafkaConsumer::wakeup);
        consumers.clear();

        pollers.values().forEach(ExecutorService::shutdownNow);
        pollers.clear();

        try {
            producer.flush();
        } finally {
            producer.close();
        }
    }


    // ---------------------------------------------------------------------
    // Utils
    // ---------------------------------------------------------------------

    private String topic(EventType type) {
        return topicPrefix + type.name();
    }

    /**
     * Resolves the event type based on the store mode.
     * 
     * <p>In SINGLE_CHANNEL mode, all events are routed to a single topic (ALL_SINGLE_CHANNEL).
     * This ensures event ordering across all event types but requires all nodes to process
     * all events.
     * 
     * <p>In MULTI_CHANNEL mode, each event type has its own topic, allowing independent
     * scaling and processing of different event types.
     * 
     * @param type the original event type
     * @return the resolved event type (may be ALL_SINGLE_CHANNEL in single channel mode)
     */
    private EventType resolve(EventType type) {
        if (mode == EventStoreMode.SINGLE_CHANNEL) {
                return EventType.ALL_SINGLE_CHANNEL;
        }
        return type;
    }

    /**
     * Validates that the subscription request is compatible with the current store mode.
     * 
     * @param type the event type to subscribe to
     * @throws UnsupportedOperationException if the subscription is invalid for the current mode
     */
    private void validateSubscribe(EventType type) {

        if (mode == EventStoreMode.SINGLE_CHANNEL && type != EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "Only ALL_SINGLE_CHANNEL allowed in SINGLE_CHANNEL mode");
        }

        if (mode == EventStoreMode.MULTI_CHANNEL && type == EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "ALL_SINGLE_CHANNEL not allowed in MULTI_CHANNEL mode");
        }
    }
}
