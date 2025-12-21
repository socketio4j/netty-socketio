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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.WakeupException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.*;
import com.socketio4j.socketio.store.event.EventListener;

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
        this.nodeId = nodeId;
        if (mode == null) {
           mode =  EventStoreMode.MULTI_CHANNEL;
        }
        this.mode = mode;
        if (topicPrefix == null || topicPrefix.isEmpty()) {
            topicPrefix = "SOCKETIO4J-";
        }
        this.topicPrefix = topicPrefix;
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

    private KafkaConsumer<String, EventMessage> createConsumer(EventType type) {

        Properties props = new Properties();
        props.putAll(consumerProps);

        // IMPORTANT: No group.id
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);

        KafkaConsumer<String, EventMessage> consumer =
                new KafkaConsumer<>(props);

        String topic = topic(resolve(type));

        // Discover partitions
        List<PartitionInfo> infos =
                consumer.partitionsFor(topic);

        if (infos == null || infos.isEmpty()) {
            throw new IllegalStateException(
                    "No partitions found for topic " + topic);
        }

        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : infos) {
            partitions.add(new TopicPartition(topic, info.partition()));
        }

        // Assign directly (XREAD-style)
        consumer.assign(partitions);

        // Start from newest (like StreamMessageId.NEWEST)
        consumer.seekToEnd(partitions);

        return consumer;
    }

    // ---------------------------------------------------------------------
    // Poll loop (never blocks Netty)
    // ---------------------------------------------------------------------

    private void pollLoop(EventType type,
                          KafkaConsumer<String, EventMessage> consumer) {

        try {
            while (running.get()) {
                try {
                    ConsumerRecords<String, EventMessage> records =
                            consumer.poll(Duration.ofSeconds(1));

                    for (ConsumerRecord<String, EventMessage> rec : records) {

                        EventMessage msg = rec.value();
                        if (msg == null || nodeId.equals(msg.getNodeId())) {
                            continue;
                        }

                        msg.setOffset(
                                rec.topic() + ":" +
                                        rec.partition() + ":" +
                                        rec.offset()
                        );

                        dispatch(type, msg);
                    }

                } catch (RecordDeserializationException e) {

                    TopicPartition tp = e.topicPartition();
                    long badOffset = e.offset();

                    log.error(
                            "Deserialization failed at {} offset {}, skipping",
                            tp, badOffset, e
                    );

                    // Skip poison pill (Redis-style)
                    consumer.seek(tp, badOffset + 1);

                    // Continue loop → next poll()
                } catch (WakeupException e) {
                    // Expected during shutdown
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

        ExecutorService exec = pollers.remove(type);
        if (exec != null) {
            exec.shutdownNow();
        }

        KafkaConsumer<?, ?> consumer = consumers.remove(type);
        if (consumer != null) {
            consumer.wakeup();
        }
    }

    @Override
    public void shutdown0() {

        running.set(false);

        pollers.values().forEach(ExecutorService::shutdownNow);
        pollers.clear();

        consumers.values().forEach(KafkaConsumer::wakeup);
        consumers.clear();

        listeners.clear();

        producer.flush();
        producer.close();
    }

    // ---------------------------------------------------------------------
    // Utils
    // ---------------------------------------------------------------------

    private String topic(EventType type) {
        return topicPrefix + type.name();
    }

    private EventType resolve(EventType type) {
        return mode == EventStoreMode.SINGLE_CHANNEL
                ? EventType.ALL_SINGLE_CHANNEL
                : type;
    }

    private void validateSubscribe(EventType type) {

        if (mode == EventStoreMode.SINGLE_CHANNEL &&
                type != EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "Only ALL_SINGLE_CHANNEL allowed");
        }

        if (mode == EventStoreMode.MULTI_CHANNEL &&
                type == EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "ALL_SINGLE_CHANNEL not allowed");
        }
    }
}
