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

import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.namespace.Namespace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Abstract Multi-Node Distributed Cluster Interoperability Suite with Official JS Clients.
 *
 * <p>Verifies distributed event store & pub-sub memory store propagation across a 16-client matrix:
 * <ul>
 *   <li>Server 1 (Node 1) connected to 8 Clients (v1, v2, v3, v4 x WebSocket & Polling)</li>
 *   <li>Server 2 (Node 2) connected to 8 Clients (v1, v2, v3, v4 x WebSocket & Polling)</li>
 * </ul>
 *
 * <p>Concrete subclasses provide store-factory implementations (Redisson, Hazelcast, etc.).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractDistributedJsClientInteropTest {

    protected SocketIOServer node1;
    protected SocketIOServer node2;

    protected int port1;
    protected int port2;

    protected File jsScript;
    protected File jsDir;

    @BeforeAll
    public abstract void setupCluster() throws Exception;

    @AfterAll
    public abstract void teardownCluster() throws Exception;

    protected void initJsScript() {
        File coreDir = new File(System.getProperty("user.dir"));
        if (!coreDir.getName().equals("netty-socketio-core")) {
            coreDir = new File(coreDir, "netty-socketio-core");
        }
        jsDir = new File(coreDir, "src/test/resources/js-interop");
        jsScript = new File(jsDir, "test-distributed-clients.js");
        assertTrue(jsScript.exists(), "test-distributed-clients.js script must exist");
    }

    protected void attachDefaultRoomListeners(SocketIOServer server) {
        server.addEventListener("join-room", String.class, (client, roomName, ackRequest) -> {
            client.joinRoom(roomName);
            client.sendEvent("join-ok", roomName);
        });
        server.addEventListener("leave-room", String.class, (client, roomName, ackRequest) -> {
            client.leaveRoom(roomName);
            client.sendEvent("leave-ok", roomName);
        });
    }

    /**
     * Waits for cluster-wide room membership on BOTH nodes to reach {@code expected}.
     * Uses {@link Namespace#getRoomClientsInCluster} which counts ALL sessionIds
     * (local + JOIN-propagated). Fails loudly if the deadline is exceeded.
     */
    protected void awaitRoomSync(String room, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        int stableTicks = 0;

        Namespace ns1 = (Namespace) node1.getNamespace("");
        Namespace ns2 = (Namespace) node2.getNamespace("");

        while (System.currentTimeMillis() < deadline) {
            int n1 = ns1.getRoomClientsInCluster(room);
            int n2 = ns2.getRoomClientsInCluster(room);
            if (n1 == expected && n2 == expected) {
                if (++stableTicks >= 3) return;
            } else {
                stableTicks = 0;
            }
            Thread.sleep(20);
        }
        int n1 = ns1.getRoomClientsInCluster(room);
        int n2 = ns2.getRoomClientsInCluster(room);
        fail(String.format("Room '%s' sync timed out: expected %d on each node, got node1=%d / node2=%d",
                room, expected, n1, n2));
    }

    /**
     * Helper to launch all 16 client matrix combinations (4 versions x 2 transports x 2 servers).
     *
     * Node 1 (port1): 8 clients (v1-v4 x websocket/polling)
     * Node 2 (port2): 8 clients (v1-v4 x websocket/polling)
     */
    protected List<Process> launchFullClientMatrix(String scenario, String room) throws Exception {
        List<Process> processes = new ArrayList<>();
        String[] versions = {"1", "2", "3", "4"};
        String[] transports = {"websocket", "polling"};

        // 8 clients on Node 1
        for (String v : versions) {
            for (String t : transports) {
                String name = "n1_v" + v + "_" + t;
                processes.add(launchJsClient(name, v, port1, t, scenario, room));
            }
        }
        // 8 clients on Node 2
        for (String v : versions) {
            for (String t : transports) {
                String name = "n2_v" + v + "_" + t;
                processes.add(launchJsClient(name, v, port2, t, scenario, room));
            }
        }
        return processes;
    }

    /**
     * POSITIVE TEST 1: Distributed Room Broadcast across 2 Servers & 16 JS Clients.
     */
    @DisplayName("Positive 1 - Multi-Node Room Broadcast (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedRoomBroadcast_Positive() throws Exception {
        final String room = "ClusterRoomAlpha_" + System.currentTimeMillis();

        List<Process> processes = launchFullClientMatrix("dist_room_broadcast", room);
        try {
            awaitRoomSync(room, 16);

            node1.getRoomOperations(room).sendEvent("dist-event", "msg_from_server1");
            Thread.sleep(500);
            node2.getRoomOperations(room).sendEvent("dist-event", "msg_from_server2");

            for (Process p : processes) {
                boolean finished = p.waitFor(25, TimeUnit.SECONDS);
                assertTrue(finished, "JS Client process should finish cleanly");
                assertEquals(0, p.exitValue(), "JS Client process should exit with status 0");
            }
        } finally {
            processes.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        }
    }

    /**
     * NEGATIVE TEST 2: Comprehensive Distributed Room Isolation (16 Clients).
     */
    @DisplayName("Negative 2 - Distributed Room Isolation across Cluster (16 Clients)")
    @Test
    public void testDistributedRoomIsolation_Negative() throws Exception {
        final String roomRed  = "RoomRed_"  + System.currentTimeMillis();
        final String roomBlue = "RoomBlue_" + System.currentTimeMillis();

        String[] versions = {"1", "2", "3", "4"};
        String[] transports = {"websocket", "polling"};
        List<Process> processes = new ArrayList<>();

        try {
            for (String v : versions) {
                for (String t : transports) {
                    processes.add(launchJsClient("n1_red_v" + v + "_" + t, v, port1, t, "dist_single_event", roomRed));
                }
            }
            for (String v : versions) {
                for (String t : transports) {
                    processes.add(launchJsClient("n2_blue_v" + v + "_" + t, v, port2, t, "dist_single_event", roomBlue));
                }
            }

            awaitRoomSync(roomRed, 8);
            awaitRoomSync(roomBlue, 8);

            node1.getRoomOperations(roomRed).sendEvent("dist-event", "red_only_message");
            Thread.sleep(500);
            node2.getRoomOperations(roomBlue).sendEvent("dist-event", "blue_only_message");

            for (Process p : processes) {
                boolean finished = p.waitFor(25, TimeUnit.SECONDS);
                assertTrue(finished, "JS Client process should finish cleanly in isolation test");
                assertEquals(0, p.exitValue(), "JS Client process should exit with status 0");
            }
        } finally {
            processes.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        }
    }

    /**
     * NEGATIVE TEST 3: Distributed Room Leave Synchronization (8 Clients).
     */
    @DisplayName("Negative 3 - Distributed Room Leave Synchronization (8 Clients)")
    @Test
    public void testDistributedRoomLeave_Negative() throws Exception {
        final String roomGreen = "RoomGreen_" + System.currentTimeMillis();
        String[] versions = {"1", "2", "3", "4"};
        String[] transports = {"websocket", "polling"};
        List<Process> processes = new ArrayList<>();

        try {
            for (String v : versions) {
                for (String t : transports) {
                    processes.add(launchJsClient("n2_leave_v" + v + "_" + t, v, port2, t, "dist_room_leave_negative", roomGreen));
                }
            }

            awaitRoomSync(roomGreen, 8);

            node2.getBroadcastOperations().sendEvent("leave-command", roomGreen);
            Thread.sleep(1500);

            node1.getRoomOperations(roomGreen).sendEvent("dist-event", "post_leave_message");

            for (Process p : processes) {
                boolean finished = p.waitFor(15, TimeUnit.SECONDS);
                assertTrue(finished, "Client process should finish after negative room leave timeout");
                assertEquals(0, p.exitValue(), "Client should exit with 0 confirming no post-leave event was received");
            }
        } finally {
            processes.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        }
    }

    /**
     * POSITIVE TEST 4: Multi-Node Global Broadcast across 2 Servers & 16 JS Clients.
     */
    @DisplayName("Positive 4 - Cluster Global Broadcast (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedGlobalBroadcast_Positive() throws Exception {
        final String syncRoom = "SyncGlobalRoom_" + System.currentTimeMillis();

        List<Process> processes = launchFullClientMatrix("dist_global_broadcast", syncRoom);
        try {
            awaitRoomSync(syncRoom, 16);

            node2.getBroadcastOperations().sendEvent("global-event", "cluster_global_ping");

            for (Process p : processes) {
                boolean finished = p.waitFor(25, TimeUnit.SECONDS);
                assertTrue(finished, "JS Client process should finish cleanly");
                assertEquals(0, p.exitValue(), "JS Client process should exit with status 0");
            }
        } finally {
            processes.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        }
    }

    /**
     * POSITIVE TEST 5: Multi-Node Distributed Binary Payload (byte[]) across 16 JS Clients.
     */
    @DisplayName("Positive 5 - Cluster Binary Payload (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedBinaryPayload_Positive() throws Exception {
        final String room = "ClusterBinaryRoom_" + System.currentTimeMillis();

        List<Process> processes = launchFullClientMatrix("dist_binary", room);
        try {
            awaitRoomSync(room, 16);

            node1.getRoomOperations(room).sendEvent("dist-event", new byte[]{10, 20, 30, 40, 50});

            for (Process p : processes) {
                boolean finished = p.waitFor(25, TimeUnit.SECONDS);
                assertTrue(finished, "JS Client process should finish cleanly");
                assertEquals(0, p.exitValue(), "JS Client process should exit with status 0");
            }
        } finally {
            processes.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        }
    }

    /**
     * POSITIVE TEST 6: Multi-Node Distributed JSON / Typed Object Payload across 16 JS Clients.
     */
    @DisplayName("Positive 6 - Cluster Object/POJO Payload (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedObjectPayload_Positive() throws Exception {
        final String room = "ClusterObjectRoom_" + System.currentTimeMillis();

        List<Process> processes = launchFullClientMatrix("dist_object", room);
        try {
            awaitRoomSync(room, 16);

            node1.getRoomOperations(room).sendEvent("dist-event", new ClusterPayload("cluster_pojo", 42));

            for (Process p : processes) {
                boolean finished = p.waitFor(25, TimeUnit.SECONDS);
                assertTrue(finished, "JS Client process should finish cleanly");
                assertEquals(0, p.exitValue(), "JS Client process should exit with status 0");
            }
        } finally {
            processes.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        }
    }

    /**
     * POSITIVE TEST 7: Multi-Node Distributed Mixed Multi-Type Payload across 16 JS Clients.
     */
    @DisplayName("Positive 7 - Cluster Mixed Multi-Type Payload (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedMixedPayload_Positive() throws Exception {
        final String room = "ClusterMixedRoom_" + System.currentTimeMillis();

        List<Process> processes = launchFullClientMatrix("dist_mixed", room);
        try {
            awaitRoomSync(room, 16);

            java.util.Map<String, Object> mapObj = new java.util.HashMap<>();
            mapObj.put("value", 99);

            node1.getRoomOperations(room).sendEvent("dist-event", "hello_cluster", new byte[]{1, 2, 3}, mapObj);

            for (Process p : processes) {
                boolean finished = p.waitFor(25, TimeUnit.SECONDS);
                assertTrue(finished, "JS Client process should finish cleanly");
                assertEquals(0, p.exitValue(), "JS Client process should exit with status 0");
            }
        } finally {
            processes.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        }
    }

    protected Process launchJsClient(String name, String version, int port,
                                     String transport, String scenario, String room) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "node", jsScript.getAbsolutePath(),
                "--clientName=" + name,
                "--version=" + version,
                "--port=" + port,
                "--transport=" + transport,
                "--scenario=" + scenario,
                "--room=" + room
        );
        pb.directory(jsDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[JS-" + name + "] " + line);
                }
            } catch (Exception ignored) {}
        }).start();

        return process;
    }

    public static class ClusterPayload implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        @com.fasterxml.jackson.annotation.JsonProperty("name")
        public String name;
        @com.fasterxml.jackson.annotation.JsonProperty("value")
        public int value;

        public ClusterPayload() {}
        public ClusterPayload(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }
}
