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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstract base class for PubSub store tests
 */
public abstract class AbstractEventStoreTest {

    protected EventStore publisherStore;  // store for publishing messages
    protected EventStore subscriberStore; // store for subscribing to messages
    protected GenericContainer<?> container;
    protected Long publisherNodeId = 2L;   // publisher's nodeId
    protected Long subscriberNodeId = 1L;  // subscriber's nodeId

    @BeforeEach
    public void setUp() throws Exception {
        container = createContainer();
        if (container != null) {
            container.start();
        }
        publisherStore = createEventStore(publisherNodeId);
        subscriberStore = createEventStore(subscriberNodeId);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (publisherStore != null) {
            publisherStore.shutdown();
        }
        if (subscriberStore != null) {
            subscriberStore.shutdown();
        }
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    /**
     * Create the container for testing
     */
    protected abstract GenericContainer<?> createContainer();

    /**
     * Create the PubSub store instance for testing with specified nodeId
     */
    protected abstract EventStore createEventStore(Long nodeId) throws Exception;

    @Test
    public void testBasicPublishSubscribe() throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DispatchMessage> receivedRef = new AtomicReference<>();

        // Subscribe on subscriber node
        subscriberStore.subscribe(
                EventType.DISPATCH,
                message -> {
                    // Ignore messages from same node
                    if (!subscriberNodeId.equals(message.getNodeId())) {
                        receivedRef.set(message);
                        latch.countDown();
                    }
                },
                DispatchMessage.class
        );

        // Create Packet (protocol-level)
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setName("test-event");
        packet.setNsp("/test");
        packet.setData("test content from different node");

        // Create DispatchMessage (cluster-level)
        DispatchMessage outgoing = new DispatchMessage(
                "room1",
                packet,
                "/test"
        );
        outgoing.setNodeId(publisherNodeId);

        // Publish
        publisherStore.publish(EventType.DISPATCH, outgoing);

        // Await delivery
        assertTrue(
                latch.await(5, TimeUnit.SECONDS),
                "Message should be received within 5 seconds"
        );

        // Validate
        DispatchMessage received = receivedRef.get();
        assertNotNull(received, "Received DispatchMessage must not be null");

        assertEquals("room1", received.getRoom());
        assertEquals("/test", received.getNamespace());
        assertEquals(publisherNodeId, received.getNodeId());

        Packet receivedPacket = received.getPacket();
        assertNotNull(receivedPacket, "Packet must not be null");

        assertEquals(PacketType.MESSAGE, receivedPacket.getType());
        assertEquals(PacketType.EVENT, receivedPacket.getSubType());
        assertEquals("test-event", receivedPacket.getName());
        assertEquals("/test", receivedPacket.getNsp());
        assertEquals(
                "test content from different node",
                receivedPacket.getData()
        );
    }


    @Test
    public void testMessageFiltering() throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DispatchMessage> receivedRef = new AtomicReference<>();

        // Subscribe on subscriber node
        subscriberStore.subscribe(
                EventType.DISPATCH,
                message -> {
                    // Should NOT receive messages from the same node
                    if (!subscriberNodeId.equals(message.getNodeId())) {
                        receivedRef.set(message);
                        latch.countDown();
                    }
                },
                DispatchMessage.class
        );

        // Create protocol packet
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setName("filter-test");
        packet.setNsp("/");

        packet.setData("test content from different node");

        // Create dispatch message
        DispatchMessage outgoing = new DispatchMessage(
                "room1",
                packet,
                "/"
        );
        outgoing.setNodeId(publisherNodeId);

        // Publish from publisher node
        publisherStore.publish(EventType.DISPATCH, outgoing);

        // Await delivery
        assertTrue(
                latch.await(5, TimeUnit.SECONDS),
                "Message should be received within 5 seconds"
        );

        // Validate received message
        DispatchMessage received = receivedRef.get();
        assertNotNull(received, "DispatchMessage must not be null");

        // Ensure filtering worked
        assertEquals(publisherNodeId, received.getNodeId());

        Packet receivedPacket = received.getPacket();
        assertNotNull(receivedPacket);

        assertEquals("filter-test", receivedPacket.getName());
        assertEquals(
                "test content from different node",
                receivedPacket.getData()
        );
    }


    @Test
    public void testUnsubscribe() throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DispatchMessage> receivedRef = new AtomicReference<>();

        // Subscribe on subscriber node
        subscriberStore.subscribe(
                EventType.DISPATCH,
                message -> {
                    // Should receive messages only if still subscribed
                    if (!subscriberNodeId.equals(message.getNodeId())) {
                        receivedRef.set(message);
                        latch.countDown();
                    }
                },
                DispatchMessage.class
        );

        // Unsubscribe immediately
        subscriberStore.unsubscribe(EventType.DISPATCH);

        // Create protocol packet
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setName("unsubscribe-test");
        packet.setNsp("/");
        packet.setData("test content");

        // Create dispatch message
        DispatchMessage outgoing = new DispatchMessage(
                "room1",
                packet,
                "/"
        );
        outgoing.setNodeId(publisherNodeId);

        // Publish from publisher node
        publisherStore.publish(EventType.DISPATCH, outgoing);

        // Message should NOT be received
        assertFalse(
                latch.await(2, TimeUnit.SECONDS),
                "Message should not be received after unsubscribe"
        );

        assertNull(
                receivedRef.get(),
                "No DispatchMessage should be received"
        );
    }
    @Test
    public void testMultipleTopics() throws InterruptedException {

        CountDownLatch dispatchLatch = new CountDownLatch(1);
        CountDownLatch connectLatch = new CountDownLatch(1);

        AtomicReference<DispatchMessage> dispatchRef = new AtomicReference<>();
        AtomicReference<DispatchMessage> connectRef = new AtomicReference<>();

        // Subscribe to DISPATCH
        subscriberStore.subscribe(
                EventType.DISPATCH,
                message -> {
                    if (!subscriberNodeId.equals(message.getNodeId())) {
                        dispatchRef.set(message);
                        dispatchLatch.countDown();
                    }
                },
                DispatchMessage.class
        );

        // Subscribe to CONNECT
        subscriberStore.subscribe(
                EventType.CONNECT,
                message -> {
                    if (!subscriberNodeId.equals(message.getNodeId())) {
                        connectRef.set(message);
                        connectLatch.countDown();
                    }
                },
                DispatchMessage.class
        );

        // --- DISPATCH message ---
        Packet dispatchPacket = new Packet(PacketType.MESSAGE);
        dispatchPacket.setSubType(PacketType.EVENT);
        dispatchPacket.setName("dispatch-event");
        dispatchPacket.setNsp("/");
        dispatchPacket.setData("dispatch message");

        DispatchMessage dispatchMsg = new DispatchMessage(
                "room1",
                dispatchPacket,
                "/"
        );
        dispatchMsg.setNodeId(publisherNodeId);

        // --- CONNECT message ---
        Packet connectPacket = new Packet(PacketType.MESSAGE);
        connectPacket.setSubType(PacketType.EVENT);
        connectPacket.setName("connect-event");
        connectPacket.setNsp("/");
        connectPacket.setData("connect message");

        DispatchMessage connectMsg = new DispatchMessage(
                "room1",
                connectPacket,
                "/"
        );
        connectMsg.setNodeId(publisherNodeId);

        // Publish to different topics
        publisherStore.publish(EventType.DISPATCH, dispatchMsg);
        publisherStore.publish(EventType.CONNECT, connectMsg);

        // Wait for both
        assertTrue(
                dispatchLatch.await(5, TimeUnit.SECONDS),
                "Dispatch message should be received"
        );

        assertTrue(
                connectLatch.await(5, TimeUnit.SECONDS),
                "Connect message should be received"
        );

        // Validate isolation
        assertNotNull(dispatchRef.get());
        assertNotNull(connectRef.get());

        assertEquals(
                "dispatch message",
                dispatchRef.get().getPacket().getData()
        );

        assertEquals(
                "connect message",
                connectRef.get().getPacket().getData()
        );
    }


}
