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
package com.socketio4j.socketio.store.event;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;

import com.socketio4j.socketio.store.container.CustomizedRedisContainer;
import com.socketio4j.socketio.store.redis_pubsub.RedisPubSubEventStore;

/**
 * Test class for RedisPubSubEventStoreTest using testcontainers
 */

public class RedisPubSubEventStoreTest extends AbstractEventStoreTest {

    private final java.util.List<RedissonClient> clients = new java.util.ArrayList<>();
    private RedissonClient redissonPub;
    private RedissonClient redissonSub;

    @Override
    protected GenericContainer<?> createContainer() {
        return new CustomizedRedisContainer();
    }

    @Override
    protected EventStore createEventStore(Long nodeId) throws Exception {
        CustomizedRedisContainer customizedRedisContainer = (CustomizedRedisContainer) container;
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + customizedRedisContainer.getHost() + ":" + customizedRedisContainer.getRedisPort());
        
        redissonPub = Redisson.create(config);
        clients.add(redissonPub);
        redissonSub = Redisson.create(config);
        clients.add(redissonSub);
        return new RedisPubSubEventStore(redissonPub, redissonSub, EventStoreMode.MULTI_CHANNEL, nodeId);
    }

    @Override
    protected void closeClients() {
        java.util.List<com.socketio4j.socketio.TestResourceCleanup.ThrowingAction> actions = new java.util.ArrayList<>();
        for (RedissonClient client : clients) actions.add(client::shutdown);
        clients.clear();
        com.socketio4j.socketio.TestResourceCleanup.runAll("event store clients",
                actions.toArray(new com.socketio4j.socketio.TestResourceCleanup.ThrowingAction[0]));
    }
}
