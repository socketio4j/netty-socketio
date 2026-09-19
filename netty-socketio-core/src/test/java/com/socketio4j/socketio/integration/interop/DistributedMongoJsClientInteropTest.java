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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.mongodb.reactivestreams.client.MongoClient;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.TestResourceCleanup;
import com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport;
import com.socketio4j.socketio.store.container.CustomizedMongoContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;
import com.socketio4j.socketio.store.mongo.MongoEventStore;

/**
 * Multi-Node JS Client Interoperability Test Suite backed by MongoDB Change Streams.
 */
@ResourceLock("EMBEDDED_MONGO")
@DisplayName("Multi-Node Official JS Client Interoperability Suite (MongoDB Change Streams)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedMongoJsClientInteropTest extends AbstractDistributedJsClientInteropTest {

    @SuppressWarnings("resource")
    private static final CustomizedMongoContainer MONGO_CONTAINER = new CustomizedMongoContainer();

    private static final String DB_NAME = "socketio_js_interop";

    private MongoClient mc1;
    private MongoClient mc2;
    private MongoEventStore store1;
    private MongoEventStore store2;

    @BeforeAll
    @Override
    public void setupCluster() throws Exception {
        if (!MONGO_CONTAINER.isRunning()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    MONGO_CONTAINER.start();
                    break;
                } catch (Exception e) {
                    if (attempt == 3) {
                        throw new RuntimeException("Failed to start MongoDB container", e);
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while starting MongoDB test container", error);
                    }
                }
            }
        }

        // Server 1
        mc1 = MONGO_CONTAINER.createClient();
        store1 = new MongoEventStore.Builder(mc1, DB_NAME)
                .eventStoreMode(EventStoreMode.MULTI_CHANNEL)
                .collectionPrefix("interop_events_")
                .build();
        Configuration cfg1 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        cfg1.setStoreFactory(new MemoryStoreFactory(store1));
        node1 = new SocketIOServer(cfg1);
        attachDefaultRoomListeners(node1);
        node1.start();
        port1 = cfg1.getPort();

        // Server 2
        mc2 = MONGO_CONTAINER.createClient();
        store2 = new MongoEventStore.Builder(mc2, DB_NAME)
                .eventStoreMode(EventStoreMode.MULTI_CHANNEL)
                .collectionPrefix("interop_events_")
                .build();
        Configuration cfg2 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        cfg2.setStoreFactory(new MemoryStoreFactory(store2));
        node2 = new SocketIOServer(cfg2);
        attachDefaultRoomListeners(node2);
        node2.start();
        port2 = cfg2.getPort();

        initJsScript();
    }

    @AfterAll
    @Override
    public void teardownCluster() {
        TestResourceCleanup.runAll("MongoDB distributed interop cleanup",
                () -> { if (node1 != null) node1.stop(); },
                () -> { if (node2 != null) node2.stop(); },
                () -> { if (store1 != null) store1.shutdown(); },
                () -> { if (store2 != null) store2.shutdown(); },
                () -> { if (mc1 != null) mc1.close(); },
                () -> { if (mc2 != null) mc2.close(); },
                () -> { if (MONGO_CONTAINER != null && MONGO_CONTAINER.isRunning()) MONGO_CONTAINER.stop(); });
    }
}
