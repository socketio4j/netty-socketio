package com.socketio4j.socketio.integration;

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:18 pm
 */

import java.net.ServerSocket;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.CustomizedKafkaContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.kafka.KafkaEventStore;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageDeserializer;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageSerializer;
import com.socketio4j.socketio.store.redis_pubsub.RedissonStoreFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class


DistributedKafkaMultiChannelTest extends DistributedCommonTest {

    private static final CustomizedKafkaContainer KAFKA =
            new CustomizedKafkaContainer();

    // -------------------------------------------
    // Utility
    // -------------------------------------------

    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // -------------------------------------------
    // Setup
    // -------------------------------------------

    @BeforeAll
    public void setup() throws Exception {

        KAFKA.start();
        String bootstrap = KAFKA.getBootstrapServers();

        // ---------- NODE 1 ----------
        Configuration cfg1 = new Configuration();
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(findAvailablePort());

        cfg1.setStoreFactory(
                new RedissonStoreFactory(Redisson.create(),
                        kafkaEventStore(bootstrap, "node1")
                )
        );

        node1 = new SocketIOServer(cfg1);
        registerRoomHandlers(node1);
        node1.start();
        port1 = cfg1.getPort();

        // ---------- NODE 2 ----------
        Configuration cfg2 = new Configuration();
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(findAvailablePort());

        cfg2.setStoreFactory(
                new RedissonStoreFactory(Redisson.create(),
                        kafkaEventStore(bootstrap, "node2")
                )
        );

        node2 = new SocketIOServer(cfg2);
        registerRoomHandlers(node2);
        node2.start();
        port2 = cfg2.getPort();
    }

    // -------------------------------------------
    // Helpers
    // -------------------------------------------

    private void registerRoomHandlers(SocketIOServer server) {

        server.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);
            c.sendEvent("join-ok", "OK");
        });

        server.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
    }

    private KafkaEventStore kafkaEventStore(String bootstrap, String groupId) {

        Properties producerProps = new Properties();
        producerProps.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(
                ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProps.put(
                ProducerConfig.LINGER_MS_CONFIG, 5);
        producerProps.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        producerProps.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                EventMessageSerializer.class);

        Properties consumerProps = new Properties();
        consumerProps.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerProps.put(
                ConsumerConfig.GROUP_ID_CONFIG, "socketio4j-" + groupId);
        consumerProps.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumerProps.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                EventMessageDeserializer.class);

        return new KafkaEventStore(
                new KafkaProducer<>(producerProps),
                consumerProps,
                null,
                EventStoreMode.MULTI_CHANNEL,
                "SOCKETIO4J-"
        );
    }

    // -------------------------------------------
    // Teardown
    // -------------------------------------------

    @AfterAll
    public void stop() {

        if (node1 != null) {
            node1.stop();
        }
        if (node2 != null) {
            node2.stop();
        }
        if (KAFKA != null) {
            KAFKA.stop();
        }
    }
}
