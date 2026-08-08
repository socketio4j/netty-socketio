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

import com.socketio4j.socketio.TestResourceCleanup;
import com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport;
import static com.socketio4j.socketio.integration.cluster.DistributedClusterIntegrationSupport.*;


import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;

import org.junit.jupiter.api.parallel.ResourceLock;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.integration.interop.AbstractDistributedJsClientInteropTest;
import com.socketio4j.socketio.store.container.CustomizedRedisContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;
import com.socketio4j.socketio.store.redis_stream.RedisStreamEventStore;

/**
 * Multi-Node JS Client Interoperability Test Suite backed by Redis Streams.
 */
@ResourceLock("EMBEDDED_REDIS")
@DisplayName("Multi-Node Official JS Client Interoperability Suite (Redis Streams)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedRedisStreamJsClientInteropTest extends AbstractDistributedJsClientInteropTest {

    private static final CustomizedRedisContainer REDIS = new CustomizedRedisContainer().withReuse(false);

    private RedissonClient redisson1;
    private RedissonClient redisson2;

    @BeforeAll
    @Override
    public void setupCluster() throws Exception {
        if (!REDIS.isRunning()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    REDIS.start();
                    break;
                } catch (Exception e) {
                    if (attempt == 3) throw e;
                    Thread.sleep(500);
                }
            }
        }
        String redisUrl = "redis://" + REDIS.getHost() + ":" + REDIS.getRedisPort();

        org.redisson.config.Config redissonCfg1 = new org.redisson.config.Config();
        redissonCfg1.useSingleServer().setAddress(redisUrl);
        redisson1 = Redisson.create(redissonCfg1);

        org.redisson.config.Config redissonCfg2 = new org.redisson.config.Config();
        redissonCfg2.useSingleServer().setAddress(redisUrl);
        redisson2 = Redisson.create(redissonCfg2);

        // Server 1
        Configuration cfg1 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg1);
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        cfg1.setStoreFactory(new MemoryStoreFactory(new RedisStreamEventStore(redisson1, redisson1, null, EventStoreMode.MULTI_CHANNEL, null, null)));
        node1 = new SocketIOServer(cfg1);
        attachDefaultRoomListeners(node1);
        node1.start();
        port1 = cfg1.getPort();

        // Server 2
        Configuration cfg2 = new Configuration();
        DistributedClusterIntegrationSupport.applyReuseListenAddress(cfg2);
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(DistributedClusterIntegrationSupport.findAvailablePort());
        cfg2.setStoreFactory(new MemoryStoreFactory(new RedisStreamEventStore(redisson2, redisson2, null, EventStoreMode.MULTI_CHANNEL, null, null)));
        node2 = new SocketIOServer(cfg2);
        attachDefaultRoomListeners(node2);
        node2.start();
        port2 = cfg2.getPort();

        initJsScript();
    }

    @AfterAll
    @Override
    public void teardownCluster() {
        TestResourceCleanup.runAll("Redis stream distributed interop cleanup",
                () -> { if (node1 != null) node1.stop(); },
                () -> { if (node2 != null) node2.stop(); },
                () -> { if (redisson1 != null) redisson1.shutdown(); },
                () -> { if (redisson2 != null) redisson2.shutdown(); },
                () -> { if (REDIS != null) REDIS.stop(); });
    }
}
