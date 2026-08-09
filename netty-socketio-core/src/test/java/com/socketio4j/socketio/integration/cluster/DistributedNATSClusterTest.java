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

import java.time.Duration;
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

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.container.CustomizedNatsContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;
import com.socketio4j.socketio.store.nats_pubsub.NatsEventStore;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;

/**
 * Runs {@link DistributedCommonTest} against all NATS-backed cluster variants while sharing
 * one NATS Testcontainer for maximum execution speed and zero container setup overhead.
 */
@ResourceLock("EMBEDDED_NATS")
public class DistributedNATSClusterTest {

    @SuppressWarnings("resource")
    static final CustomizedNatsContainer NATS_CONTAINER = new CustomizedNatsContainer();

    @BeforeAll
    static void startNats() {
        if (!NATS_CONTAINER.isRunning()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    NATS_CONTAINER.start();
                    break;
                } catch (Exception e) {
                    if (attempt == 3) throw new RuntimeException("Failed to start NATS container", e);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while starting NATS test container", error);
                    }
                }
            }
        }
    }

    @AfterAll
    static void stopNats() {
        TestResourceCleanup.runAll("NATS test container cleanup",
                () -> { if (NATS_CONTAINER != null && NATS_CONTAINER.isRunning()) NATS_CONTAINER.stop(); });
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SingleChannelMemoryTest extends DistributedCommonTest {
        private Connection nc;
        private Connection nc1;

        @BeforeAll
        void setupNodes() throws Exception {
            String bootstrap = NATS_CONTAINER.getNatsUrl();

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(0);
            Options options = new Options.Builder()
                    .server(bootstrap)
                    .connectionTimeout(Duration.ofSeconds(2))
                    .reconnectWait(Duration.ofMillis(500))
                    .pingInterval(Duration.ofSeconds(10))
                    .maxPingsOut(3)
                    .build();

            nc = Nats.connect(options);
            cfg1.setStoreFactory(new MemoryStoreFactory(new NatsEventStore(nc, EventStoreMode.SINGLE_CHANNEL, null)));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(0);
            Options options1 = new Options.Builder()
                    .server(bootstrap)
                    .connectionTimeout(Duration.ofSeconds(2))
                    .reconnectWait(Duration.ofMillis(500))
                    .pingInterval(Duration.ofSeconds(10))
                    .maxPingsOut(3)
                    .build();

            nc1 = Nats.connect(options1);
            cfg2.setStoreFactory(new MemoryStoreFactory(new NatsEventStore(nc1, EventStoreMode.SINGLE_CHANNEL, null)));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("NATS cluster node cleanup",
                    () -> { if (nc != null) nc.close(); },
                    () -> { if (nc1 != null) nc1.close(); },
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MultiChannelMemoryTest extends DistributedCommonTest {
        private Connection nc;
        private Connection nc1;

        @BeforeAll
        void setupNodes() throws Exception {
            String bootstrap = NATS_CONTAINER.getNatsUrl();

            Configuration cfg1 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
            cfg1.setHostname("127.0.0.1");
            cfg1.setPort(0);
            Options options = new Options.Builder()
                    .server(bootstrap)
                    .connectionTimeout(Duration.ofSeconds(2))
                    .reconnectWait(Duration.ofMillis(500))
                    .pingInterval(Duration.ofSeconds(10))
                    .maxPingsOut(3)
                    .build();

            nc = Nats.connect(options);
            cfg1.setStoreFactory(new MemoryStoreFactory(new NatsEventStore(nc, EventStoreMode.MULTI_CHANNEL, null)));

            node1 = new SocketIOServer(cfg1);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
            node1.start();
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
            cfg2.setHostname("127.0.0.1");
            cfg2.setPort(0);
            Options options1 = new Options.Builder()
                    .server(bootstrap)
                    .connectionTimeout(Duration.ofSeconds(2))
                    .reconnectWait(Duration.ofMillis(500))
                    .pingInterval(Duration.ofSeconds(10))
                    .maxPingsOut(3)
                    .build();

            nc1 = Nats.connect(options1);
            cfg2.setStoreFactory(new MemoryStoreFactory(new NatsEventStore(nc1, EventStoreMode.MULTI_CHANNEL, null)));

            node2 = new SocketIOServer(cfg2);
            DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
            node2.start();
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("NATS cluster node cleanup",
                    () -> { if (nc != null) nc.close(); },
                    () -> { if (nc1 != null) nc1.close(); },
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); });
        }
    }
}
