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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.GenericContainer;

import com.mongodb.reactivestreams.client.MongoClient;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.container.CustomizedMongoContainer;
import com.socketio4j.socketio.store.mongo.MongoEventStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for MongoEventStore using testcontainers.
 */
@ResourceLock("EMBEDDED_MONGO")
public class MongoPubSubEventStoreTest extends AbstractEventStoreTest {

    private static final String DB_NAME = "socketio_event_store_test";

    private final AtomicInteger testCounter = new AtomicInteger();
    private MongoClient sharedClient;
    private String currentPrefix;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        currentPrefix = "test_" + testCounter.incrementAndGet() + "_";
        super.setUp();
    }

    @Override
    protected GenericContainer<?> createContainer() {
        return new CustomizedMongoContainer().withReuse(false);
    }

    private synchronized MongoClient getSharedClient() {
        if (sharedClient == null) {
            CustomizedMongoContainer mongoContainer = (CustomizedMongoContainer) container;
            sharedClient = mongoContainer.createClient();
        }
        return sharedClient;
    }

    @Override
    protected EventStore createEventStore(Long nodeId) throws Exception {
        return new MongoEventStore.Builder(getSharedClient(), DB_NAME)
                .nodeId(nodeId)
                .collectionPrefix(currentPrefix)
                .eventStoreMode(EventStoreMode.MULTI_CHANNEL)
                .build();
    }

    @Override
    protected void closeClients() {
        // Shared client remains open across tests to prevent reconnection overhead and cursor race
    }

    @AfterAll
    @Override
    public void stopContainer() {
        if (sharedClient != null) {
            try {
                sharedClient.close();
            } catch (Exception ignored) {
            }
            sharedClient = null;
        }
        super.stopContainer();
    }

    @Test
    public void testSingleChannelPubSub() throws Exception {
        String singlePrefix = "single_" + testCounter.incrementAndGet() + "_";

        MongoEventStore singlePubStore = new MongoEventStore.Builder(getSharedClient(), DB_NAME)
                .nodeId(300L)
                .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                .collectionPrefix(singlePrefix)
                .build();

        MongoEventStore singleSubStore = new MongoEventStore.Builder(getSharedClient(), DB_NAME)
                .nodeId(301L)
                .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                .collectionPrefix(singlePrefix)
                .build();

        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<DispatchMessage> receivedRef = new AtomicReference<>();

            singleSubStore.subscribe(
                    EventType.ALL_SINGLE_CHANNEL,
                    message -> {
                        if (message instanceof DispatchMessage) {
                            receivedRef.set((DispatchMessage) message);
                            latch.countDown();
                        }
                    },
                    DispatchMessage.class
            );

            Packet packet = new Packet(PacketType.MESSAGE);
            packet.setSubType(PacketType.EVENT);
            packet.setName("single-channel-event");
            packet.setNsp("/");
            packet.setData("hello-single-channel");

            DispatchMessage outgoing = new DispatchMessage("roomA", packet, "/");
            outgoing.setNodeId(300L);

            singlePubStore.publish(EventType.DISPATCH, outgoing);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Message should be received in SINGLE_CHANNEL mode");
            DispatchMessage received = receivedRef.get();
            assertNotNull(received);
            assertEquals("roomA", received.getRoom());
            assertEquals(300L, received.getNodeId());
            assertEquals("single-channel-event", received.getPacket().getName());
            assertEquals("hello-single-channel", received.getPacket().getData());

            // Unsubscribe test
            singleSubStore.unsubscribe(EventType.ALL_SINGLE_CHANNEL);
            CountDownLatch unsubLatch = new CountDownLatch(1);
            AtomicReference<DispatchMessage> unsubReceived = new AtomicReference<>();

            singlePubStore.publish(EventType.DISPATCH, outgoing);
            assertFalse(unsubLatch.await(2, TimeUnit.SECONDS), "No message after unsubscribe");
            assertNull(unsubReceived.get());
        } finally {
            singlePubStore.shutdown();
            singleSubStore.shutdown();
        }
    }
}
