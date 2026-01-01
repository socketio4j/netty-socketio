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

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:18 pm
 */
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.CustomizedNatsContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;
import com.socketio4j.socketio.store.nats_pubsub.NatsEventStore;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedNATSMultiChannelMemoryTest extends DistributedCommonTest {

    private static final CustomizedNatsContainer NATS_CONTAINER =
            new CustomizedNatsContainer();

    private Connection nc;
    private Connection nc1;
    // -------------------------------------------
    // Utility
    // -------------------------------------------

    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeAll
    public void setup() throws Exception {

        NATS_CONTAINER.start();
        String bootstrap = NATS_CONTAINER.getNatsUrl();

        // ---------- NODE 1 ----------
        Configuration cfg1 = new Configuration();
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(findAvailablePort());
        Options options = new Options.Builder()
                .server(bootstrap)
                .connectionTimeout(Duration.ofSeconds(2))
                .maxReconnects(-1)
                .reconnectWait(Duration.ofMillis(500))
                .pingInterval(Duration.ofSeconds(10))
                .maxPingsOut(3)
                .build();


        nc = Nats.connect(options);
        cfg1.setStoreFactory(
                new MemoryStoreFactory(
                        new NatsEventStore(nc, EventStoreMode.MULTI_CHANNEL, null)
                )
        );

        node1 = new SocketIOServer(cfg1);
        node1.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);
            c.sendEvent("join-ok", "OK");
        });
        node1.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node1.addEventListener("get-my-rooms", String.class, (client, data, ackSender) -> {
            if (ackSender.isAckRequested()){
                ackSender.sendAckData(client.getAllRooms());
            }
        });
        node1.addConnectListener(client -> {

            Map<String, List<String>> params =
                    client.getHandshakeData().getUrlParams();

            List<String> joinParams = params.get("join");
            if (joinParams == null || joinParams.isEmpty()) {
                return;
            }

            // Convert to Set to avoid duplicates
            Set<String> rooms = joinParams.stream()
                    .flatMap(v -> Arrays.stream(v.split(","))) // supports join=a,b
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            rooms.forEach(client::joinRoom);
        });
        node1.start();
        port1 = cfg1.getPort();

        // ---------- NODE 2 ----------
        Configuration cfg2 = new Configuration();
        cfg2.setHostname("127.0.0.1");
        cfg2.setPort(findAvailablePort());
        Options options1 = new Options.Builder()
                .server(bootstrap)
                .connectionTimeout(Duration.ofSeconds(2))
                .maxReconnects(-1)
                .reconnectWait(Duration.ofMillis(500))
                .pingInterval(Duration.ofSeconds(10))
                .maxPingsOut(3)
                .build();

        nc1 = Nats.connect(options1);
        cfg2.setStoreFactory(
                new MemoryStoreFactory(
                        new NatsEventStore(nc1, EventStoreMode.MULTI_CHANNEL, null)
                )
        );

        node2 = new SocketIOServer(cfg2);
        node2.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);

            c.sendEvent("join-ok", "OK");
        });
        node2.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node2.addEventListener("get-my-rooms", String.class, (client, data, ackSender) ->{
            if (ackSender.isAckRequested()){
                ackSender.sendAckData(client.getAllRooms());
            }
        });
        node2.addConnectListener(client -> {

            Map<String, List<String>> params =
                    client.getHandshakeData().getUrlParams();

            List<String> joinParams = params.get("join");
            if (joinParams == null || joinParams.isEmpty()) {
                return;
            }

            // Convert to Set to avoid duplicates
            Set<String> rooms = joinParams.stream()
                    .flatMap(v -> Arrays.stream(v.split(","))) // supports join=a,b
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            rooms.forEach(client::joinRoom);
        });
        node2.start();
        port2 = cfg2.getPort();
    }


    // -------------------------------------------
    // Teardown
    // -------------------------------------------

    @AfterAll
    public void stop() {

        if(nc != null) {
            try {
                nc.close();
            } catch (InterruptedException ignored) {
             
            }
        }
        if (nc1 != null) {
            try {
                nc1.close();
            } catch (InterruptedException ignored) {
        
            }
        }

        if (node1 != null) {
            node1.stop();
        }
        if (node2 != null) {
            node2.stop();
        }

        NATS_CONTAINER.stop();
    
    }
}
