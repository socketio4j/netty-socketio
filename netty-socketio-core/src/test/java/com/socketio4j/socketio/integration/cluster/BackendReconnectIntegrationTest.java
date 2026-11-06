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

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.socketio4j.socketio.TestResourceCleanup;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.nats_pubsub.NatsEventStore;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Nats;
import io.nats.client.Options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual service restart; steady-state mocks cannot satisfy this contract. */
@ResourceLock("EMBEDDED_NATS")
class BackendReconnectIntegrationTest {
    @Test
    void natsResubscribesExistingEventStoreAfterBrokerProcessRestart() throws Exception {
        try (GenericContainer<?> broker = new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
                .withExposedPorts(4222)
                .withCreateContainerCmdModifier(command -> command.withEntrypoint("/bin/sh"))
                .withCommand("-c", "command -v nats-server >/dev/null || exit 1; trap 'kill -TERM \"$broker\"; exit 0' TERM INT; "
                        + "while true; do nats-server -p 4222 & broker=$!; echo $broker > /tmp/nats.pid; wait $broker; done")) {
            broker.start();
            String url = "nats://" + broker.getHost() + ":" + broker.getMappedPort(4222);
            CountDownLatch pubDisconnected = new CountDownLatch(1);
            CountDownLatch pubReconnected = new CountDownLatch(1);
            Options pubOptions = new Options.Builder().server(url).connectionTimeout(Duration.ofSeconds(5))
                    .maxReconnects(-1).reconnectWait(Duration.ofMillis(100))
                    .connectionListener((connection, event) -> {
                        if (event == ConnectionListener.Events.DISCONNECTED) pubDisconnected.countDown();
                        if (event == ConnectionListener.Events.RECONNECTED) pubReconnected.countDown();
                    }).build();

            CountDownLatch subDisconnected = new CountDownLatch(1);
            CountDownLatch subReconnected = new CountDownLatch(1);
            Options subOptions = new Options.Builder().server(url).connectionTimeout(Duration.ofSeconds(5))
                    .maxReconnects(-1).reconnectWait(Duration.ofMillis(100))
                    .connectionListener((connection, event) -> {
                        if (event == ConnectionListener.Events.DISCONNECTED) subDisconnected.countDown();
                        if (event == ConnectionListener.Events.RECONNECTED) subReconnected.countDown();
                    }).build();
            try (Connection publisherConnection = Nats.connect(pubOptions);
                    Connection subscriberConnection = Nats.connect(subOptions)) {
                NatsEventStore publisher = new NatsEventStore(publisherConnection, EventStoreMode.MULTI_CHANNEL, 1L);
                NatsEventStore subscriber = new NatsEventStore(subscriberConnection, EventStoreMode.MULTI_CHANNEL, 2L);
                BlockingQueue<Object> deliveries = new LinkedBlockingQueue<>();
                try {
                    subscriber.subscribe(EventType.DISPATCH, message -> deliveries.add(message.getPacket().getData()),
                            DispatchMessage.class);
                    subscriberConnection.flush(Duration.ofSeconds(5));
                    publish(publisher, "before-restart");
                    publisherConnection.flush(Duration.ofSeconds(5));
                    assertEquals(Collections.singletonList("before-restart"), deliveries.poll(5, TimeUnit.SECONDS));

                    // Restart the actual service while keeping the published Docker port stable.
                    assertEquals(0, broker.execInContainer("sh", "-c",
                            "kill -TERM \"$(cat /tmp/nats.pid)\"").getExitCode());
                    assertTrue(pubDisconnected.await(10, TimeUnit.SECONDS), "Publisher must observe broker loss");
                    assertTrue(subDisconnected.await(10, TimeUnit.SECONDS), "Subscriber must observe broker loss");
                    assertTrue(pubReconnected.await(15, TimeUnit.SECONDS), "Publisher must actually reconnect");
                    assertTrue(subReconnected.await(15, TimeUnit.SECONDS), "Subscriber must actually reconnect");
                    subscriberConnection.flush(Duration.ofSeconds(5));
                    publisherConnection.flush(Duration.ofSeconds(5));
                    publish(publisher, "after-restart");
                    publish(publisher, "barrier");
                    publisherConnection.flush(Duration.ofSeconds(5));
                    assertEquals(Collections.singletonList("after-restart"), deliveries.poll(15, TimeUnit.SECONDS));
                    assertEquals(Collections.singletonList("barrier"), deliveries.poll(15, TimeUnit.SECONDS));
                    assertTrue(deliveries.isEmpty(), "Ordered wire barrier must not hide duplicate delivery");
                } finally {
                    TestResourceCleanup.runAll("NATS restart event stores", publisher::shutdown, subscriber::shutdown);
                }
            }
        }
    }

    private static void publish(NatsEventStore store, String value) {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setName("restart-event");
        packet.setData(Collections.singletonList(value));
        store.publish(EventType.DISPATCH, new DispatchMessage("room", packet, "/"));
    }
}
