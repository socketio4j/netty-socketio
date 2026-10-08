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
package com.socketio4j.socketio.integration.interop;

import com.socketio4j.socketio.TestResourceCleanup;
import com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport;
import static com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport.*;


import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;


import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.integration.interop.AbstractDistributedJsClientInteropTest;
import com.socketio4j.socketio.store.container.CustomizedKafkaContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.kafka.KafkaEventStore;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageDeserializer;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageSerializer;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Multi-Node JS Client Interoperability Test Suite backed by Kafka.
 */
@ResourceLock("EMBEDDED_KAFKA")
@DisplayName("Multi-Node Official JS Client Interoperability Suite (Kafka)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedKafkaJsClientInteropTest extends AbstractDistributedJsClientInteropTest {

    private static final CustomizedKafkaContainer KAFKA = new CustomizedKafkaContainer();
    private KafkaEventStore kafkaEventStore1;
    private KafkaEventStore kafkaEventStore2;

    @BeforeAll
    @Override
    public void setupCluster() throws Exception {
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
        String bootstrap = KAFKA.getBootstrapServers();

        // Server 1
        Configuration cfg1 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        kafkaEventStore1 = kafkaEventStore(bootstrap, "node1");
        cfg1.setStoreFactory(new MemoryStoreFactory(kafkaEventStore1));
        node1 = new SocketIOServer(cfg1);
        attachDefaultRoomListeners(node1);
        node1.start();
        port1 = cfg1.getPort();

        // Server 2
        Configuration cfg2 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        kafkaEventStore2 = kafkaEventStore(bootstrap, "node2");
        cfg2.setStoreFactory(new MemoryStoreFactory(kafkaEventStore2));
        node2 = new SocketIOServer(cfg2);
        attachDefaultRoomListeners(node2);
        node2.start();
        port2 = cfg2.getPort();

        initJsScript();
    }

    private KafkaEventStore kafkaEventStore(String bootstrap, String groupId) {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, EventMessageSerializer.class.getName());

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        String uniqueGroupId = "socketio4j-interop-" + groupId + "-" + UUID.randomUUID();
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, uniqueGroupId);
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventMessageDeserializer.class.getName());

        return new KafkaEventStore(
                new KafkaProducer<>(producerProps),
                consumerProps,
                null,
                EventStoreMode.MULTI_CHANNEL,
                "SOCKETIO4J-INTEROP-"
        );
    }

    @AfterAll
    @Override
    public void teardownCluster() {
        TestResourceCleanup.runAll("Kafka distributed interop cleanup",
                () -> { if (node1 != null) node1.stop(); },
                () -> { if (node2 != null) node2.stop(); },
                () -> { if (kafkaEventStore1 != null) kafkaEventStore1.shutdown(); },
                () -> { if (kafkaEventStore2 != null) kafkaEventStore2.shutdown(); },
                () -> { if (KAFKA != null && KAFKA.isRunning()) KAFKA.close(); });
    }
}
