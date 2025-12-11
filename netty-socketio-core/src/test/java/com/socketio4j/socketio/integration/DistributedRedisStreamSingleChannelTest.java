package com.socketio4j.socketio.integration;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import com.socketio4j.socketio.AckMode;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.CustomizedRedisContainer;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.redis_stream.RedisStreamsStoreFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedRedisStreamSingleChannelTest extends DistributedCommonTest {

    private static final CustomizedRedisContainer REDIS_CONTAINER = new CustomizedRedisContainer();

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
        REDIS_CONTAINER.start();

        String redisURL = "redis://" + REDIS_CONTAINER.getHost() + ":" + REDIS_CONTAINER.getRedisPort();

        // Standard Redisson config
        Config config = new Config();
        config.useSingleServer().setAddress(redisURL);
        RedissonClient redissonClient = Redisson.create(config);

        // ---------- NODE 1 ----------
        Configuration cfg1 = new Configuration();
        cfg1.setHostname("127.0.0.1");
        cfg1.setPort(findAvailablePort());

        cfg1.setStoreFactory(new RedisStreamsStoreFactory(redissonClient, EventStoreMode.SINGLE_CHANNEL));
        cfg1.setAckMode(AckMode.MANUAL);
        node1 = new SocketIOServer(cfg1);
        node1.addConnectListener(client -> {
            Map<String, List<String>> params = client.getHandshakeData().getUrlParams();
            if (params.containsKey("join")) {
                params.get("join").forEach(client::joinRoom);
            }

        });
        node1.addEventListener("test", String.class, ((client, data, ackSender) -> {
            ackSender.sendAckData("ACK-OK");
        }));
        node1.addEventListener("get-my-rooms", String.class, ((client, data, ackSender) -> {
            ackSender.sendAckData(client.getAllRooms());
        }));
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

        cfg2.setStoreFactory(new RedisStreamsStoreFactory(redissonClient, EventStoreMode.SINGLE_CHANNEL));
        cfg2.setAckMode(AckMode.MANUAL);
        node2 = new SocketIOServer(cfg2);
        node2.addConnectListener(client -> {
            Map<String, List<String>> params = client.getHandshakeData().getUrlParams();
            if (params.containsKey("join")) {
                params.get("join").forEach(client::joinRoom);
            }

        });
        node2.addEventListener("get-my-rooms", String.class, ((client, data, ackSender) -> {
            ackSender.sendAckData(client.getAllRooms());
        }));
        node2.addEventListener("test", String.class, ((client, data, ackSender) -> {
            ackSender.sendAckData("ACK-OK");
        }));
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

        Thread.sleep(2000); // Give servers time to bind and adapters time to initialize
    }


    @AfterAll
    public void stop() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        REDIS_CONTAINER.stop();
    }
}