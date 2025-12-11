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

import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.store.CustomizedHazelcastContainer;
import com.socketio4j.socketio.store.hazelcast.HazelcastEventStore;

/**
 * Test class for HazelcastPubSubStore using testcontainers
 */
public class HazelcastEventStoreTest extends AbstractEventStoreTest {

    private HazelcastInstance hazelcastPub;
    private HazelcastInstance hazelcastSub;

    @Override
    protected GenericContainer<?> createContainer() {
        return new CustomizedHazelcastContainer().withReuse(true);  // This is now single-server safe
    }

    @Override
    protected EventStore createPubSubStore(Long nodeId) throws Exception {
        CustomizedHazelcastContainer hz = (CustomizedHazelcastContainer) container;

        ClientConfig config = new ClientConfig();
        config.getNetworkConfig()
                .setSmartRouting(false)                   // never try unreachable members inside container
                .setRedoOperation(true)
                .addAddress(hz.getHazelcastAddress());   // ALWAYS localhost:mappedPort

        hazelcastPub = HazelcastClient.newHazelcastClient(config);
        hazelcastSub = HazelcastClient.newHazelcastClient(config);

        return new HazelcastEventStore(
                hazelcastPub,
                hazelcastSub,
                nodeId,
                PublishConfig.allUnreliable(),
                EventStoreMode.MULTI_CHANNEL
        );
    }

    @Override
    public void tearDown() throws Exception {
        if (hazelcastPub != null) hazelcastPub.shutdown();
        if (hazelcastSub != null) hazelcastSub.shutdown();
        if (container != null && container.isRunning()) container.stop();
    }
}
