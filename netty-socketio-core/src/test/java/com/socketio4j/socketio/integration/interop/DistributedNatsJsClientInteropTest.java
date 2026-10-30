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
import com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport;
import static com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport.*;


import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;


import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.integration.interop.AbstractDistributedJsClientInteropTest;
import com.socketio4j.socketio.store.container.CustomizedNatsContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;
import com.socketio4j.socketio.store.nats_pubsub.NatsEventStore;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Multi-Node JS Client Interoperability Test Suite backed by NATS PubSub.
 */
@ResourceLock("EMBEDDED_NATS")
@DisplayName("Multi-Node Official JS Client Interoperability Suite (NATS PubSub)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedNatsJsClientInteropTest extends AbstractDistributedJsClientInteropTest {

    private static final CustomizedNatsContainer NATS_CONTAINER = new CustomizedNatsContainer();
    private Connection natsConn1;
    private Connection natsConn2;

    @BeforeAll
    @Override
    public void setupCluster() throws Exception {
        if (!NATS_CONTAINER.isRunning()) {
            NATS_CONTAINER.start();
        }
        String bootstrap = NATS_CONTAINER.getNatsUrl();
        Options options = new Options.Builder()
                .server(bootstrap)
                .connectionTimeout(Duration.ofSeconds(5))
                .maxReconnects(-1)
                .reconnectWait(Duration.ofMillis(500))
                .build();

        natsConn1 = Nats.connect(options);
        natsConn2 = Nats.connect(options);

        // Server 1
        Configuration cfg1 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        cfg1.setStoreFactory(new MemoryStoreFactory(new NatsEventStore(natsConn1, EventStoreMode.MULTI_CHANNEL, null)));
        node1 = new SocketIOServer(cfg1);
        attachDefaultRoomListeners(node1);
        node1.start();
        port1 = cfg1.getPort();

        // Server 2
        Configuration cfg2 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        cfg2.setStoreFactory(new MemoryStoreFactory(new NatsEventStore(natsConn2, EventStoreMode.MULTI_CHANNEL, null)));
        node2 = new SocketIOServer(cfg2);
        attachDefaultRoomListeners(node2);
        node2.start();
        port2 = cfg2.getPort();

        initJsScript();
    }

    @AfterAll
    @Override
    public void teardownCluster() throws Exception {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        if (natsConn1 != null) natsConn1.close();
        if (natsConn2 != null) natsConn2.close();
        if (NATS_CONTAINER.isRunning()) NATS_CONTAINER.stop();
    }
}
