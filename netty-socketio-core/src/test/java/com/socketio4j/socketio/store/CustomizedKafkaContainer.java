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
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.socketio4j.socketio.store.event.EventType;

/**
 * Customized Kafka container for SocketIO4j using modern Testcontainers Kafka module.
 * Ensures CI-safe startup, topic creation, and readiness checks.
 */
public class CustomizedKafkaContainer extends ConfluentKafkaContainer {

    private static final Logger log = LoggerFactory.getLogger(CustomizedKafkaContainer.class);

    private static final List<String> TOPICS = Arrays.stream(EventType.values())
            .map(e -> "SOCKETIO4J-" + e.name())
            .collect(Collectors.toList());

    public CustomizedKafkaContainer() {
        super(DockerImageName.parse("confluentinc/cp-kafka:7.7.7"));
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

    private void waitUntilKafkaReady() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        HashMap<String, Object> conf = new HashMap<>();
        conf.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

        while (true) {
            try (AdminClient admin = AdminClient.create(conf)) {
                admin.describeCluster().clusterId().get(5, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new RuntimeException("Kafka broker not ready after 60s", e);
                }
                sleep(1000);
            }
        }
    }

    private void createTopics() {
        HashMap<String, Object> conf = new HashMap<>();
        conf.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

        try (AdminClient admin = AdminClient.create(conf)) {
            List<NewTopic> topics = TOPICS.stream()
                    .map(t -> new NewTopic(t, 1, (short) 1))
                    .collect(Collectors.toList());

            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
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