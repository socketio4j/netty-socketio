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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.CustomizedRedisContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.redis_pubsub.RedisPubSubEventStore;
import com.socketio4j.socketio.store.redis_pubsub.RedisStoreFactory;
import com.socketio4j.socketio.store.redis_reliable.RedisPubSubReliableEventStore;
import com.socketio4j.socketio.store.redis_stream.RedisStreamEventStore;

import static com.socketio4j.socketio.integration.DistributedClusterIntegrationSupport.findAvailablePort;

/**
 * Runs {@link DistributedCommonTest} against all Redisson-backed cluster variants while sharing
 * one Redis Testcontainer.
 */
public class DistributedRedissonClusterSuite {

    @SuppressWarnings("resource")
    static final CustomizedRedisContainer REDIS = new CustomizedRedisContainer().withReuse(false);

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    private static String redisUrl() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getRedisPort();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PubSubSingleChannelUnreliable extends DistributedCommonTest {
        private RedissonClient redisClient1;
        private RedissonClient redisClient2;

        @BeforeAll
        void setupNodes() throws Exception {
            String url = redisUrl();
            redisClient1 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            redisClient2 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new RedisStoreFactory(redisClient1,
                    new RedisPubSubEventStore.Builder(redisClient1).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()));
            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();
            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new RedisStoreFactory(redisClient2,
                    new RedisPubSubEventStore.Builder(redisClient2).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()));
            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            if (node1 != null) {
                node1.stop();
            }
            if (node2 != null) {
                node2.stop();
            }
            if (redisClient1 != null) {
                redisClient1.shutdown();
            }
            if (redisClient2 != null) {
                redisClient2.shutdown();
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PubSubMultiChannelUnreliable extends DistributedCommonTest {
        private RedissonClient redisClient1;
        private RedissonClient redisClient2;

        @BeforeAll
        void setupNodes() throws Exception {
            String url = redisUrl();
            redisClient1 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            redisClient2 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new RedisStoreFactory(redisClient1,
                    new RedisPubSubEventStore.Builder(redisClient1).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()));
            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();
            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new RedisStoreFactory(redisClient2,
                    new RedisPubSubEventStore.Builder(redisClient2).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()));
            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            if (node1 != null) {
                node1.stop();
            }
            if (node2 != null) {
                node2.stop();
            }
            if (redisClient1 != null) {
                redisClient1.shutdown();
            }
            if (redisClient2 != null) {
                redisClient2.shutdown();
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StreamSingleChannel extends DistributedCommonTest {
        private RedissonClient redisClient1;
        private RedissonClient redisClient2;

        @BeforeAll
        void setupNodes() throws Exception {
            String url = redisUrl();
            redisClient1 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            redisClient2 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new RedisStoreFactory(redisClient1,
                    new RedisStreamEventStore.Builder(redisClient1).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()));
            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();
            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new RedisStoreFactory(redisClient2,
                    new RedisStreamEventStore.Builder(redisClient2).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()));
            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            if (node1 != null) {
                node1.stop();
            }
            if (node2 != null) {
                node2.stop();
            }
            if (redisClient1 != null) {
                redisClient1.shutdown();
            }
            if (redisClient2 != null) {
                redisClient2.shutdown();
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StreamMultiChannel extends DistributedCommonTest {
        private RedissonClient redisClient1;
        private RedissonClient redisClient2;

        @BeforeAll
        void setupNodes() throws Exception {
            String url = redisUrl();
            redisClient1 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            redisClient2 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new RedisStoreFactory(redisClient1,
                    new RedisStreamEventStore.Builder(redisClient1).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()));
            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();
            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new RedisStoreFactory(redisClient2,
                    new RedisStreamEventStore.Builder(redisClient2).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()));
            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            if (node1 != null) {
                node1.stop();
            }
            if (node2 != null) {
                node2.stop();
            }
            if (redisClient1 != null) {
                redisClient1.shutdown();
            }
            if (redisClient2 != null) {
                redisClient2.shutdown();
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ReliablePubSubSingleChannel extends DistributedCommonTest {
        private RedissonClient redisClient1;
        private RedissonClient redisClient2;

        @BeforeAll
        void setupNodes() throws Exception {
            String url = redisUrl();
            redisClient1 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            redisClient2 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new RedisStoreFactory(redisClient1,
                    new RedisPubSubReliableEventStore.Builder(redisClient1).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()));
            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();
            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new RedisStoreFactory(redisClient2,
                    new RedisPubSubReliableEventStore.Builder(redisClient2).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()));
            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            if (node1 != null) {
                node1.stop();
            }
            if (node2 != null) {
                node2.stop();
            }
            if (redisClient1 != null) {
                redisClient1.shutdown();
            }
            if (redisClient2 != null) {
                redisClient2.shutdown();
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ReliablePubSubMultiChannel extends DistributedCommonTest {
        private RedissonClient redisClient1;
        private RedissonClient redisClient2;

        @BeforeAll
        void setupNodes() throws Exception {
            String url = redisUrl();
            redisClient1 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            redisClient2 = Redisson.create(DistributedClusterIntegrationSupport.redisConfig(url));
            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new RedisStoreFactory(redisClient1,
                    new RedisPubSubReliableEventStore.Builder(redisClient1).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()));
            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();
            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new RedisStoreFactory(redisClient2,
                    new RedisPubSubReliableEventStore.Builder(redisClient2).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()));
            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            if (node1 != null) {
                node1.stop();
            }
            if (node2 != null) {
                node2.stop();
            }
            if (redisClient1 != null) {
                redisClient1.shutdown();
            }
            if (redisClient2 != null) {
                redisClient2.shutdown();
            }
        }
    }
}
