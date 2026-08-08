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
package com.socketio4j.socketio.integration.cluster;

import com.socketio4j.socketio.TestResourceCleanup;
import com.socketio4j.socketio.integration.cluster.DistributedCommonTest;
import com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport;
import static com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport.*;

import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.container.CustomizedKafkaContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.kafka.KafkaEventStore;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageDeserializer;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageSerializer;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;

import static com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport.findAvailablePort;

/**
 * Runs {@link DistributedCommonTest} against all Kafka-backed cluster variants while sharing
 * one Kafka Testcontainer for maximum execution speed and zero container setup overhead.
 */
@ResourceLock("EMBEDDED_KAFKA")
public class DistributedKafkaClusterTest {

    @SuppressWarnings("resource")
    static final CustomizedKafkaContainer KAFKA = new CustomizedKafkaContainer();

    @BeforeAll
    static void startKafka() {
        if (!KAFKA.isRunning()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    KAFKA.start();
                    break;
                } catch (Exception e) {
                    if (attempt == 3) throw new RuntimeException("Failed to start Kafka container", e);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while starting Kafka test container", error);
                    }
                }
            }
        }
    }

    @AfterAll
    static void stopKafka() {
        TestResourceCleanup.runAll("Kafka test container cleanup",
                () -> { if (KAFKA != null && KAFKA.isRunning()) KAFKA.close(); });
    }

    private static KafkaEventStore createKafkaEventStore(String bootstrap, String groupId, EventStoreMode mode) {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, EventMessageSerializer.class.getName());

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        String uniqueGroupId = "socketio4j-" + groupId + "-" + UUID.randomUUID();
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, uniqueGroupId);
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventMessageDeserializer.class);

        return new KafkaEventStore(
                new KafkaProducer<>(producerProps),
                consumerProps,
                null,
                mode,
                "SOCKETIO4J-"
        );
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SingleChannelMemoryTest extends DistributedCommonTest {
        private KafkaEventStore kafkaEventStore1;
        private KafkaEventStore kafkaEventStore2;

        @BeforeAll
        void setupNodes() throws Exception {
            String bootstrap = KAFKA.getBootstrapServers();

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            kafkaEventStore1 = createKafkaEventStore(bootstrap, "single-channel-mem-node1", EventStoreMode.SINGLE_CHANNEL);
            cfg1.setStoreFactory(new MemoryStoreFactory(kafkaEventStore1));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            kafkaEventStore2 = createKafkaEventStore(bootstrap, "single-channel-mem-node2", EventStoreMode.SINGLE_CHANNEL);
            cfg2.setStoreFactory(new MemoryStoreFactory(kafkaEventStore2));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("Kafka pub/sub cluster cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    () -> { if (kafkaEventStore1 != null) kafkaEventStore1.shutdown(); },
                    () -> { if (kafkaEventStore2 != null) kafkaEventStore2.shutdown(); });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MultiChannelMemoryTest extends DistributedCommonTest {
        private KafkaEventStore store1;
        private KafkaEventStore store2;

        @BeforeAll
        void setupNodes() throws Exception {
            String bootstrap = KAFKA.getBootstrapServers();

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            store1 = createKafkaEventStore(bootstrap, "multi-channel-mem-node1", EventStoreMode.MULTI_CHANNEL);
            cfg1.setStoreFactory(new MemoryStoreFactory(store1));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            store2 = createKafkaEventStore(bootstrap, "multi-channel-mem-node2", EventStoreMode.MULTI_CHANNEL);
            cfg2.setStoreFactory(new MemoryStoreFactory(store2));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("Kafka single-topic cluster cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    () -> { if (store1 != null) store1.shutdown(); },
                    () -> { if (store2 != null) store2.shutdown(); });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SingleChannelStoreTest extends DistributedCommonTest {
        private KafkaEventStore store1;
        private KafkaEventStore store2;

        @BeforeAll
        void setupNodes() throws Exception {
            String bootstrap = KAFKA.getBootstrapServers();

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            store1 = createKafkaEventStore(bootstrap, "single-channel-store-node1", EventStoreMode.SINGLE_CHANNEL);
            cfg1.setStoreFactory(new MemoryStoreFactory(store1));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            store2 = createKafkaEventStore(bootstrap, "single-channel-store-node2", EventStoreMode.SINGLE_CHANNEL);
            cfg2.setStoreFactory(new MemoryStoreFactory(store2));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("Kafka multi-topic cluster cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    () -> { if (store1 != null) store1.shutdown(); },
                    () -> { if (store2 != null) store2.shutdown(); });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MultiChannelStoreTest extends DistributedCommonTest {
        private KafkaEventStore store1;
        private KafkaEventStore store2;

        @BeforeAll
        void setupNodes() throws Exception {
            String bootstrap = KAFKA.getBootstrapServers();

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            store1 = createKafkaEventStore(bootstrap, "multi-channel-store-node1", EventStoreMode.MULTI_CHANNEL);
            cfg1.setStoreFactory(new MemoryStoreFactory(store1));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            store2 = createKafkaEventStore(bootstrap, "multi-channel-store-node2", EventStoreMode.MULTI_CHANNEL);
            cfg2.setStoreFactory(new MemoryStoreFactory(store2));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("Kafka reliable pub/sub cluster cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    () -> { if (store1 != null) store1.shutdown(); },
                    () -> { if (store2 != null) store2.shutdown(); });
        }
    }
}
