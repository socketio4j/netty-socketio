package com.socketio4j.example.core;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.AckMode;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.store.hazelcast.HazelcastStoreFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class CoreExampleMain {

    private static final Logger log =
            LoggerFactory.getLogger(CoreExampleMain.class);

    public static void main(String[] args) {
        log.info("Starting Clustered Netty Socket.IO Example Servers...");



        // 1. Configure Hazelcast for Server 1
        Config hzConfig1 = new Config();
        hzConfig1.setInstanceName("hz1");
        hzConfig1.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hzConfig1.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember("127.0.0.1");
        hzConfig1.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");

        // 2. Configure Hazelcast for Server 2
        Config hzConfig2 = new Config();
        hzConfig2.setInstanceName("hz2");
        hzConfig2.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hzConfig2.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember("127.0.0.1");
        hzConfig2.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");

        HazelcastInstance hz1 = Hazelcast.newHazelcastInstance(hzConfig1);
        HazelcastInstance hz2 = Hazelcast.newHazelcastInstance(hzConfig2);

        // ----------------------------------------------------------------

        // 3. Configure Server 1 on Port 4000
        Configuration config1 = new Configuration();
        config1.setHostname("127.0.0.1");
        config1.setPort(4000);
        config1.setMetricsEnabled(false);
        config1.setAckMode(AckMode.AUTO_SUCCESS_ONLY);
        config1.setStoreFactory(new HazelcastStoreFactory(hz1));
        SocketIOServer server1 = new SocketIOServer(config1);
        setupServer(server1);

        // 4. Configure Server 2 on Port 4001
        Configuration config2 = new Configuration();
        config2.setHostname("127.0.0.1");
        config2.setPort(4001);
        config2.setMetricsEnabled(false);
        config2.setAckMode(AckMode.AUTO_SUCCESS_ONLY);
        config2.setStoreFactory(new HazelcastStoreFactory(hz2));
        SocketIOServer server2 = new SocketIOServer(config2);
        setupServer(server2);

        server1.start();
        server2.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                log.info("Shutting down servers and cluster...");
                server1.stop();
                server2.stop();
                hz1.shutdown();
                hz2.shutdown();
            }
        });

        log.info("Server 1 listening @ http://localhost:4000");
        log.info("Server 2 listening @ http://localhost:4001");
    }

    private static void setupServer(SocketIOServer server) {
        server.addNamespace("/example");

        // ── /example namespace ───────────────────────────────────────────────

        server.getNamespace("/example").addConnectListener(client -> {
            String sessionRoom = client.getSessionId().toString();
            client.joinRoom(sessionRoom);
            client.joinRoom("room1");
            client.joinRoom("room2");
            log.info("[/example] connected: sessionId={}", client.getSessionId());
            // Send welcome directly to the client — avoids a race between
            // joinRoom and getRoomOperations which can lose the event for EIOv4.
            client.sendEvent("welcome", "your private room is " + sessionRoom);
        });

        server.getNamespace("/example").addDisconnectListener(client ->
            log.info("[/example] disconnected: sessionId={}", client.getSessionId())
        );

        // Binary echo on /example — echo back the exact bytes received
        server.getNamespace("/example").addEventListener("hi", byte[].class, (client, data, ack) -> {
            log.debug("[/example] hi from {}: {} byte(s)", client.getSessionId(), data.length);
            client.sendEvent("hello", data);
        });

        // ── Default namespace / ──────────────────────────────────────────────

        server.addConnectListener(client -> {
            String sessionRoom = client.getSessionId().toString();
            client.joinRoom(sessionRoom);
            client.joinRoom("room1");
            log.info("[/] connected: sessionId={}", client.getSessionId());
            // Send welcome directly to the client — avoids a race between
            // joinRoom and getRoomOperations which can lose the event for EIOv4.
            client.sendEvent("welcome", "your private room is " + sessionRoom);
        });

        server.addDisconnectListener(client ->
            log.info("[/] disconnected: sessionId={}", client.getSessionId())
        );

        // ── Text ping-pong (private reply to sender) ─────────────────────────

        server.addEventListener("ping-me", String.class, (client, data, ack) -> {
            String room = client.getSessionId().toString();
            log.debug("[/] ping-me from {}: {}", client.getSessionId(), data);
            server.getRoomOperations(room).sendEvent("pong", "pong: " + data);
        });

        // ── Binary echo on / — echo back exact bytes ─────────────────────────

        server.addEventListener("hi", byte[].class, (client, data, ack) -> {
            log.debug("[/] hi from {}: {} byte(s)", client.getSessionId(), data.length);
            client.sendEvent("hello", data);
        });

        // ── Distributed broadcast: text to room1 ─────────────────────────────

        server.addEventListener("broadcast-msg", String.class, (client, data, ack) -> {
            log.info("[/] broadcast-msg from {}: {}", client.getSessionId(), data);
            server.getRoomOperations("room1").sendEvent("room-event", data);
        });

        // ── Distributed broadcast: binary to room1 ───────────────────────────

        server.addEventListener("broadcast-binary", byte[].class, (client, data, ack) -> {
            log.info("[/] broadcast-binary from {}: {} byte(s)", client.getSessionId(), data.length);
            server.getRoomOperations("room1").sendEvent("room-binary-event", data);
        });

        // ── Custom room management ────────────────────────────────────────────

        /**
         * join-custom-room   payload: room name
         * Server joins client to room and sends back 'joined' confirmation.
         * Compatible with test-client.js "join-custom-room" flow.
         */
        server.addEventListener("join-custom-room", String.class, (client, data, ack) -> {
            if (data == null || data.isBlank()) {
                log.warn("[/] join-custom-room: empty room name from {}", client.getSessionId());
                return;
            }
            log.info("[/] join-custom-room: {} → {}", client.getSessionId(), data);
            client.joinRoom(data);
            client.sendEvent("joined", data);
        });

        /**
         * join-room          payload: room name
         * Alias used by DistributedCommonTest — sends back 'join-ok' confirmation.
         */
        server.addEventListener("join-room", String.class, (client, data, ack) -> {
            if (data == null || data.isBlank()) {
                log.warn("[/] join-room: empty room name from {}", client.getSessionId());
                return;
            }
            log.info("[/] join-room: {} → {}", client.getSessionId(), data);
            client.joinRoom(data);
            client.sendEvent("join-ok", "OK");
        });

        /**
         * leave-custom-room  payload: room name
         * Server removes client from room and sends back 'left' confirmation.
         */
        server.addEventListener("leave-custom-room", String.class, (client, data, ack) -> {
            if (data == null || data.isBlank()) {
                log.warn("[/] leave-custom-room: empty room name from {}", client.getSessionId());
                return;
            }
            log.info("[/] leave-custom-room: {} ← {}", client.getSessionId(), data);
            client.leaveRoom(data);
            client.sendEvent("left", data);
        });

        /**
         * leave-room         payload: room name
         * Alias used by DistributedCommonTest — sends back 'leave-ok' confirmation.
         */
        server.addEventListener("leave-room", String.class, (client, data, ack) -> {
            if (data == null || data.isBlank()) {
                log.warn("[/] leave-room: empty room name from {}", client.getSessionId());
                return;
            }
            log.info("[/] leave-room: {} ← {}", client.getSessionId(), data);
            client.leaveRoom(data);
            client.sendEvent("leave-ok", "OK");
        });

        /**
         * get-my-rooms  payload: ignored
         * Acks with a JSON array of the rooms the calling client is currently in.
         * Used by DistributedCommonTest.testConnectAndJoinDifferentRoomTest.
         */
        server.addEventListener("get-my-rooms", String.class, (client, data, ack) -> {
            List<String> rooms = new ArrayList<>(client.getAllRooms());
            log.info("[/] get-my-rooms for {}: {}", client.getSessionId(), rooms);
            if (ack != null && ack.isAckRequested()) {
                ack.sendAckData(rooms);
            }
        });

        // ── Custom room broadcast ─────────────────────────────────────────────

        /**
         * broadcast-custom-room  payload: "<roomName>:<message>"
         * Broadcasts <message> to all members of <roomName>.
         * Guards against malformed payloads (no ':' separator).
         */
        server.addEventListener("broadcast-custom-room", String.class, (client, data, ack) -> {
            if (data == null || !data.contains(":")) {
                log.warn("[/] broadcast-custom-room: malformed payload from {}: '{}'",
                        client.getSessionId(), data);
                return;
            }
            String[] parts   = data.split(":", 2);
            String roomName  = parts[0];
            String message   = parts[1];
            log.info("[/] broadcast-custom-room: {} → room '{}': {}",
                    client.getSessionId(), roomName, message);
            server.getRoomOperations(roomName).sendEvent("custom-room-event", message);
        });

        // ── Exclude-sender broadcast ──────────────────────────────────────────

        server.addEventListener("broadcast-exclude-sender", String.class, (client, data, ack) -> {
            log.info("[/] broadcast-exclude-sender from {}: {}", client.getSessionId(), data);
            server.getRoomOperations("room1").sendEvent("exclude-event", client, data);
        });

        // ── JSON round-trip ───────────────────────────────────────────────────

        server.addEventListener("json-event", CustomMessage.class, (client, data, ack) -> {
            log.info("[/] json-event from {}: id={} message={} ts={}",
                    client.getSessionId(), data.getId(), data.getMessage(), data.getTimestamp());
            server.getRoomOperations("room1").sendEvent("custom-json-response", data);
        });
    }

    public static class CustomMessage implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private int id;
        private String message;
        private long timestamp;

        public CustomMessage() {}

        public CustomMessage(int id, String message, long timestamp) {
            this.id = id;
            this.message = message;
            this.timestamp = timestamp;
        }

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}
