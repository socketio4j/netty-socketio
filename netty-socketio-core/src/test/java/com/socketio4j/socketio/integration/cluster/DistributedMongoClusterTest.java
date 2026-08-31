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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.mongodb.reactivestreams.client.MongoClient;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.container.CustomizedMongoContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;
import com.socketio4j.socketio.store.mongo.MongoEventStore;

/**
 * Runs {@link DistributedCommonTest} against all MongoDB-backed cluster variants while sharing
 * one MongoDB Testcontainer for maximum execution speed and zero container setup overhead.
 */
@ResourceLock("EMBEDDED_MONGO")
public class DistributedMongoClusterTest {

    @SuppressWarnings("resource")
    static final CustomizedMongoContainer MONGO_CONTAINER = new CustomizedMongoContainer();

    private static final String DB_NAME = "socketio_test";

    @BeforeAll
    static void startMongo() {
        if (!MONGO_CONTAINER.isRunning()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    MONGO_CONTAINER.start();
                    break;
                } catch (Exception e) {
                    if (attempt == 3) throw new RuntimeException("Failed to start MongoDB container", e);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while starting MongoDB test container", error);
                    }
                }
            }
        }
    }

    @AfterAll
    static void stopMongo() {
        TestResourceCleanup.runAll("MongoDB test container cleanup",
                () -> { if (MONGO_CONTAINER != null && MONGO_CONTAINER.isRunning()) MONGO_CONTAINER.stop(); });
    }

    /**
     * Starts one node backed by its own store, so the two nodes talk to each other only
     * through change streams — never through a shared in-process client.
     */
    private static SocketIOServer startNode(MongoEventStore store, Configuration cfg) {
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg);
        cfg.setHostname("127.0.0.1");
        cfg.setPort(0);
        cfg.setStoreFactory(new MemoryStoreFactory(store));

        SocketIOServer node = new SocketIOServer(cfg);
        DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node);
        node.start();
        return node;
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SingleChannelMemoryTest extends DistributedCommonTest {
        private MongoClient mc1;
        private MongoClient mc2;
        private MongoEventStore store1;
        private MongoEventStore store2;

        @BeforeAll
        void setupNodes() {
            Configuration cfg1 = new Configuration();
            mc1 = MONGO_CONTAINER.createClient();
            store1 = new MongoEventStore.Builder(mc1, DB_NAME)
                    .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                    .build();
            node1 = startNode(store1, cfg1);
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            mc2 = MONGO_CONTAINER.createClient();
            store2 = new MongoEventStore.Builder(mc2, DB_NAME)
                    .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                    .build();
            node2 = startNode(store2, cfg2);
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("MongoDB cluster node cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    // Stopping a node leaves its event store running, so close the change
                    // streams before the clients they are reading from.
                    () -> { if (store1 != null) store1.shutdown(); },
                    () -> { if (store2 != null) store2.shutdown(); },
                    () -> { if (mc1 != null) mc1.close(); },
                    () -> { if (mc2 != null) mc2.close(); });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MultiChannelMemoryTest extends DistributedCommonTest {
        private MongoClient mc1;
        private MongoClient mc2;
        private MongoEventStore store1;
        private MongoEventStore store2;

        @BeforeAll
        void setupNodes() {
            Configuration cfg1 = new Configuration();
            mc1 = MONGO_CONTAINER.createClient();
            store1 = new MongoEventStore.Builder(mc1, DB_NAME)
                    .eventStoreMode(EventStoreMode.MULTI_CHANNEL)
                    .build();
            node1 = startNode(store1, cfg1);
            port1 = cfg1.getPort();

            Configuration cfg2 = new Configuration();
            mc2 = MONGO_CONTAINER.createClient();
            store2 = new MongoEventStore.Builder(mc2, DB_NAME)
                    .eventStoreMode(EventStoreMode.MULTI_CHANNEL)
                    .build();
            node2 = startNode(store2, cfg2);
            port2 = cfg2.getPort();
        }

        @AfterAll
        void tearDownNodes() {
            TestResourceCleanup.runAll("MongoDB cluster node cleanup",
                    () -> { if (node1 != null) node1.stop(); },
                    () -> { if (node2 != null) node2.stop(); },
                    // Stopping a node leaves its event store running, so close the change
                    // streams before the clients they are reading from.
                    () -> { if (store1 != null) store1.shutdown(); },
                    () -> { if (store2 != null) store2.shutdown(); },
                    () -> { if (mc1 != null) mc1.close(); },
                    () -> { if (mc2 != null) mc2.close(); });
        }
    }
}
