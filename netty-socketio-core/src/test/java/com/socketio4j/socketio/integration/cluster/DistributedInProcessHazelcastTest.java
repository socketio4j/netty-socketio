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

import java.util.UUID;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.hazelcast.HazelcastStoreFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("EMBEDDED_HAZELCAST")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedInProcessHazelcastTest extends DistributedCommonTest {

    private static final String CLUSTER_NAME = "socketio4j-in-process-" + UUID.randomUUID();

    private HazelcastInstance hz1;
    private HazelcastInstance hz2;

    @BeforeAll
    public void setup() throws Exception {
        // Configure Hazelcast to form a cluster in-process using loopback/local discovery
        Config config = new Config();
        config.setClusterName(CLUSTER_NAME);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember("127.0.0.1");
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);

        hz1 = Hazelcast.newHazelcastInstance(config);
        hz2 = Hazelcast.newHazelcastInstance(config);

        // NODE 1
        Configuration cfg1 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        cfg1.setStoreFactory(new HazelcastStoreFactory(hz1));
        node1 = new SocketIOServer(cfg1);
        DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node1);
        node1.start();
        port1 = cfg1.getPort();

        // NODE 2
        Configuration cfg2 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        cfg2.setStoreFactory(new HazelcastStoreFactory(hz2));
        node2 = new SocketIOServer(cfg2);
        DistributedClusterIntegrationSupport.attachDefaultRoomListeners(node2);
        node2.start();
        port2 = cfg2.getPort();
    }

    @AfterAll
    public void teardown() {
        TestResourceCleanup.runAll("in-process Hazelcast cluster cleanup",
                () -> { if (node1 != null) node1.stop(); },
                () -> { if (node2 != null) node2.stop(); },
                () -> { if (hz1 != null) hz1.shutdown(); },
                () -> { if (hz2 != null) hz2.shutdown(); });
    }
}
