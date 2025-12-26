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
package com.socketio4j.socketio.integration;

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:18 pm
 */

import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.CustomizedKafkaContainer;
import com.socketio4j.socketio.store.CustomizedRedisContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.kafka.KafkaEventStore;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageDeserializer;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageSerializer;
import com.socketio4j.socketio.store.redis_pubsub.RedissonStoreFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedKafkaMultiChannelTest extends DistributedCommonTest {

    private static final CustomizedKafkaContainer KAFKA =
            new CustomizedKafkaContainer();
    private static final CustomizedRedisContainer REDIS_CONTAINER = new CustomizedRedisContainer().withReuse(false);
    private RedissonClient redisClient1;
    private RedissonClient redisClient2;


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
    private Config redisConfig(String url) {
        Config c = new Config();
        c.useSingleServer().setAddress(url);
        return c;
    }
    @BeforeAll
    public void setup() throws Exception {

        KAFKA.start();
        REDIS_CONTAINER.start();
        String redisURL = "redis://" + REDIS_CONTAINER.getHost() + ":" + REDIS_CONTAINER.getRedisPort();
        redisClient1 = Redisson.create(redisConfig(redisURL));
        redisClient2 = Redisson.create(redisConfig(redisURL));
        String bootstrap = KAFKA.getBootstrapServers();

        // ---------- NODE 1 ----------
        Configuration cfg1 = new Configuration();
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(findAvailablePort());

        cfg1.setStoreFactory(
                new RedissonStoreFactory(redisClient1,
                        kafkaEventStore(bootstrap, "node1")
                )
        );

        node1 = new SocketIOServer(cfg1);
        node1.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);
            c.sendEvent("join-ok", "OK");
        });
        node1.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node1.addEventListener("get-my-rooms", String.class, (client, data, ackSender) -> {
            if (ackSender.isAckRequested()){
                ackSender.sendAckData(client.getAllRooms());
            }
        });
        node1.addConnectListener(client -> {

            Map<String, List<String>> params =
                    client.getHandshakeData().getUrlParams();

            List<String> joinParams = params.get("join");
            if (joinParams == null || joinParams.isEmpty()) {
                return;
            }

            // Convert to Set to avoid duplicates
            Set<String> rooms = joinParams.stream()
                    .flatMap(v -> Arrays.stream(v.split(","))) // supports join=a,b
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            rooms.forEach(client::joinRoom);
        });
        node1.start();
        port1 = cfg1.getPort();

        // ---------- NODE 2 ----------
        Configuration cfg2 = new Configuration();
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(findAvailablePort());

        cfg2.setStoreFactory(
                new RedissonStoreFactory(redisClient2,
                        kafkaEventStore(bootstrap, "node2")
                )
        );

        node2 = new SocketIOServer(cfg2);
        node2.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);

            c.sendEvent("join-ok", "OK");
        });
        node2.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node2.addEventListener("get-my-rooms", String.class, (client, data, ackSender) ->{
            if (ackSender.isAckRequested()){
                ackSender.sendAckData(client.getAllRooms());
            }
        });
        node2.addConnectListener(client -> {

            Map<String, List<String>> params =
                    client.getHandshakeData().getUrlParams();

            List<String> joinParams = params.get("join");
            if (joinParams == null || joinParams.isEmpty()) {
                return;
            }

            // Convert to Set to avoid duplicates
            Set<String> rooms = joinParams.stream()
                    .flatMap(v -> Arrays.stream(v.split(","))) // supports join=a,b
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            rooms.forEach(client::joinRoom);
        });
        node2.start();
        port2 = cfg2.getPort();
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
            KAFKA.close();
        }
        if (REDIS_CONTAINER!=null){
            REDIS_CONTAINER.stop();
        }
    }
}
