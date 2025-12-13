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

import java.net.ServerSocket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.redisson.config.Config;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.CustomizedHazelcastContainer;
import com.socketio4j.socketio.store.CustomizedRedisContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.PublishConfig;
import com.socketio4j.socketio.store.hazelcast.HazelcastEventStore;
import com.socketio4j.socketio.store.hazelcast.HazelcastStoreFactory;
import com.socketio4j.socketio.store.redis_pubsub.RedissonStoreFactory;


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedHazelcastPubSubSingleChannelUnreliableTest extends DistributedCommonTest {

    private static final CustomizedHazelcastContainer HAZELCAST_CONTAINER = new CustomizedHazelcastContainer().withReuse(true);
    private HazelcastInstance hazelcastInstance;
    private HazelcastInstance hazelcastInstance1;
    // -------------------------------------------
    // Utility: find dynamic free port
    // -------------------------------------------
    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // -------------------------------------------
    // Redis + Node Setup
    // -------------------------------------------
    @BeforeAll
    public void setup() throws Exception {
        if (!HAZELCAST_CONTAINER.isRunning()) {
            HAZELCAST_CONTAINER.start();
        }

        ClientConfig config = new ClientConfig();
        config.getNetworkConfig()
                .setSmartRouting(false)                   // never try unreachable members inside container
                .setRedoOperation(true)
                .addAddress(HAZELCAST_CONTAINER.getHazelcastAddress());
        hazelcastInstance = HazelcastClient.newHazelcastClient(config);
        hazelcastInstance1 = HazelcastClient.newHazelcastClient(config);
        // ---------- NODE 1 ----------
        Configuration cfg1 = new Configuration();
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(findAvailablePort());

        cfg1.setStoreFactory(new HazelcastStoreFactory(
                hazelcastInstance, new HazelcastEventStore.Builder(hazelcastInstance).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()
        ));

        node1 = new SocketIOServer(cfg1);
        node1.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);
            c.sendEvent("join-ok", "OK");
        });
        node1.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node1.start();
        port1 = cfg1.getPort();

        // ---------- NODE 2 ----------
        Configuration cfg2 = new Configuration();
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(findAvailablePort());

        cfg2.setStoreFactory(new HazelcastStoreFactory(
                hazelcastInstance1,  new HazelcastEventStore.Builder(hazelcastInstance1).eventStoreMode(EventStoreMode.SINGLE_CHANNEL).build()
                ));
        node2 = new SocketIOServer(cfg2);
        node2.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);
            c.sendEvent("join-ok", "OK");
        });
        node2.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node2.start();
        port2 = cfg2.getPort();

        //Thread.sleep(600);
    }

    private Config redisConfig(String url) {
        Config c = new Config();
        c.useSingleServer().setAddress(url);
        return c;
    }

    @AfterAll
    public void stop() {

        if (node1 != null) {
            node1.stop();
        }
        if (node2 != null) {
            node2.stop();
        }
        if (hazelcastInstance != null) {
            hazelcastInstance.shutdown();
        }
        if (hazelcastInstance1 != null) {
            hazelcastInstance1.shutdown();
        }
        if (HAZELCAST_CONTAINER != null) {
            HAZELCAST_CONTAINER.stop();
        }
    }
}
