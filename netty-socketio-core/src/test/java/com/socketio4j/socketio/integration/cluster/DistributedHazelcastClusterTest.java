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
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.container.CustomizedHazelcastContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.hazelcast.HazelcastPubSubEventStore;
import com.socketio4j.socketio.store.hazelcast.HazelcastStoreFactory;
import com.socketio4j.socketio.store.hazelcast_ringbuffer.HazelcastPubSubRingBufferEventStore;

import static com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport.findAvailablePort;

/**
 * Runs {@link DistributedCommonTest} against all Hazelcast-backed cluster variants while sharing
 * one Hazelcast Testcontainer for maximum execution speed and zero container setup overhead.
 */
@ResourceLock("EMBEDDED_HAZELCAST")
public class DistributedHazelcastClusterTest {

    @SuppressWarnings("resource")
    static final CustomizedHazelcastContainer HAZELCAST_CONTAINER = new CustomizedHazelcastContainer().withReuse(false);

    @BeforeAll
    static void startHazelcast() {
        if (!HAZELCAST_CONTAINER.isRunning()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    HAZELCAST_CONTAINER.start();
                    break;
                } catch (Exception e) {
                    if (attempt == 3) throw new RuntimeException("Failed to start Hazelcast container", e);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while starting Hazelcast test container", error);
                    }
                }
            }
        }
    }

    @AfterAll
    static void stopHazelcast() {
        TestResourceCleanup.runAll("Hazelcast test container cleanup",
                () -> { if (HAZELCAST_CONTAINER != null && HAZELCAST_CONTAINER.isRunning()) HAZELCAST_CONTAINER.stop(); });
    }

    private static ClientConfig hazelcastClientConfig() {
        ClientConfig config = new ClientConfig();
        config.setClusterName(HAZELCAST_CONTAINER.getClusterName());
        config.getNetworkConfig()
                .setSmartRouting(false)
                .setRedoOperation(true)
                .addAddress(HAZELCAST_CONTAINER.getHazelcastAddress());
        return config;
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PubSubSingleChannelUnreliableTest extends DistributedCommonTest {
        private HazelcastInstance hazelcastInstance;
        private HazelcastInstance hazelcastInstance1;

        @BeforeAll
        void setupNodes() throws Exception {
            ClientConfig config = hazelcastClientConfig();
            hazelcastInstance = HazelcastClient.newHazelcastClient(config);
            hazelcastInstance1 = HazelcastClient.newHazelcastClient(config);

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new HazelcastStoreFactory(
                    hazelcastInstance, new HazelcastPubSubEventStore.Builder(hazelcastInstance).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()
            ));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new HazelcastStoreFactory(
                    hazelcastInstance1, new HazelcastPubSubEventStore.Builder(hazelcastInstance1).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()
            ));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("Hazelcast member cluster cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    () -> { if (hazelcastInstance != null) hazelcastInstance.shutdown(); },
                    () -> { if (hazelcastInstance1 != null) hazelcastInstance1.shutdown(); });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PubSubMultiChannelUnreliableTest extends DistributedCommonTest {
        private HazelcastInstance hazelcastClient;
        private HazelcastInstance hazelcastClient1;

        @BeforeAll
        void setupNodes() throws Exception {
            ClientConfig config = hazelcastClientConfig();
            hazelcastClient = HazelcastClient.newHazelcastClient(config);
            hazelcastClient1 = HazelcastClient.newHazelcastClient(config);

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new HazelcastStoreFactory(
                    hazelcastClient, new HazelcastPubSubEventStore.Builder(hazelcastClient).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()
            ));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new HazelcastStoreFactory(
                    hazelcastClient1, new HazelcastPubSubEventStore.Builder(hazelcastClient1).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()
            ));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("Hazelcast client cluster cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    () -> { if (hazelcastClient != null) hazelcastClient.shutdown(); },
                    () -> { if (hazelcastClient1 != null) hazelcastClient1.shutdown(); });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RingBufferSingleChannelTest extends DistributedCommonTest {
        private HazelcastInstance hazelcastInstance;
        private HazelcastInstance hazelcastInstance1;

        @BeforeAll
        void setupNodes() throws Exception {
            ClientConfig config = hazelcastClientConfig();
            hazelcastInstance = HazelcastClient.newHazelcastClient(config);
            hazelcastInstance1 = HazelcastClient.newHazelcastClient(config);

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new HazelcastStoreFactory(
                    hazelcastInstance, new HazelcastPubSubRingBufferEventStore.Builder(hazelcastInstance).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()
            ));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new HazelcastStoreFactory(
                    hazelcastInstance1, new HazelcastPubSubRingBufferEventStore.Builder(hazelcastInstance1).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()
            ));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("Hazelcast member single-channel cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    () -> { if (hazelcastInstance != null) hazelcastInstance.shutdown(); },
                    () -> { if (hazelcastInstance1 != null) hazelcastInstance1.shutdown(); });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RingBufferMultiChannelTest extends DistributedCommonTest {
        private HazelcastInstance hazelcastInstance;
        private HazelcastInstance hazelcastInstance1;

        @BeforeAll
        void setupNodes() throws Exception {
            ClientConfig config = hazelcastClientConfig();
            hazelcastInstance = HazelcastClient.newHazelcastClient(config);
            hazelcastInstance1 = HazelcastClient.newHazelcastClient(config);

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(findAvailablePort());
            cfg1.setStoreFactory(new HazelcastStoreFactory(
                    hazelcastInstance, new HazelcastPubSubRingBufferEventStore.Builder(hazelcastInstance).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()
            ));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(findAvailablePort());
            cfg2.setStoreFactory(new HazelcastStoreFactory(
                    hazelcastInstance1, new HazelcastPubSubRingBufferEventStore.Builder(hazelcastInstance1).eventStoreMode(EventStoreMode.MULTI_CHANNEL).build()
            ));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("Hazelcast member multi-channel cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    () -> { if (hazelcastInstance != null) hazelcastInstance.shutdown(); },
                    () -> { if (hazelcastInstance1 != null) hazelcastInstance1.shutdown(); });
        }
    }
}
