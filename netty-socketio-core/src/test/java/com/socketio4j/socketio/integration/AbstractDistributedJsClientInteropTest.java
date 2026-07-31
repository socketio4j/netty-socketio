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

    private static final java.util.Set<JsClientProcess> ALL_ACTIVE_PROCESSES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (JsClientProcess p : ALL_ACTIVE_PROCESSES) {
                try {
                    if (p != null && p.isAlive()) {
                        p.destroyForcibly();
                    }
                } catch (Exception ignored) {}
            }
        }));
    }

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
            try {
                client.joinRoom(roomName);
                client.sendEvent("join-ok", roomName);
            } catch (Exception e) {
                System.err.println("Error joining room " + roomName + " for client " + client.getSessionId() + ": " + e.getMessage());
            }
        });
        server.addEventListener("leave-room", String.class, (client, roomName, ackRequest) -> {
            try {
                client.leaveRoom(roomName);
                client.sendEvent("leave-ok", roomName);
            } catch (Exception e) {
                System.err.println("Error leaving room " + roomName + " for client " + client.getSessionId() + ": " + e.getMessage());
            }
        });
    }

    /**
     * Waits for cluster-wide room membership on BOTH nodes to reach {@code expected}.
     * Fails fast if any JS client process terminates prematurely with an error.
     */
    protected void awaitRoomSync(String room, int expected) throws InterruptedException {
        awaitRoomSync(room, expected, null);
    }

    protected void awaitRoomSync(String room, int expected, List<JsClientProcess> processes) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        int stableTicks = 0;

        Namespace ns1 = node1 != null ? (Namespace) node1.getNamespace("") : null;
        Namespace ns2 = node2 != null ? (Namespace) node2.getNamespace("") : null;

        while (System.currentTimeMillis() < deadline) {
            int n1 = ns1 != null ? ns1.getRoomClientsInCluster(room) : 0;
            int n2 = ns2 != null ? ns2.getRoomClientsInCluster(room) : 0;

            // Fail fast if any JS process exited with an error status during sync
            if (processes != null) {
                for (JsClientProcess p : processes) {
                    if (!p.isAlive() && p.exitValue() != 0) {
                        failFastOnClientFailure(room, expected, n1, n2, processes, p);
                    }
                }
            }

            if (n1 == expected && n2 == expected) {
                if (++stableTicks >= 3) return;
            } else {
                stableTicks = 0;
            }
            Thread.sleep(20);
        }

        int n1 = ns1 != null ? ns1.getRoomClientsInCluster(room) : 0;
        int n2 = ns2 != null ? ns2.getRoomClientsInCluster(room) : 0;

        StringBuilder diag = new StringBuilder();
        diag.append(String.format("Room '%s' sync timed out! Expected %d clients on each node.\n", room, expected));
        diag.append(String.format("  Node 1 (port %d): totalClients=%d, localRoomClients=%d, clusterRoomClients=%d\n",
                port1,
                node1 != null ? countClients(node1.getAllClients()) : -1,
                ns1 != null ? countClients(ns1.getRoomClients(room)) : -1,
                n1));
        diag.append(String.format("  Node 2 (port %d): totalClients=%d, localRoomClients=%d, clusterRoomClients=%d\n",
                port2,
                node2 != null ? countClients(node2.getAllClients()) : -1,
                ns2 != null ? countClients(ns2.getRoomClients(room)) : -1,
                n2));

        if (processes != null && !processes.isEmpty()) {
            diag.append("\nJS Client Process Statuses:\n");
            for (JsClientProcess p : processes) {
                boolean alive = p.isAlive();
                int exitCode = alive ? -1 : p.exitValue();
                diag.append(String.format("  - %s (v%s, %s, port %d): %s (exitCode=%d)\n",
                        p.getName(), p.getVersion(), p.getTransport(), p.getPort(),
                        alive ? "RUNNING" : "EXITED", exitCode));
            }

            diag.append("\nJS Client Output Logs:\n");
            for (JsClientProcess p : processes) {
                String logs = p.getLogOutput().trim();
                if (!logs.isEmpty()) {
                    diag.append("--- Log for ").append(p.getName()).append(" ---\n");
                    diag.append(logs).append("\n");
                }
            }
        }

        fail(diag.toString());
    }

    private void failFastOnClientFailure(String room, int expected, int n1, int n2,
                                         List<JsClientProcess> processes, JsClientProcess failedProcess) {
        StringBuilder diag = new StringBuilder();
        diag.append(String.format("FAIL-FAST: JS Client process '%s' (v%s, %s, port %d) exited unexpectedly with status %d during awaitRoomSync for room '%s' (expected %d, got node1=%d / node2=%d)!\n",
                failedProcess.getName(), failedProcess.getVersion(), failedProcess.getTransport(),
                failedProcess.getPort(), failedProcess.exitValue(), room, expected, n1, n2));

        diag.append("\nFailed Process Log:\n");
        diag.append(failedProcess.getLogOutput());

        diag.append("\nAll Processes Statuses:\n");
        for (JsClientProcess p : processes) {
            boolean alive = p.isAlive();
            int exitCode = alive ? -1 : p.exitValue();
            diag.append(String.format("  - %s (v%s, %s, port %d): %s (exitCode=%d)\n",
                    p.getName(), p.getVersion(), p.getTransport(), p.getPort(),
                    alive ? "RUNNING" : "EXITED", exitCode));
        }

        fail(diag.toString());
    }

    /**
     * Helper to launch all 16 client matrix combinations (4 versions x 2 transports x 2 servers).
     */
    protected List<JsClientProcess> launchFullClientMatrix(String scenario, String room) throws Exception {
        List<JsClientProcess> processes = new ArrayList<>();
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

    protected void verifyAndCleanUpProcesses(List<JsClientProcess> processes, long timeoutSeconds) throws Exception {
        try {
            for (JsClientProcess p : processes) {
                boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    fail(String.format("JS Client process '%s' (v%s, %s, port %d) timed out after %d seconds!\nLog output:\n%s",
                            p.getName(), p.getVersion(), p.getTransport(), p.getPort(), timeoutSeconds, p.getLogOutput()));
                }
                if (p.exitValue() != 0) {
                    fail(String.format("JS Client process '%s' (v%s, %s, port %d) exited with non-zero status code %d!\nLog output:\n%s",
                            p.getName(), p.getVersion(), p.getTransport(), p.getPort(), p.exitValue(), p.getLogOutput()));
                }
            }
        } finally {
            for (JsClientProcess p : processes) {
                p.destroyForcibly();
            }
        }
    }

    /**
     * POSITIVE TEST 1: Distributed Room Broadcast across 2 Servers & 16 JS Clients.
     */
    @DisplayName("Positive 1 - Multi-Node Room Broadcast (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedRoomBroadcast_Positive() throws Exception {
        final String room = "ClusterRoomAlpha_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_room_broadcast", room);
        try {
            awaitRoomSync(room, 16, processes);

            node1.getRoomOperations(room).sendEvent("dist-event", "msg_from_server1");
            Thread.sleep(500);
            node2.getRoomOperations(room).sendEvent("dist-event", "msg_from_server2");

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
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
        List<JsClientProcess> processes = new ArrayList<>();

        try {
            for (String v : versions) {
                for (String t : transports) {
                    processes.add(launchJsClient("n1_red_v" + v + "_" + t, v, port1, t, "dist_room_isolation_negative", roomRed));
                }
            }
            for (String v : versions) {
                for (String t : transports) {
                    processes.add(launchJsClient("n2_blue_v" + v + "_" + t, v, port2, t, "dist_room_isolation_negative", roomBlue));
                }
            }

            awaitRoomSync(roomRed, 8, processes);
            awaitRoomSync(roomBlue, 8, processes);

            node1.getRoomOperations(roomRed).sendEvent("dist-event", "red_only_message");
            Thread.sleep(500);
            node2.getRoomOperations(roomBlue).sendEvent("dist-event", "blue_only_message");
            Thread.sleep(500);

            node1.getBroadcastOperations().sendEvent("dist-test-done", "isolation_check");

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
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
        List<JsClientProcess> processes = new ArrayList<>();

        java.util.concurrent.atomic.AtomicInteger leftCount = new java.util.concurrent.atomic.AtomicInteger(0);
        com.socketio4j.socketio.listener.DataListener<String> leftListener = (client, data, ackRequest) -> leftCount.incrementAndGet();
        node2.addEventListener("client-left-room", String.class, leftListener);

        try {
            for (String v : versions) {
                for (String t : transports) {
                    processes.add(launchJsClient("n2_leave_v" + v + "_" + t, v, port2, t, "dist_room_leave_negative", roomGreen));
                }
            }

            awaitRoomSync(roomGreen, 8, processes);

            node2.getBroadcastOperations().sendEvent("leave-command", roomGreen);

            long deadline = System.currentTimeMillis() + 10000;
            while (leftCount.get() < 8 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(8, leftCount.get(), "All 8 clients should acknowledge leaving roomGreen");

            node1.getRoomOperations(roomGreen).sendEvent("dist-event", "post_leave_message");
            Thread.sleep(500);

            node2.getBroadcastOperations().sendEvent("dist-test-done", "room_leave_check");

            verifyAndCleanUpProcesses(processes, 15);
        } finally {
            node2.removeAllListeners("client-left-room");
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    /**
     * POSITIVE TEST 4: Multi-Node Global Broadcast across 2 Servers & 16 JS Clients.
     */
    @DisplayName("Positive 4 - Cluster Global Broadcast (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedGlobalBroadcast_Positive() throws Exception {
        final String syncRoom = "SyncGlobalRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_global_broadcast", syncRoom);
        try {
            awaitRoomSync(syncRoom, 16, processes);

            node2.getBroadcastOperations().sendEvent("global-event", "cluster_global_ping");

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    /**
     * POSITIVE TEST 5: Multi-Node Distributed Binary Payload (byte[]) across 16 JS Clients.
     */
    @DisplayName("Positive 5 - Cluster Binary Payload (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedBinaryPayload_Positive() throws Exception {
        final String room = "ClusterBinaryRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_binary", room);
        try {
            awaitRoomSync(room, 16, processes);

            node1.getRoomOperations(room).sendEvent("dist-event", new byte[]{10, 20, 30, 40, 50});

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    /**
     * POSITIVE TEST 6: Multi-Node Distributed JSON / Typed Object Payload across 16 JS Clients.
     */
    @DisplayName("Positive 6 - Cluster Object/POJO Payload (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedObjectPayload_Positive() throws Exception {
        final String room = "ClusterObjectRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_object", room);
        try {
            awaitRoomSync(room, 16, processes);

            node1.getRoomOperations(room).sendEvent("dist-event", new ClusterPayload("cluster_pojo", 42));

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    /**
     * POSITIVE TEST 7: Multi-Node Distributed Mixed Multi-Type Payload across 16 JS Clients.
     */
    @DisplayName("Positive 7 - Cluster Mixed Multi-Type Payload (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedMixedPayload_Positive() throws Exception {
        final String room = "ClusterMixedRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_mixed", room);
        try {
            awaitRoomSync(room, 16, processes);

            java.util.Map<String, Object> mapObj = new java.util.HashMap<>();
            mapObj.put("value", 99);

            node1.getRoomOperations(room).sendEvent("dist-event", "hello_cluster", new byte[]{1, 2, 3}, mapObj);

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    /**
     * POSITIVE TEST 8: Multi-Node Distributed Real-Life Multi-Level Complex POJO Payload across 16 JS Clients.
     */
    @DisplayName("Positive 8 - Cluster Real-Life Multi-Level Complex POJO (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedComplexObjectPayload_Positive() throws Exception {
        final String room = "ClusterComplexObjectRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_complex_object", room);
        try {
            awaitRoomSync(room, 16, processes);

            ClusterOrderPayload order = new ClusterOrderPayload(
                    "ORD-CLUSTER-12345",
                    299.99,
                    new ClusterCustomer("CUST-VIP-777", "vip@cluster.io", true),
                    java.util.Arrays.asList(
                            new ClusterOrderItem("SKU-CLUSTER-A", 1, 199.99),
                            new ClusterOrderItem("SKU-CLUSTER-B", 2, 50.00)
                    ),
                    java.util.Collections.singletonMap("region", "us-east-1")
            );

            node1.getRoomOperations(room).sendEvent("dist-event", order);

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    /**
     * POSITIVE TEST 9: Multi-Node Server-Initiated Distributed Text ACK Callbacks across 16 JS Clients.
     */
    @DisplayName("Positive 9 - Cluster Text ACK Callbacks (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedAckText_Positive() throws Exception {
        final String room = "ClusterAckTextRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_ack_text", room);
        try {
            awaitRoomSync(room, 16, processes);

            java.util.concurrent.atomic.AtomicInteger ackCounter = new java.util.concurrent.atomic.AtomicInteger(0);

            for (com.socketio4j.socketio.SocketIOClient client : node1.getAllClients()) {
                client.sendEvent("distAckTextReq", new com.socketio4j.socketio.AckCallback<String>(String.class, 10) {
                    @Override
                    public void onSuccess(String result) {
                        if (result != null && result.startsWith("ack_reply_")) {
                            ackCounter.incrementAndGet();
                        }
                    }
                }, "hello_ack_node1");
            }

            for (com.socketio4j.socketio.SocketIOClient client : node2.getAllClients()) {
                client.sendEvent("distAckTextReq", new com.socketio4j.socketio.AckCallback<String>(String.class, 10) {
                    @Override
                    public void onSuccess(String result) {
                        if (result != null && result.startsWith("ack_reply_")) {
                            ackCounter.incrementAndGet();
                        }
                    }
                }, "hello_ack_node2");
            }

            verifyAndCleanUpProcesses(processes, 25);
            assertEquals(16, ackCounter.get(), "Server should receive text ACK replies from all 16 cluster clients");
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    /**
     * POSITIVE TEST 10: Multi-Node Server-Initiated Distributed Binary ACK Callbacks across 16 JS Clients.
     */
    @DisplayName("Positive 10 - Cluster Binary ACK Callbacks (16 Clients: v1-v4 x WS/Polling x 2 Servers)")
    @Test
    public void testDistributedAckBinary_Positive() throws Exception {
        final String room = "ClusterAckBinaryRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_ack_binary", room);
        try {
            awaitRoomSync(room, 16, processes);

            java.util.concurrent.atomic.AtomicInteger ackCounter = new java.util.concurrent.atomic.AtomicInteger(0);

            for (com.socketio4j.socketio.SocketIOClient client : node1.getAllClients()) {
                client.sendEvent("distAckBinaryReq", new com.socketio4j.socketio.AckCallback<byte[]>(byte[].class, 10) {
                    @Override
                    public void onSuccess(byte[] result) {
                        if (result != null && result.length == 3 && result[0] == 10 && result[1] == 20 && result[2] == 30) {
                            ackCounter.incrementAndGet();
                        }
                    }
                }, "hello_bin_ack_node1");
            }

            for (com.socketio4j.socketio.SocketIOClient client : node2.getAllClients()) {
                client.sendEvent("distAckBinaryReq", new com.socketio4j.socketio.AckCallback<byte[]>(byte[].class, 10) {
                    @Override
                    public void onSuccess(byte[] result) {
                        if (result != null && result.length == 3 && result[0] == 10 && result[1] == 20 && result[2] == 30) {
                            ackCounter.incrementAndGet();
                        }
                    }
                }, "hello_bin_ack_node2");
            }

            verifyAndCleanUpProcesses(processes, 25);
            assertEquals(16, ackCounter.get(), "Server should receive binary ACK replies from all 16 cluster clients");
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    protected JsClientProcess launchJsClient(String name, String version, int port,
                                           String transport, String scenario, String room) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "node", jsScript.getAbsolutePath(),
                "--clientName=" + name,
                "--version=" + version,
                "--port=" + port,
                "--transport=" + transport,
                "--scenario=" + scenario,
                "--room=" + room,
                "--timeout=35000"
        );
        pb.directory(jsDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        JsClientProcess wrapper = new JsClientProcess(name, version, port, transport, scenario, room, process);
        ALL_ACTIVE_PROCESSES.add(wrapper);
        return wrapper;
    }

    private int countClients(Iterable<com.socketio4j.socketio.SocketIOClient> clients) {
        if (clients == null) return -1;
        if (clients instanceof java.util.Collection) {
            return ((java.util.Collection<?>) clients).size();
        }
        int count = 0;
        for (Object unused : clients) {
            count++;
        }
        return count;
    }

    public static class JsClientProcess {
        private final String name;
        private final String version;
        private final int port;
        private final String transport;
        private final String scenario;
        private final String room;
        private final Process process;
        private final StringBuilder logOutput = new StringBuilder();
        private final Thread logThread;

        public JsClientProcess(String name, String version, int port, String transport,
                               String scenario, String room, Process process) {
            this.name = name;
            this.version = version;
            this.port = port;
            this.transport = transport;
            this.scenario = scenario;
            this.room = room;
            this.process = process;

            this.logThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (logOutput) {
                            logOutput.append(line).append("\n");
                        }
                        System.out.println("[JS-" + name + "] " + line);
                    }
                } catch (Exception ignored) {}
            });
            this.logThread.setDaemon(true);
            this.logThread.start();
        }

        public String getName() { return name; }
        public String getVersion() { return version; }
        public int getPort() { return port; }
        public String getTransport() { return transport; }
        public String getScenario() { return scenario; }
        public String getRoom() { return room; }
        public Process getProcess() { return process; }

        public boolean isAlive() {
            return process.isAlive();
        }

        public int exitValue() {
            return process.exitValue();
        }

        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return process.waitFor(timeout, unit);
        }

        public void destroyForcibly() {
            ALL_ACTIVE_PROCESSES.remove(this);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        public String getLogOutput() {
            synchronized (logOutput) {
                return logOutput.toString();
            }
        }
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

    public static class ClusterOrderPayload implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        @com.fasterxml.jackson.annotation.JsonProperty("orderId")
        public String orderId;
        @com.fasterxml.jackson.annotation.JsonProperty("totalAmount")
        public double totalAmount;
        @com.fasterxml.jackson.annotation.JsonProperty("customer")
        public ClusterCustomer customer;
        @com.fasterxml.jackson.annotation.JsonProperty("items")
        public java.util.List<ClusterOrderItem> items;
        @com.fasterxml.jackson.annotation.JsonProperty("metadata")
        public java.util.Map<String, String> metadata;

        public ClusterOrderPayload() {}
        public ClusterOrderPayload(String orderId, double totalAmount, ClusterCustomer customer,
                                   java.util.List<ClusterOrderItem> items, java.util.Map<String, String> metadata) {
            this.orderId = orderId;
            this.totalAmount = totalAmount;
            this.customer = customer;
            this.items = items;
            this.metadata = metadata;
        }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public double getTotalAmount() { return totalAmount; }
        public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }
        public ClusterCustomer getCustomer() { return customer; }
        public void setCustomer(ClusterCustomer customer) { this.customer = customer; }
        public java.util.List<ClusterOrderItem> getItems() { return items; }
        public void setItems(java.util.List<ClusterOrderItem> items) { this.items = items; }
        public java.util.Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(java.util.Map<String, String> metadata) { this.metadata = metadata; }
    }

    public static class ClusterCustomer implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        @com.fasterxml.jackson.annotation.JsonProperty("customerId")
        public String customerId;
        @com.fasterxml.jackson.annotation.JsonProperty("email")
        public String email;
        @com.fasterxml.jackson.annotation.JsonProperty("vipStatus")
        public boolean vipStatus;

        public ClusterCustomer() {}
        public ClusterCustomer(String customerId, String email, boolean vipStatus) {
            this.customerId = customerId;
            this.email = email;
            this.vipStatus = vipStatus;
        }

        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public boolean isVipStatus() { return vipStatus; }
        public void setVipStatus(boolean vipStatus) { this.vipStatus = vipStatus; }
    }

    public static class ClusterOrderItem implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        @com.fasterxml.jackson.annotation.JsonProperty("sku")
        public String sku;
        @com.fasterxml.jackson.annotation.JsonProperty("quantity")
        public int quantity;
        @com.fasterxml.jackson.annotation.JsonProperty("unitPrice")
        public double unitPrice;

        public ClusterOrderItem() {}
        public ClusterOrderItem(String sku, int quantity, double unitPrice) {
            this.sku = sku;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public double getUnitPrice() { return unitPrice; }
        public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
    }
}
