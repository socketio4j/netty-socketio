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
package com.socketio4j.socketio.store;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.socketio4j.socketio.store.event.EventType;

public class CustomizedKafkaContainer extends KafkaContainer {

    private static final Logger log =
            LoggerFactory.getLogger(CustomizedKafkaContainer.class);

    private static final List<String> TOPICS =
            Arrays.stream(EventType.values())
                    .map(e -> "SOCKETIO4J-" + e.name()).collect(Collectors.toList());

    public CustomizedKafkaContainer() {
        super(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

        withReuse(false);
        withStartupAttempts(3);
        withStartupTimeout(Duration.ofMinutes(2));
    }

    @Override
    public void start() {
        super.start();

        waitUntilKafkaReady();
        createTopics();

        log.info("Kafka ready at {}", getBootstrapServers());
    }

    /**
     * CI-safe broker readiness check using AdminClient
     */
    private void waitUntilKafkaReady() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        HashMap<String, Object> conf = new HashMap<>();
        conf.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                getBootstrapServers());
        while (true) {
            try (AdminClient admin = AdminClient.create(conf)) {

                admin.describeCluster()
                        .clusterId()
                        .get(5, TimeUnit.SECONDS);

                return; // broker is ready

            } catch (Exception e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new RuntimeException(
                            "Kafka broker not ready after 60s", e);
                }
                sleep(1000);
            }
        }
    }

    /**
     * CI-safe topic creation (idempotent)
     */
    private void createTopics() {
        HashMap<String, Object> conf = new HashMap<>();
        conf.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                getBootstrapServers());
        try (AdminClient admin = AdminClient.create(conf)) {

            List<NewTopic> topics =
                    TOPICS.stream()
                            .map(t -> new NewTopic(t, 1, (short) 1))
                                    .collect(Collectors.toList());

            admin.createTopics(topics)
                    .all()
                    .get(30, TimeUnit.SECONDS);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create Kafka topics", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for Kafka", ie);
        }
    }
}
