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
package com.socketio4j.socketio.store.pubsub;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;

import com.socketio4j.socketio.store.CustomizedRedisContainer;
import com.socketio4j.socketio.store.RedisStreamsPubSubStore;

/**
 * Test class for RedisStreamsPubSubStore using Redis Streams and testcontainers.
 * This test extends AbstractPubSubStoreTest to verify all standard PubSub behavior
 * plus Redis Streams-specific functionality.
 */
public class RedisStreamsPubSubStoreTest extends AbstractPubSubStoreTest {

    private RedissonClient redissonClient;

    @Override
    protected GenericContainer<?> createContainer() {
        return new CustomizedRedisContainer();
    }

    @Override
    protected PubSubStore createPubSubStore(Long nodeId) throws Exception {
        CustomizedRedisContainer customizedRedisContainer = (CustomizedRedisContainer) container;
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + customizedRedisContainer.getHost() + ":" + customizedRedisContainer.getRedisPort());
        
        redissonClient = Redisson.create(config);
        return new RedisStreamsPubSubStore(nodeId, redissonClient);
    }

    @Override
    public void tearDown() throws Exception {
        if (publisherStore != null) {
            publisherStore.shutdown();
        }
        if (subscriberStore != null) {
            subscriberStore.shutdown();
        }
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }
}