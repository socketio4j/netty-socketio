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

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.cluster.Address;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.integration.interop.AbstractDistributedJsClientInteropTest;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.hazelcast.HazelcastPubSubEventStore;
import com.socketio4j.socketio.store.hazelcast.HazelcastStoreFactory;

/**
 * Multi-Node JS Client Interoperability Test Suite backed by an embedded Hazelcast member.
 */
@DisplayName("Multi-Node Official JS Client Interoperability Suite (Hazelcast)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedHazelcastJsClientInteropTest extends AbstractDistributedJsClientInteropTest {
    private static final String CLUSTER_NAME = "js-interop-" + UUID.randomUUID();

    private HazelcastInstance hazelcastInstance;
    private HazelcastInstance hazelcastInstance1;
    private HazelcastInstance member;
    @BeforeAll
    @Override
    public void setupCluster() throws Exception {

        System.out.println("==================================================");
        System.out.println("STARTING HAZELCAST TEST");
        System.out.println("==================================================");

        // ---------- MEMBER ----------
        Config config = new Config();
        config.setClusterName(CLUSTER_NAME);

        config.getNetworkConfig()
                .setPort(5701)
                .setPortAutoIncrement(true);

        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        System.out.println("Creating embedded member...");

        member = Hazelcast.newHazelcastInstance(config);
        System.out.println("Multicast : "
                + config.getNetworkConfig()
                .getJoin().getMulticastConfig().isEnabled());

        System.out.println("TCP/IP    : "
                + config.getNetworkConfig()
                .getJoin().getTcpIpConfig().isEnabled());

        System.out.println("AutoDetect: "
                + config.getNetworkConfig()
                .getJoin().getAutoDetectionConfig().isEnabled());

        System.out.println("Interfaces: "
                + config.getNetworkConfig()
                .getInterfaces().isEnabled());

        System.out.println("Port      : "
                + config.getNetworkConfig().getPort());
        Address address = member.getCluster().getLocalMember().getAddress();

        System.out.println("------------------------------------------");
        System.out.println("Member created");
        System.out.println("Address      : " + address);
        System.out.println("Host         : " + address.getHost());
        System.out.println("Port         : " + address.getPort());
        System.out.println("UUID         : " + member.getCluster().getLocalMember().getUuid());
        System.out.println("------------------------------------------");

        Thread.sleep(2000);

        // ---------- CLIENT 1 ----------

        ClientConfig clientConfig1 = new ClientConfig();
        clientConfig1.setClusterName(CLUSTER_NAME);

        clientConfig1.getNetworkConfig()
                .setSmartRouting(false)
                .setRedoOperation(true)
                .addAddress(address.getHost() + ":" + address.getPort());

        System.out.println("Creating client #1");
        System.out.println("Addresses : "
                + clientConfig1.getNetworkConfig().getAddresses());

        hazelcastInstance = HazelcastClient.newHazelcastClient(clientConfig1);

        System.out.println("Client #1 connected");
        System.out.println("Client members : "
                + hazelcastInstance.getCluster().getMembers());

        // ---------- CLIENT 2 ----------

        ClientConfig clientConfig2 = new ClientConfig();
        clientConfig2.setClusterName(CLUSTER_NAME);

        clientConfig2.getNetworkConfig()
                .setSmartRouting(false)
                .setRedoOperation(true)
                .addAddress(address.getHost() + ":" + address.getPort());

        System.out.println("Creating client #2");
        System.out.println("Addresses : "
                + clientConfig2.getNetworkConfig().getAddresses());

        hazelcastInstance1 = HazelcastClient.newHazelcastClient(clientConfig2);

        System.out.println("Client #2 connected");
        System.out.println("Client members : "
                + hazelcastInstance1.getCluster().getMembers());

        // ---------- NODE 1 ----------

        Configuration cfg1 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);

        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(
                DistributedClusterIntegrationSupport.findAvailablePort());

        cfg1.setStoreFactory(new HazelcastStoreFactory(
                hazelcastInstance,
                new HazelcastPubSubEventStore.Builder(hazelcastInstance)
                        .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                        .build()));

        node1 = new SocketIOServer(cfg1);

        attachDefaultRoomListeners(node1);

        node1.start();

        port1 = cfg1.getPort();

        System.out.println("Node #1 started on port " + port1);

        // ---------- NODE 2 ----------

        Configuration cfg2 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);

        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(
                DistributedClusterIntegrationSupport.findAvailablePort());

        cfg2.setStoreFactory(new HazelcastStoreFactory(
                hazelcastInstance1,
                new HazelcastPubSubEventStore.Builder(hazelcastInstance1)
                        .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                        .build()));

        node2 = new SocketIOServer(cfg2);

        attachDefaultRoomListeners(node2);

        node2.start();

        port2 = cfg2.getPort();

        System.out.println("Node #2 started on port " + port2);

        System.out.println("==================================================");
        System.out.println("SETUP COMPLETE");
        System.out.println("==================================================");

        initJsScript();
    }

    @AfterAll
    @Override
    public void teardownCluster() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        if (hazelcastInstance != null) hazelcastInstance.shutdown();
        if (hazelcastInstance1 != null) hazelcastInstance1.shutdown();
        if (member != null) member.shutdown();
    }
}
