/*
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
package com.socketio4j.socketio.integration.interop;

import com.socketio4j.socketio.AckCallback;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.listener.DataListener;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.namespace.NamespaceTestReuseAssertions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Abstract Multi-Node Distributed Cluster Interoperability Suite with Official JS Clients.
 * Covers the exact official client-version matrix over WebSocket and polling.
 */

public abstract class AbstractDistributedJsClientInteropTest {

    protected static final int CLIENTS_PER_NODE =
            JsClientInteropMatrix.VERSIONS.size() * JsClientInteropMatrix.TRANSPORTS.size();
    protected static final int FULL_MATRIX_CLIENTS = CLIENTS_PER_NODE * 2;
    private static final long DEFAULT_JS_CLIENT_TIMEOUT_SECONDS = 35;
    private static final long CLIENT_CONFIRM_TIMEOUT_SECONDS = 30;
    private static final long CLIENT_CONFIRM_JS_CLIENT_TIMEOUT_SECONDS = 60;

    private static final java.util.Set<JsClientProcess> ALL_ACTIVE_PROCESSES = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (JsClientProcess p : ALL_ACTIVE_PROCESSES) {
                try {
                    if (p != null && p.isAlive()) {
                        p.destroyForcibly();
                    }
                } catch (Exception error) {
                    System.err.println("Failed to terminate distributed JS process during JVM shutdown: " + error);
                }
            }
        }));
    }

    protected SocketIOServer node1;
    protected SocketIOServer node2;
    protected int port1;
    protected int port2;
    protected File jsScript;
    protected File jsDir;

    private final Map<String, SocketIOClient> connectedClientMap = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Throwable> listenerFailures = new ConcurrentLinkedQueue<>();
    private Set<String> node1BaselineNamespaces;
    private Set<String> node2BaselineNamespaces;

    @BeforeAll
    public abstract void setupCluster() throws Exception;

    @BeforeEach
    void resetPerTestState() {
        captureOrAssertBaselineNamespaces();
        assertNoConnectedClients("before test case");
        connectedClientMap.clear();
        listenerFailures.clear();
    }

    @AfterEach
    void enforcePerTestIsolation() throws Exception {
        Throwable isolationFailure = null;
        try {
            if (!waitForNoConnectedClients(2, TimeUnit.SECONDS)) {
                String retainedClients = describeConnectedClients();
                node1.getBroadcastOperations().disconnect();
                node2.getBroadcastOperations().disconnect();

                // NamespaceClient defers polling cleanup for five seconds when
                // the client has already gone away. Wait beyond that exact
                // grace period so a failing case is fully cleaned before its
                // failure is rethrown and the next case begins.
                if (!waitForNoConnectedClients(6, TimeUnit.SECONDS)) {
                    isolationFailure = new AssertionError(
                            "Distributed interop case left clients connected and forced cleanup did not finish: "
                                    + retainedClients + "; remaining=" + describeConnectedClients());
                } else {
                    isolationFailure = new AssertionError(
                            "Distributed interop case left clients connected after its client processes exited: "
                                    + retainedClients);
                }
            }

            if (isolationFailure == null) {
                assertNoConnectedClients("after test case");
            }
        } catch (Throwable failure) {
            isolationFailure = failure;
        }

        try {
            removeTestCreatedNamespaces();
            clearAndReinstallBaselineListeners();
            captureOrAssertBaselineNamespaces();
            throwIfListenerFailed();
        } catch (Throwable cleanupFailure) {
            if (isolationFailure == null) {
                isolationFailure = cleanupFailure;
            } else {
                isolationFailure.addSuppressed(cleanupFailure);
            }
        } finally {
            connectedClientMap.clear();
            listenerFailures.clear();
        }

        if (isolationFailure != null) {
            rethrow(isolationFailure);
        }
    }

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
        attachDefaultRoomListeners(server.getNamespace(""));
    }

    protected void attachDefaultRoomListeners(com.socketio4j.socketio.SocketIONamespace ns) {
        ns.addEventListener("client-ready", String.class, (client, clientName, ackRequest) -> {
            connectedClientMap.put(clientName, client);
        });
        ns.addEventListener("join-room", String.class, (client, roomName, ackRequest) -> {
            try {
                client.joinRoom(roomName);
                // Private session room for direct 1-to-1 routing across cluster
                client.joinRoom(client.getSessionId().toString());
                client.sendEvent("join-ok", roomName);
            } catch (Exception e) {
                listenerFailures.add(new IllegalStateException(
                        "Could not join room '" + roomName + "' for client " + client.getSessionId(), e));
            }
        });
        ns.addEventListener("leave-room", String.class, (client, roomName, ackRequest) -> {
            try {
                client.leaveRoom(roomName);
                client.sendEvent("leave-ok", roomName);
            } catch (Exception e) {
                listenerFailures.add(new IllegalStateException(
                        "Could not leave room '" + roomName + "' for client " + client.getSessionId(), e));
            }
        });
    }

    private void captureOrAssertBaselineNamespaces() {
        if (node1 == null || node2 == null || !node1.isStarted() || !node2.isStarted()) {
            throw new AssertionError("Distributed interop servers must be running before each test case");
        }

        Set<String> currentNode1Namespaces = namespaceNames(node1);
        Set<String> currentNode2Namespaces = namespaceNames(node2);
        if (node1BaselineNamespaces == null) {
            node1BaselineNamespaces = currentNode1Namespaces;
            node2BaselineNamespaces = currentNode2Namespaces;
            return;
        }

        if (!node1BaselineNamespaces.equals(currentNode1Namespaces)
                || !node2BaselineNamespaces.equals(currentNode2Namespaces)) {
            throw new AssertionError("Distributed interop namespace isolation failed. node1 expected="
                    + node1BaselineNamespaces + ", actual=" + currentNode1Namespaces
                    + "; node2 expected=" + node2BaselineNamespaces
                    + ", actual=" + currentNode2Namespaces);
        }
    }

    private Set<String> namespaceNames(SocketIOServer server) {
        Set<String> names = new HashSet<String>();
        for (com.socketio4j.socketio.SocketIONamespace namespace : server.getAllNamespaces()) {
            names.add(namespace.getName());
        }
        return names;
    }

    private boolean waitForNoConnectedClients(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        do {
            if (allNamespacesAreEmpty(node1) && allNamespacesAreEmpty(node2)) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        } while (System.nanoTime() < deadline);
        return allNamespacesAreEmpty(node1) && allNamespacesAreEmpty(node2);
    }

    private void assertNoConnectedClients(String phase) {
        assertNamespacesEmpty(node1, phase);
        assertNamespacesEmpty(node2, phase);
    }

    private boolean allNamespacesAreEmpty(SocketIOServer server) {
        for (com.socketio4j.socketio.SocketIONamespace namespace : server.getAllNamespaces()) {
            if (!namespace.getAllClients().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void assertNamespacesEmpty(SocketIOServer server, String phase) {
        for (com.socketio4j.socketio.SocketIONamespace namespace : server.getAllNamespaces()) {
            NamespaceTestReuseAssertions.assertEmpty(namespace, phase);
        }
    }

    private String describeConnectedClients() {
        return "node1=" + describeConnectedClients(node1)
                + ", node2=" + describeConnectedClients(node2);
    }

    private String describeConnectedClients(SocketIOServer server) {
        List<String> descriptions = new ArrayList<String>();
        for (com.socketio4j.socketio.SocketIONamespace namespace : server.getAllNamespaces()) {
            if (!namespace.getAllClients().isEmpty()) {
                descriptions.add(namespace.getName() + "=" + namespace.getAllClients());
            }
        }
        return descriptions.toString();
    }

    private void removeTestCreatedNamespaces() {
        removeTestCreatedNamespaces(node1, node1BaselineNamespaces);
        removeTestCreatedNamespaces(node2, node2BaselineNamespaces);
    }

    private void removeTestCreatedNamespaces(SocketIOServer server, Set<String> baselineNamespaces) {
        for (String namespace : new HashSet<String>(namespaceNames(server))) {
            if (!baselineNamespaces.contains(namespace)) {
                server.removeNamespace(namespace);
            }
        }
    }

    private void clearAndReinstallBaselineListeners() {
        clearListeners(node1);
        clearListeners(node2);
        attachDefaultRoomListeners(node1);
        attachDefaultRoomListeners(node2);
    }

    private void clearListeners(SocketIOServer server) {
        for (com.socketio4j.socketio.SocketIONamespace namespace : server.getAllNamespaces()) {
            NamespaceTestReuseAssertions.clearListeners(namespace);
            NamespaceTestReuseAssertions.assertNoListeners(namespace, "after distributed test cleanup");
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw new RuntimeException(failure);
    }

    protected void awaitRoomSync(String room, int expected, List<JsClientProcess> processes) throws InterruptedException {
        awaitRoomSync("", room, expected, processes);
    }

    protected void awaitRoomSync(String namespace, String room, int expected, List<JsClientProcess> processes) throws InterruptedException {
        throwIfListenerFailed();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        int stableTicks = 0;

        Namespace ns1 = node1 != null ? (Namespace) node1.getNamespace(namespace) : null;
        Namespace ns2 = node2 != null ? (Namespace) node2.getNamespace(namespace) : null;

        while (System.currentTimeMillis() < deadline) {
            throwIfListenerFailed();
            checkProcessesAlive(processes, room, expected);

            int n1 = ns1 != null ? ns1.getRoomClientsInCluster(room) : 0;
            int n2 = ns2 != null ? ns2.getRoomClientsInCluster(room) : 0;

            if (n1 == expected && n2 == expected) {
                if (++stableTicks >= 3) {
                    throwIfListenerFailed();
                    return;
                }
            } else {
                stableTicks = 0;
            }
            Thread.sleep(20);
        }
        failWithDiagnostics(room, expected, processes);
    }

    private void checkProcessesAlive(List<JsClientProcess> processes, String room, int expected) {
        if (processes == null) return;
        for (JsClientProcess p : processes) {
            if (!p.isAlive() && p.exitValue() != 0) {
                failFastOnClientFailure(room, expected, processes, p);
            }
        }
    }

    private void throwIfListenerFailed() {
        Throwable failure = listenerFailures.poll();
        if (failure == null) {
            return;
        }
        Throwable additionalFailure;
        while ((additionalFailure = listenerFailures.poll()) != null) {
            failure.addSuppressed(additionalFailure);
        }
        throw new AssertionError("Distributed interop server listener failed", failure);
    }

    private void failFastOnClientFailure(String room, int expected, List<JsClientProcess> processes, JsClientProcess failedProcess) {
        StringBuilder diag = new StringBuilder();
        diag.append(String.format("FAIL-FAST: JS Client process '%s' (v%s, %s, port %d) exited unexpectedly with status %d during execution for room '%s' (expected %d clients)!\n",
                failedProcess.getName(), failedProcess.getVersion(), failedProcess.getTransport(),
                failedProcess.getPort(), failedProcess.exitValue(), room, expected));

        diag.append("\nFailed Process Log:\n").append(failedProcess.getLogOutput());
        fail(diag.toString());
    }

    private void failWithDiagnostics(String room, int expected, List<JsClientProcess> processes) {
        Namespace ns1 = node1 != null ? (Namespace) node1.getNamespace("") : null;
        Namespace ns2 = node2 != null ? (Namespace) node2.getNamespace("") : null;

        StringBuilder diag = new StringBuilder();
        diag.append(String.format("Room '%s' sync timed out! Expected %d clients on each node.\n", room, expected));
        diag.append(String.format("  Node 1 (port %d): totalClients=%d, localRoomClients=%d, clusterRoomClients=%d\n",
                port1,
                node1 != null ? countClients(node1.getAllClients()) : -1,
                ns1 != null ? countClients(ns1.getRoomClients(room)) : -1,
                ns1 != null ? ns1.getRoomClientsInCluster(room) : -1));
        diag.append(String.format("  Node 2 (port %d): totalClients=%d, localRoomClients=%d, clusterRoomClients=%d\n",
                port2,
                node2 != null ? countClients(node2.getAllClients()) : -1,
                ns2 != null ? countClients(ns2.getRoomClients(room)) : -1,
                ns2 != null ? ns2.getRoomClientsInCluster(room) : -1));

        if (processes != null && !processes.isEmpty()) {
            diag.append("\nJS Client Output Logs:\n");
            for (JsClientProcess p : processes) {
                diag.append("--- Log for ").append(p.getName()).append(" ---\n").append(p.getLogOutput()).append("\n");
            }
        }
        fail(diag.toString());
    }

    protected List<JsClientProcess> launchFullClientMatrix(String scenario, String room, Map<String, String> extraArgs) throws Exception {
        return launchFullClientMatrix(scenario, room, extraArgs, DEFAULT_JS_CLIENT_TIMEOUT_SECONDS);
    }

    protected List<JsClientProcess> launchFullClientMatrix(String scenario, String room,
                                                            Map<String, String> extraArgs,
                                                            long clientTimeoutSeconds) throws Exception {
        List<JsClientProcess> processes = new ArrayList<>();
        List<String> versions = JsClientInteropMatrix.VERSIONS;
        List<String> transports = JsClientInteropMatrix.TRANSPORTS;

        for (String v : versions) {
            for (String t : transports) {
                processes.add(launchJsClient("n1_v" + v + "_" + t, v, port1, t, scenario, room,
                        extraArgs, clientTimeoutSeconds));
                processes.add(launchJsClient("n2_v" + v + "_" + t, v, port2, t, scenario, room,
                        extraArgs, clientTimeoutSeconds));
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
            throwIfListenerFailed();
        } finally {
            for (JsClientProcess p : processes) {
                p.destroyForcibly();
            }
        }
    }

    // --- TEST SCENARIOS ---

    @DisplayName("Positive 1 - Multi-Node Room Broadcast with Unique Nonces (exact client matrix)")
    @Test
    public void testDistributedRoomBroadcast_Positive() throws Exception {
        final String room = "ClusterRoomAlpha_" + System.currentTimeMillis();
        final String nonce1 = "NONCE_N1_" + UUID.randomUUID();
        final String nonce2 = "NONCE_N2_" + UUID.randomUUID();

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("nonce1", nonce1);
        extraArgs.put("nonce2", nonce2);

        List<JsClientProcess> processes = launchFullClientMatrix("dist_room_broadcast", room, extraArgs);
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            node1.getRoomOperations(room).sendEvent("dist-event", nonce1);
            node2.getRoomOperations(room).sendEvent("dist-event", nonce2);

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Negative 2 - Distributed Room Isolation with Unique Nonces (exact client matrix)")
    @Test
    public void testDistributedRoomIsolation_Negative() throws Exception {
        final String roomRed = "RoomRed_" + System.currentTimeMillis();
        final String roomBlue = "RoomBlue_" + System.currentTimeMillis();
        final String redNonce = "RED_NONCE_" + UUID.randomUUID();
        final String blueNonce = "BLUE_NONCE_" + UUID.randomUUID();

        List<String> versions = JsClientInteropMatrix.VERSIONS;
        List<String> transports = JsClientInteropMatrix.TRANSPORTS;
        List<JsClientProcess> processes = new ArrayList<>();

        Map<String, String> redArgs = new HashMap<>();
        redArgs.put("expectedNonce", redNonce);

        Map<String, String> blueArgs = new HashMap<>();
        blueArgs.put("expectedNonce", blueNonce);

        CountDownLatch confirmationLatch = new CountDownLatch(FULL_MATRIX_CLIENTS);
        Set<String> expectedClientNames = ConcurrentHashMap.newKeySet();
        Set<String> confirmedClientNames = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<String> unexpectedConfirmations = new ConcurrentLinkedQueue<>();
        DataListener<String> confirmationListener = (client, clientName, ackRequest) -> {
            if (!expectedClientNames.contains(clientName)) {
                unexpectedConfirmations.add(String.valueOf(clientName));
            } else if (confirmedClientNames.add(clientName)) {
                confirmationLatch.countDown();
            }
        };
        node1.addEventListener("room-isolation-confirmed", String.class, confirmationListener);
        node2.addEventListener("room-isolation-confirmed", String.class, confirmationListener);

        try {
            for (String v : versions) {
                for (String t : transports) {
                    processes.add(launchJsClient("n1_red_v" + v + "_" + t, v, port1, t, "dist_room_isolation_negative", roomRed, redArgs));
                    processes.add(launchJsClient("n2_blue_v" + v + "_" + t, v, port2, t, "dist_room_isolation_negative", roomBlue, blueArgs));
                }
            }
            processes.forEach(process -> expectedClientNames.add(process.getName()));

            awaitRoomSync(roomRed, CLIENTS_PER_NODE, processes);
            awaitRoomSync(roomBlue, CLIENTS_PER_NODE, processes);

            node1.getRoomOperations(roomRed).sendEvent("dist-event", redNonce);
            node2.getRoomOperations(roomBlue).sendEvent("dist-event", blueNonce);

            assertTrue(confirmationLatch.await(CLIENT_CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    clientConfirmationTimeoutMessage("room-isolation delivery", expectedClientNames,
                            confirmedClientNames, unexpectedConfirmations, processes));

            node1.getBroadcastOperations().sendEvent("dist-test-done", "isolation_check");

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            node1.removeAllListeners("room-isolation-confirmed");
            node2.removeAllListeners("room-isolation-confirmed");
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Negative 3 - Distributed Room Leave Synchronization (exact client matrix)")
    @Test
    public void testDistributedRoomLeave_Negative() throws Exception {
        final String roomGreen = "RoomGreen_" + System.currentTimeMillis();
        final String postLeaveNonce = "POST_LEAVE_NONCE_" + UUID.randomUUID();

        List<String> versions = JsClientInteropMatrix.VERSIONS;
        List<String> transports = JsClientInteropMatrix.TRANSPORTS;
        List<JsClientProcess> processes = new ArrayList<>();

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("forbiddenNonce", postLeaveNonce);

        CountDownLatch leaveLatch = new CountDownLatch(CLIENTS_PER_NODE);
        DataListener<String> leftListener = (client, data, ackRequest) -> leaveLatch.countDown();
        node2.addEventListener("client-left-room", String.class, leftListener);

        try {
            for (String v : versions) {
                for (String t : transports) {
                    processes.add(launchJsClient("n2_leave_v" + v + "_" + t, v, port2, t, "dist_room_leave_negative", roomGreen, extraArgs));
                }
            }

            awaitRoomSync(roomGreen, CLIENTS_PER_NODE, processes);
            node2.getBroadcastOperations().sendEvent("leave-command", roomGreen);

            assertTrue(leaveLatch.await(10, TimeUnit.SECONDS),
                    "All clients must send client-left-room signal");

            node1.getRoomOperations(roomGreen).sendEvent("dist-event", postLeaveNonce);
            node2.getBroadcastOperations().sendEvent("dist-test-done", "room_leave_check");

            verifyAndCleanUpProcesses(processes, 15);
        } finally {
            node2.removeAllListeners("client-left-room");
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 4 - Cluster Global Broadcast with Unique Nonce (exact client matrix)")
    @Test
    public void testDistributedGlobalBroadcast_Positive() throws Exception {
        final String syncRoom = "SyncGlobalRoom_" + System.currentTimeMillis();
        final String globalNonce = "GLOBAL_PING_" + UUID.randomUUID();

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("globalNonce", globalNonce);

        List<JsClientProcess> processes = launchFullClientMatrix("dist_global_broadcast", syncRoom, extraArgs);
        try {
            awaitRoomSync(syncRoom, FULL_MATRIX_CLIENTS, processes);

            node2.getBroadcastOperations().sendEvent("global-event", globalNonce);

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 5 - Cluster Binary Dynamic Payload Checksum (exact client matrix)")
    @Test
    public void testDistributedBinaryPayload_Positive() throws Exception {
        final String room = "ClusterBinaryRoom_" + System.currentTimeMillis();
        byte[] dynamicPayload = new byte[16];
        new Random().nextBytes(dynamicPayload);

        int checksum = 0;
        for (byte b : dynamicPayload) checksum += (b & 0xFF);

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("checkSum", String.valueOf(checksum));
        extraArgs.put("byteLength", String.valueOf(dynamicPayload.length));

        List<JsClientProcess> processes = launchFullClientMatrix("dist_binary", room, extraArgs);
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            node1.getRoomOperations(room).sendEvent("dist-event", dynamicPayload);

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 6 - Cluster Dynamic Object POJO (exact client matrix)")
    @Test
    public void testDistributedObjectPayload_Positive() throws Exception {
        final String room = "ClusterObjectRoom_" + System.currentTimeMillis();
        final String dynamicName = "pojo_nonce_" + UUID.randomUUID();
        final int dynamicValue = new Random().nextInt(1000000) + 1;

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("expectedName", dynamicName);
        extraArgs.put("expectedValue", String.valueOf(dynamicValue));

        List<JsClientProcess> processes = launchFullClientMatrix("dist_object", room, extraArgs);
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            node1.getRoomOperations(room).sendEvent("dist-event", new ClusterPayload(dynamicName, dynamicValue));

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 7 - Cluster Mixed Multi-Type Dynamic Payload (exact client matrix)")
    @Test
    public void testDistributedMixedPayload_Positive() throws Exception {
        final String room = "ClusterMixedRoom_" + System.currentTimeMillis();
        final String textNonce = "TXT_" + UUID.randomUUID();
        final String mapNonce = "MAP_" + UUID.randomUUID();
        final int mapVal = new Random().nextInt(50000);

        byte[] binData = new byte[]{0x12, 0x34, 0x56, 0x78};

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("textNonce", textNonce);
        extraArgs.put("mapNonce", mapNonce);
        extraArgs.put("mapVal", String.valueOf(mapVal));

        List<JsClientProcess> processes = launchFullClientMatrix("dist_mixed", room, extraArgs);
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            Map<String, Object> mapObj = new HashMap<>();
            mapObj.put("nonce", mapNonce);
            mapObj.put("value", mapVal);

            node1.getRoomOperations(room).sendEvent("dist-event", textNonce, binData, mapObj);

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 8 - Cluster Complex Multi-Level POJO with Dynamic Order Nonce (exact client matrix)")
    @Test
    public void testDistributedComplexObjectPayload_Positive() throws Exception {
        final String room = "ClusterComplexObjectRoom_" + System.currentTimeMillis();
        final String orderId = "ORD-" + UUID.randomUUID();
        final String customerId = "CUST-" + UUID.randomUUID();
        final double amount = 499.95;

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("orderId", orderId);
        extraArgs.put("customerId", customerId);

        List<JsClientProcess> processes = launchFullClientMatrix("dist_complex_object", room, extraArgs);
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            ClusterOrderPayload order = new ClusterOrderPayload(
                    orderId,
                    amount,
                    new ClusterCustomer(customerId, "vip@cluster.io", true),
                    Arrays.asList(
                            new ClusterOrderItem("SKU-CLUSTER-A", 1, 199.95),
                            new ClusterOrderItem("SKU-CLUSTER-B", 3, 100.00)
                    ),
                    java.util.Collections.singletonMap("region", "us-east-1")
            );

            node1.getRoomOperations(room).sendEvent("dist-event", order);

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 9 - Cluster Text ACK Callbacks with Exact Client Matrix")
    @Test
    public void testDistributedAckText_Positive() throws Exception {
        final String room = "ClusterAckTextRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes =
                launchFullClientMatrix("dist_ack_text", room, new HashMap<>());

        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            CountDownLatch ackLatch = new CountDownLatch(FULL_MATRIX_CLIENTS);
            ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

            Consumer<SocketIOClient> sendAckRequest = client -> {
                String nonce = "CHALLENGE_" + client.getSessionId() + "_" + UUID.randomUUID();
                String expectedReply = "ACK_VERIFIED_" + nonce;

                client.sendEvent("distAckTextReq", new AckCallback<String>(String.class, 10) {
                    @Override
                    public void onSuccess(String actualReply) {
                        if (expectedReply.equals(actualReply)) {
                            ackLatch.countDown();
                        } else {
                            failures.add(String.format(
                                    "Client=%s expected='%s' actual='%s'",
                                    client.getSessionId(),
                                    expectedReply,
                                    actualReply));
                        }
                    }

                    @Override
                    public void onTimeout() {
                        failures.add(String.format(
                                "ACK timeout from client %s",
                                client.getSessionId()));
                    }
                }, nonce);
            };

            node1.getAllClients().forEach(sendAckRequest);
            node2.getAllClients().forEach(sendAckRequest);

            assertTrue(
                    ackLatch.await(15, TimeUnit.SECONDS),
                    String.format(
                            "Timed out waiting for ACKs. Received %d/%d.%nFailures:%n%s",
                            FULL_MATRIX_CLIENTS - ackLatch.getCount(),
                            FULL_MATRIX_CLIENTS,
                            String.join("\n", failures)));

            assertTrue(
                    failures.isEmpty(),
                    "ACK payload verification failed:\n" + String.join("\n", failures));

            node1.getBroadcastOperations()
                    .sendEvent("dist-test-done", "ack_text_check");

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 10 - Cluster Binary ACK Callbacks with Exact Client Matrix")
    @Test
    public void testDistributedAckBinary_Positive() throws Exception {
        final String room = "ClusterAckBinaryRoom_" + System.currentTimeMillis();
        List<JsClientProcess> processes = launchFullClientMatrix("dist_ack_binary", room, new HashMap<>());
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            CountDownLatch ackLatch = new CountDownLatch(FULL_MATRIX_CLIENTS);
            AtomicInteger validAcks = new AtomicInteger(0);

            for (SocketIOClient client : node1.getAllClients()) {
                byte[] token = new byte[4];
                new Random().nextBytes(token);

                client.sendEvent("distAckBinaryReq", new AckCallback<byte[]>(byte[].class, 10) {
                    @Override
                    public void onSuccess(byte[] result) {
                        if (result != null && result.length == 6 &&
                                (result[0] & 0xFF) == (token[0] & 0xFF) &&
                                (result[1] & 0xFF) == (token[1] & 0xFF) &&
                                (result[2] & 0xFF) == (token[2] & 0xFF) &&
                                (result[3] & 0xFF) == (token[3] & 0xFF) &&
                                (result[4] & 0xFF) == 0xBE &&
                                (result[5] & 0xFF) == 0xEF) {
                            validAcks.incrementAndGet();
                            ackLatch.countDown();
                        }
                    }
                }, (Object) token);
            }

            for (SocketIOClient client : node2.getAllClients()) {
                byte[] token = new byte[4];
                new Random().nextBytes(token);

                client.sendEvent("distAckBinaryReq", new AckCallback<byte[]>(byte[].class, 10) {
                    @Override
                    public void onSuccess(byte[] result) {
                        if (result != null && result.length == 6 &&
                                (result[0] & 0xFF) == (token[0] & 0xFF) &&
                                (result[1] & 0xFF) == (token[1] & 0xFF) &&
                                (result[2] & 0xFF) == (token[2] & 0xFF) &&
                                (result[3] & 0xFF) == (token[3] & 0xFF) &&
                                (result[4] & 0xFF) == 0xBE &&
                                (result[5] & 0xFF) == 0xEF) {
                            validAcks.incrementAndGet();
                            ackLatch.countDown();
                        }
                    }
                }, (Object) token);
            }

            assertTrue(ackLatch.await(15, TimeUnit.SECONDS),
                    String.format("Timed out waiting for binary ACKs! Received %d of %d expected ACKs.",
                            validAcks.get(), FULL_MATRIX_CLIENTS));
            assertEquals(FULL_MATRIX_CLIENTS, validAcks.get(),
                    "Server should receive binary transformed ACK replies from all cluster clients");

            node1.getBroadcastOperations().sendEvent("dist-test-done", "ack_binary_check");

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 11 - Client-to-Client Cluster Relay (exact client matrix)")
    @Test
    public void testDistributedClientToClientRelay_Positive() throws Exception {
        final String room = "ClusterP2pRoom_" + System.currentTimeMillis();
        final String senderClient = "n1_v4.8.3_websocket";
        final String messageNonce = "P2P_MSG_" + UUID.randomUUID();

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("p2pSender", senderClient);
        extraArgs.put("p2pNonce", messageNonce);

        CountDownLatch p2pLatch = new CountDownLatch(FULL_MATRIX_CLIENTS);
        Set<String> expectedClientNames = ConcurrentHashMap.newKeySet();
        Set<String> confirmedClientNames = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<String> unexpectedConfirmations = new ConcurrentLinkedQueue<>();
        DataListener<String> confirmListener = (client, clientName, ackRequest) -> {
            if (!expectedClientNames.contains(clientName)) {
                unexpectedConfirmations.add(String.valueOf(clientName));
            } else if (confirmedClientNames.add(clientName)) {
                p2pLatch.countDown();
            }
        };

        DataListener<P2pRelayPayload> relayListener = (client, payload, ackRequest) -> {
            node1.getRoomOperations(payload.getRoom()).sendEvent("client-p2p-receive", payload);
        };

        node1.addEventListener("client-p2p-send", P2pRelayPayload.class, relayListener);
        node2.addEventListener("client-p2p-send", P2pRelayPayload.class, relayListener);

        node1.addEventListener("client-p2p-confirmed", String.class, confirmListener);
        node2.addEventListener("client-p2p-confirmed", String.class, confirmListener);

        List<JsClientProcess> processes = launchFullClientMatrix(
                "dist_client_to_client", room, extraArgs, CLIENT_CONFIRM_JS_CLIENT_TIMEOUT_SECONDS);
        processes.forEach(process -> expectedClientNames.add(process.getName()));
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            node1.getBroadcastOperations().sendEvent("trigger-p2p-send", senderClient);

            assertTrue(p2pLatch.await(CLIENT_CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    clientConfirmationTimeoutMessage("P2P relay", expectedClientNames,
                            confirmedClientNames, unexpectedConfirmations, processes));

            node1.getBroadcastOperations().sendEvent("dist-test-done", "p2p_relay_check");

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            node1.removeAllListeners("client-p2p-send");
            node2.removeAllListeners("client-p2p-send");
            node1.removeAllListeners("client-p2p-confirmed");
            node2.removeAllListeners("client-p2p-confirmed");
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 12 - Cluster Direct SessionID Routing across Nodes")
    @Test
    public void testDistributedDirectSessionId_Positive() throws Exception {
        final String room = "ClusterDirectRoom_" + System.currentTimeMillis();
        final String directNonce = "DIRECT_NONCE_" + UUID.randomUUID();

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("directNonce", directNonce);

        List<JsClientProcess> processes = launchFullClientMatrix("dist_direct_session", room, extraArgs);
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            SocketIOClient targetClientOnNode2 = connectedClientMap.get("n2_v4.8.3_websocket");
            if (targetClientOnNode2 == null) {
                targetClientOnNode2 = node2.getAllClients().iterator().next();
            }
            assertNotNull(targetClientOnNode2, "Target client on Node 2 must exist");
            String targetSessionId = targetClientOnNode2.getSessionId().toString();

            awaitRoomSync(targetSessionId, 1, processes);

            CountDownLatch directLatch = new CountDownLatch(1);
            Set<String> confirmedSet = ConcurrentHashMap.newKeySet();

            DataListener<String> confirmListener = (client, clientName, ackRequest) -> {
                if (confirmedSet.add(clientName)) {
                    directLatch.countDown();
                }
            };
            node2.addEventListener("direct-confirmed", String.class, confirmListener);

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
            while (System.currentTimeMillis() < deadline && directLatch.getCount() > 0) {
                node1.getRoomOperations(targetSessionId).sendEvent("direct-event", directNonce);
                if (directLatch.await(1, TimeUnit.SECONDS)) {
                    break;
                }
            }

            assertEquals(0, directLatch.getCount(), "Target client on Node 2 must receive direct message from Node 1");

            node1.getBroadcastOperations().sendEvent("dist-test-done", "direct_session_check");
            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            node2.removeAllListeners("direct-confirmed");
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 13 - Custom Namespace (/admin) Cluster Propagation (exact client matrix)")
    @Test
    public void testDistributedCustomNamespace_Positive() throws Exception {
        final String room = "AdminClusterRoom_" + System.currentTimeMillis();
        final String adminNonce = "ADMIN_NONCE_" + UUID.randomUUID();

        com.socketio4j.socketio.SocketIONamespace adminNs1 = node1.addNamespace("/admin");
        com.socketio4j.socketio.SocketIONamespace adminNs2 = node2.addNamespace("/admin");

        attachDefaultRoomListeners(adminNs1);
        attachDefaultRoomListeners(adminNs2);

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("namespace", "/admin");
        extraArgs.put("adminNonce", adminNonce);

        List<JsClientProcess> processes = launchFullClientMatrix("dist_custom_namespace", room, extraArgs);
        try {
            awaitRoomSync("/admin", room, FULL_MATRIX_CLIENTS, processes);

            node1.getNamespace("/admin").getRoomOperations(room).sendEvent("admin-event", adminNonce);

            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 14 - Client-Initiated ACK Propagation across Cluster (exact client matrix)")
    @Test
    public void testDistributedClientInitiatedAck_Positive() throws Exception {
        final String room = "ClusterClientAckRoom_" + System.currentTimeMillis();

        CountDownLatch ackLatch = new CountDownLatch(FULL_MATRIX_CLIENTS);
        Set<String> expectedClientNames = ConcurrentHashMap.newKeySet();
        Set<String> confirmedClientNames = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<String> unexpectedConfirmations = new ConcurrentLinkedQueue<>();
        DataListener<String> confirmListener = (client, clientName, ackRequest) -> {
            if (!expectedClientNames.contains(clientName)) {
                unexpectedConfirmations.add(String.valueOf(clientName));
            } else if (confirmedClientNames.add(clientName)) {
                ackLatch.countDown();
            }
        };

        DataListener<String> reqListener = (client, challenge, ackRequest) -> {
            if (ackRequest.isAckRequested()) {
                ackRequest.sendAckData("SERVER_ACK_REPLY_" + challenge);
            }
        };

        node1.addEventListener("client-ack-req", String.class, reqListener);
        node2.addEventListener("client-ack-req", String.class, reqListener);
        node1.addEventListener("client-ack-confirmed", String.class, confirmListener);
        node2.addEventListener("client-ack-confirmed", String.class, confirmListener);

        List<JsClientProcess> processes = launchFullClientMatrix(
                "dist_client_ack", room, new HashMap<>(), CLIENT_CONFIRM_JS_CLIENT_TIMEOUT_SECONDS);
        processes.forEach(process -> expectedClientNames.add(process.getName()));
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            node1.getBroadcastOperations().sendEvent("trigger-client-ack");

            assertTrue(ackLatch.await(CLIENT_CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    clientConfirmationTimeoutMessage("client-initiated ACKs", expectedClientNames,
                            confirmedClientNames, unexpectedConfirmations, processes));

            node1.getBroadcastOperations().sendEvent("dist-test-done", "client_ack_check");
            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            node1.removeAllListeners("client-ack-req");
            node2.removeAllListeners("client-ack-req");
            node1.removeAllListeners("client-ack-confirmed");
            node2.removeAllListeners("client-ack-confirmed");
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Positive 15 - Targeted Client Exclusion across Cluster (all but one matrix client)")
    @Test
    public void testDistributedClientExclusion_Positive() throws Exception {
        final String room = "ClusterExclusionRoom_" + System.currentTimeMillis();
        final String exclusionNonce = "EXCLUSION_NONCE_" + UUID.randomUUID();
        final String excludedClientName = "n1_v4.8.3_websocket";

        Map<String, String> extraArgs = new HashMap<>();
        extraArgs.put("excludedClientName", excludedClientName);
        extraArgs.put("exclusionNonce", exclusionNonce);

        CountDownLatch confirmLatch = new CountDownLatch(FULL_MATRIX_CLIENTS - 1);
        Set<String> confirmedClients = ConcurrentHashMap.newKeySet();

        DataListener<String> confirmListener = (client, clientName, ackRequest) -> {
            if (confirmedClients.add(clientName)) {
                confirmLatch.countDown();
            }
        };

        node1.addEventListener("exclusion-confirmed", String.class, confirmListener);
        node2.addEventListener("exclusion-confirmed", String.class, confirmListener);

        List<JsClientProcess> processes = launchFullClientMatrix("dist_client_exclusion", room, extraArgs);
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            SocketIOClient excludedClient = connectedClientMap.get(excludedClientName);
            assertNotNull(excludedClient, "Must find registered SocketIOClient for " + excludedClientName);

            node1.getRoomOperations(room).sendEvent("dist-event",
                    client -> client.getSessionId().equals(excludedClient.getSessionId()),
                    exclusionNonce);

            assertTrue(confirmLatch.await(15, TimeUnit.SECONDS),
                    String.format("Timed out waiting for client exclusion confirmations! Received %d of %d expected.",
                            confirmedClients.size(), FULL_MATRIX_CLIENTS - 1));

            node1.getBroadcastOperations().sendEvent("dist-test-done", "client_exclusion_check");
            verifyAndCleanUpProcesses(processes, 25);
        } finally {
            node1.removeAllListeners("exclusion-confirmed");
            node2.removeAllListeners("exclusion-confirmed");
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    @DisplayName("Negative 16 - Abrupt Client Crash & Cluster Session Cleanup Re-sync")
    @Test
    public void testDistributedAbruptDisconnect_Negative() throws Exception {
        final String room = "CrashRoom_" + System.currentTimeMillis();

        List<JsClientProcess> processes = launchFullClientMatrix("dist_abrupt_disconnect", room, new HashMap<>());
        try {
            awaitRoomSync(room, FULL_MATRIX_CLIENTS, processes);

            List<JsClientProcess> crashedProcesses = new ArrayList<>();
            List<JsClientProcess> remainingProcesses = new ArrayList<>();

            for (JsClientProcess p : processes) {
                if (p.getTransport().equals("websocket") && crashedProcesses.size() < 4) {
                    crashedProcesses.add(p);
                } else {
                    remainingProcesses.add(p);
                }
            }

            assertEquals(4, crashedProcesses.size(), "Should find 4 WebSocket processes to kill");

            for (JsClientProcess crashed : crashedProcesses) {
                crashed.destroyForcibly();
            }

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
            boolean cleanedUp = false;

            Namespace ns1 = (Namespace) node1.getNamespace("");
            Namespace ns2 = (Namespace) node2.getNamespace("");

            while (System.currentTimeMillis() < deadline) {
                int n1 = ns1.getRoomClientsInCluster(room);
                int n2 = ns2.getRoomClientsInCluster(room);

                if (n1 == FULL_MATRIX_CLIENTS - 4 && n2 == FULL_MATRIX_CLIENTS - 4) {
                    cleanedUp = true;
                    break;
                }
                Thread.sleep(100);
            }

            assertTrue(cleanedUp, String.format("Cluster room client count must drop from %d to %d after abrupt process crash! Node1=%d, Node2=%d",
                    FULL_MATRIX_CLIENTS, FULL_MATRIX_CLIENTS - 4,
                    ns1.getRoomClientsInCluster(room), ns2.getRoomClientsInCluster(room)));

            node2.getBroadcastOperations().sendEvent("dist-test-done", "abrupt_disconnect_check");
            verifyAndCleanUpProcesses(remainingProcesses, 25);
        } finally {
            processes.forEach(JsClientProcess::destroyForcibly);
        }
    }

    // --- UTILITIES & POJOS ---

    public static class P2pRelayPayload implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        @com.fasterxml.jackson.annotation.JsonProperty("sender")
        public String sender;
        @com.fasterxml.jackson.annotation.JsonProperty("room")
        public String room;
        @com.fasterxml.jackson.annotation.JsonProperty("nonce")
        public String nonce;

        public P2pRelayPayload() {}
        public P2pRelayPayload(String sender, String room, String nonce) {
            this.sender = sender;
            this.room = room;
            this.nonce = nonce;
        }

        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
        public String getRoom() { return room; }
        public void setRoom(String room) { this.room = room; }
        public String getNonce() { return nonce; }
        public void setNonce(String nonce) { this.nonce = nonce; }
    }

    protected JsClientProcess launchJsClient(String name, String version, int port,
                                             String transport, String scenario, String room,
                                             Map<String, String> extraArgs) throws Exception {
        return launchJsClient(name, version, port, transport, scenario, room, extraArgs,
                DEFAULT_JS_CLIENT_TIMEOUT_SECONDS);
    }

    protected JsClientProcess launchJsClient(String name, String version, int port,
                                             String transport, String scenario, String room,
                                             Map<String, String> extraArgs,
                                             long clientTimeoutSeconds) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        cmd.add(jsScript.getAbsolutePath());
        cmd.add("--clientName=" + name);
        cmd.add("--version=" + version);
        cmd.add("--port=" + port);
        cmd.add("--transport=" + transport);
        cmd.add("--scenario=" + scenario);
        cmd.add("--room=" + room);
        cmd.add("--timeout=" + TimeUnit.SECONDS.toMillis(clientTimeoutSeconds));

        if (extraArgs != null) {
            for (Map.Entry<String, String> entry : extraArgs.entrySet()) {
                cmd.add("--" + entry.getKey() + "=" + entry.getValue());
            }
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(jsDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        JsClientProcess wrapper = new JsClientProcess(name, version, port, transport, scenario, room, process);
        ALL_ACTIVE_PROCESSES.add(wrapper);
        return wrapper;
    }

    private String clientConfirmationTimeoutMessage(String operation,
                                                    Set<String> expectedClientNames,
                                                    Set<String> confirmedClientNames,
                                                    ConcurrentLinkedQueue<String> unexpectedConfirmations,
                                                    List<JsClientProcess> processes) {
        List<String> missingClientNames = new ArrayList<String>(expectedClientNames);
        missingClientNames.removeAll(confirmedClientNames);
        java.util.Collections.sort(missingClientNames);

        StringBuilder message = new StringBuilder();
        message.append(String.format("Timed out waiting for %s! Received %d of %d unique client confirmations.",
                operation, confirmedClientNames.size(), expectedClientNames.size()));
        message.append(" Missing clients: ").append(missingClientNames).append('.');
        if (!unexpectedConfirmations.isEmpty()) {
            message.append(" Unexpected confirmations: ").append(unexpectedConfirmations).append('.');
        }
        message.append("\nJS Client Output Logs:\n");
        for (JsClientProcess process : processes) {
            message.append("--- Log for ").append(process.getName()).append(" ---\n")
                    .append(process.getLogOutput()).append('\n');
        }
        return message.toString();
    }

    private int countClients(Iterable<SocketIOClient> clients) {
        if (clients == null) return -1;
        if (clients instanceof java.util.Collection) {
            return ((java.util.Collection<?>) clients).size();
        }
        int count = 0;
        for (Object ignored : clients) {
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
        private final AtomicReference<Throwable> logFailure = new AtomicReference<>();
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

            logThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (logOutput) {
                            logOutput.append(line).append("\n");
                        }

                    }
                } catch (Exception error) {
                    logFailure.compareAndSet(null, error);
                }
            });
            logThread.setDaemon(true);
            logThread.start();
        }

        public String getName() { return name; }
        public String getVersion() { return version; }
        public int getPort() { return port; }
        public String getTransport() { return transport; }
        public String getScenario() { return scenario; }
        public String getRoom() { return room; }
        public boolean isAlive() { return process.isAlive(); }
        public int exitValue() { return process.exitValue(); }
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            boolean finished = process.waitFor(timeout, unit);
            if (!finished) {
                return false;
            }

            logThread.join(TimeUnit.SECONDS.toMillis(1));
            if (logThread.isAlive()) {
                throw new IllegalStateException("Timed out while reading output for distributed JS client '" + name + "'");
            }
            Throwable error = logFailure.get();
            if (error != null) {
                throw new IllegalStateException("Could not read output for distributed JS client '" + name + "'", error);
            }
            return true;
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
