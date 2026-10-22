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
package com.socketio4j.socketio.integration.interop;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIONamespace;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.namespace.NamespaceTestReuseAssertions;
import com.socketio4j.socketio.protocol.EngineIOVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("NODE_JS_INTEROP")
public class BrowserInteropTest {

    private static final int BROWSER_COUNT = 3;
    private static final int CLIENT_VERSION_COUNT = JsClientInteropMatrix.VERSIONS.size();
    private static final int EIO3_CLIENT_VERSION_COUNT = (int) JsClientInteropMatrix.VERSIONS.stream()
            .filter(JsClientInteropMatrix::usesEngineIOV3)
            .count();
    private static final int TRANSPORT_COUNT = 2;
    private static final int NAMESPACE_COUNT = 2;
    private static final int EVENT_TYPE_COUNT = 6;
    // Individual browser cases retain their 30-second page timeout. This
    // larger process deadline accommodates cold browser launch overhead.
    private static final long BROWSER_RUNNER_TIMEOUT_SECONDS = 240;
    private static final long PROCESS_OUTPUT_DRAIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_CAPTURED_PROCESS_OUTPUT_CHARS = 1_000_000;

    private static final byte[] EXPECTED_BINARY = {
            0, 1, 2, 3, 4, 5, 10, 20, 30, 40,
            50, 60, 70, 80, 90, 100,
            (byte) 0xAA,
            (byte) 0xBB,
            (byte) 0xCC,
            (byte) 0xDD,
            (byte) 0xEE,
            (byte) 0xFF
    };

    private static final String EXPECTED_TEXT = "Hello SocketIO Browser";
    private static final Integer EXPECTED_NUMBER = 42;

    private static final byte[] EXPECTED_BINARY_SHA256 =
            sha256(EXPECTED_BINARY);

    private static SocketIOServer server;
    private static int serverPort;
    private static int httpPort;

    /**
     * Every received event is recorded.
     * Assertions happen after the browser matrix finishes.
     */
    private static final Queue<ReceivedEvent> EVENTS =
            new ConcurrentLinkedQueue<ReceivedEvent>();

    /**
     * Netty invokes Socket.IO listeners asynchronously. An assertion thrown
     * there is otherwise only reported to the exception listener and cannot
     * fail the JUnit method that started the browser matrix.
     */
    private static final Queue<Throwable> CALLBACK_FAILURES =
            new ConcurrentLinkedQueue<Throwable>();

    /**
     * Used to detect duplicate deliveries.
     */
    private static final Set<String> UNIQUE_EVENTS =
            Collections.newSetFromMap(
                    new ConcurrentHashMap<String, Boolean>());
    private static final AtomicInteger CONNECTS =
            new AtomicInteger();

    private static final AtomicInteger DISCONNECTS =
            new AtomicInteger();
    /**
     * Event ordering per session.
     */
    private static final Map<UUID,
            Map<String, List<String>>> EVENT_ORDER =
            new ConcurrentHashMap<>();
    private static final AtomicLong EVENT_SEQUENCE =
            new AtomicLong();
    private static final class ReceivedEvent {

        private final UUID sessionId;
        private final String namespace;
        private final String event;
        private final Transport transport;
        private final EngineIOVersion version;
        private final long sequence;

        ReceivedEvent(UUID sessionId,
                      String namespace,
                      String event,
                      Transport transport,
                      EngineIOVersion version) {

            this.sessionId = sessionId;
            this.namespace = namespace;
            this.event = event;
            this.transport = transport;
            this.version = version;
            this.sequence = EVENT_SEQUENCE.incrementAndGet();
        }

        UUID getSessionId() {
            return sessionId;
        }

        String getNamespace() {
            return namespace;
        }

        String getEvent() {
            return event;
        }

        Transport getTransport() {
            return transport;
        }

        EngineIOVersion getVersion() {
            return version;
        }

        long getSequence() {
            return sequence;
        }

        String uniqueKey() {
            return sessionId + "|" +
                    namespace + "|" +
                    event + "|" +
                    transport + "|" +
                    version;
        }

        @Override
        public String toString() {
            return uniqueKey();
        }
    }

    /**
     * Clears all verification state before every browser run.
     */
    private static void resetRecorder() {

        EVENTS.clear();
        CALLBACK_FAILURES.clear();
        UNIQUE_EVENTS.clear();
        EVENT_ORDER.clear();
        CONNECTS.set(0);
        DISCONNECTS.set(0);
        EVENT_SEQUENCE.set(0);
    }

    /**
     * Records one successfully received event.
     */
    private static void recordEvent(String namespace,
                                    String event,
                                    com.socketio4j.socketio.SocketIOClient client) {

        ReceivedEvent e =
                new ReceivedEvent(
                        client.getSessionId(),
                        namespace,
                        event,
                        client.getTransport(),
                        client.getEngineIOVersion());

        EVENTS.add(e);

        if (!UNIQUE_EVENTS.add(e.uniqueKey())) {
            throw new AssertionError(
                    "Duplicate event received: " + e.uniqueKey());
        }

        UUID sid = client.getSessionId();

        Map<String, List<String>> byNamespace =
                EVENT_ORDER.get(sid);

        if (byNamespace == null) {

            byNamespace =
                    new ConcurrentHashMap<String, List<String>>();

            Map<String, List<String>> existing =
                    EVENT_ORDER.putIfAbsent(sid, byNamespace);

            if (existing != null) {
                byNamespace = existing;
            }
        }

        List<String> order = byNamespace.get(namespace);

        if (order == null) {

            order = new CopyOnWriteArrayList<String>();

            List<String> existing =
                    byNamespace.putIfAbsent(namespace, order);

            if (existing != null) {
                order = existing;
            }
        }

        order.add(event);
    }

    /**
     * Wait until the embedded HTTP server is reachable.
     * Avoids Thread.sleep().
     */
    private static void waitForHttpServer(int port)
            throws Exception {

        long deadline =
                System.currentTimeMillis() + 10000;
        Exception lastConnectionFailure = null;

        while (System.currentTimeMillis() < deadline) {

            try (Socket ignored =
                         new Socket("127.0.0.1", port)) {
                return;
            } catch (Exception error) {
                lastConnectionFailure = error;
                Thread.sleep(100);
            }
        }

        throw new IllegalStateException(
                "HTTP server did not start on port " + port,
                lastConnectionFailure);
    }

    /**
     * SHA-256 helper for binary integrity verification.
     */
    private static byte[] sha256(byte[] bytes) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return digest.digest(bytes);

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(e);

        }
    }

    /**
     * Find an available port by binding to port 0.
     */
    private static int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class CapturedProcess {

        private final Process process;
        private final StringBuilder output = new StringBuilder();
        private final AtomicReference<IOException> outputFailure = new AtomicReference<>();
        private final Thread outputDrainer;

        CapturedProcess(Process process, String description) {
            this.process = process;
            this.outputDrainer = new Thread(() -> drainOutput(),
                    "browser-interop-output-" + description);
            outputDrainer.setDaemon(true);
            outputDrainer.start();
        }

        private void drainOutput() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    synchronized (output) {
                        int remaining = MAX_CAPTURED_PROCESS_OUTPUT_CHARS - output.length();
                        if (remaining > 0) {
                            output.append(buffer, 0, Math.min(read, remaining));
                        }
                    }
                }
            } catch (IOException error) {
                outputFailure.compareAndSet(null, error);
            }
        }

        boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return process.waitFor(timeout, unit);
        }

        int exitValue() {
            return process.exitValue();
        }

        void stop() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(PROCESS_OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(PROCESS_OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            awaitOutput();
        }

        void awaitOutput() throws InterruptedException {
            outputDrainer.join(TimeUnit.SECONDS.toMillis(PROCESS_OUTPUT_DRAIN_TIMEOUT_SECONDS));
            if (outputDrainer.isAlive()) {
                throw new IllegalStateException("Timed out draining browser process output");
            }
            IOException failure = outputFailure.get();
            if (failure != null) {
                throw new IllegalStateException("Unable to read browser process output", failure);
            }
        }

        String output() {
            synchronized (output) {
                return output.toString();
            }
        }
    }

    /**
     * Start an external process and capture its combined output without
     * bypassing Surefire's fork communication channel.
     */
    private static CapturedProcess startProcess(
            File directory,
            Map<String, String> env,
            String... command)
            throws Exception {

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true);
        if (env != null) {
            pb.environment().putAll(env);
        }
        return new CapturedProcess(pb.start(), command[0]);
    }

    @BeforeAll
    static void beforeAll() throws Exception {

        serverPort = findAvailablePort();
        httpPort = findAvailablePort();

        Configuration config = new Configuration();
        config.setPort(serverPort);
        config.setOrigin("http://127.0.0.1:" + httpPort);

        server = new SocketIOServer(config);

        for (String namespace : new String[]{"", "/chat"}) {

            SocketIONamespace nsp = namespace.isEmpty()
                    ? server.getNamespace("")
                    : server.addNamespace(namespace);

            register(nsp);
        }

        server.start();
    }

    @AfterAll
    static void afterAll() {

        if (server != null) {
            server.stop();
        }
    }

    private static void register(SocketIONamespace nsp) {

        final String namespace = nsp.getName();

        nsp.addConnectListener(client -> {
                CONNECTS.incrementAndGet();

        }
        );

        nsp.addDisconnectListener(client -> {
                DISCONNECTS.incrementAndGet();

        });

        nsp.addEventListener(
                "text",
                String.class,
                (client, text, ack) -> {
                    verifyCallback(() -> {
                        recordEvent(namespace, "text", client);
                        assertText(text);
                        client.sendEvent("textReply", text);
                    });
                });

        nsp.addEventListener(
                "textAck",
                String.class,
                (client, text, ack) -> {
                    verifyCallback(() -> {
                        recordEvent(namespace, "textAck", client);
                        assertText(text);
                        ack.sendAckData(text);
                    });
                });

        nsp.addEventListener(
                "binary",
                byte[].class,
                (client, bytes, ack) -> {
                    verifyCallback(() -> {
                        recordEvent(namespace, "binary", client);
                        assertBinary(bytes);
                        client.sendEvent("binaryReply", bytes);
                    });
                });

        nsp.addEventListener(
                "binaryAck",
                byte[].class,
                (client, bytes, ack) -> {
                    verifyCallback(() -> {
                        recordEvent(namespace, "binaryAck", client);
                        assertBinary(bytes);
                        ack.sendAckData(bytes);
                    });
                });

        nsp.addEventListener(
                "mixed",
                JsonData.class,
                (client, data, ack) -> {
                    verifyCallback(() -> {
                        recordEvent(namespace, "mixed", client);
                        assertText(data.getText());
                        assertBinary(data.getBinary());
                        assertNumber(data.getNumber());
                        client.sendEvent("mixedReply", data);
                    });
                });

        nsp.addEventListener(
                "mixedAck",
                JsonData.class,
                (client, data, ack) -> {
                    verifyCallback(() -> {
                        recordEvent(namespace, "mixedAck", client);
                        assertText(data.getText());
                        assertBinary(data.getBinary());
                        assertNumber(data.getNumber());
                        ack.sendAckData(data);
                    });
                });
    }

    private static void verifyCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException | Error error) {
            CALLBACK_FAILURES.add(error);
            throw error;
        }
    }

    private static void assertText(String value) {

        assertEquals(EXPECTED_TEXT, value);
    }

    private static void assertNumber(Integer value) {

        assertEquals(EXPECTED_NUMBER, value);
    }

    private static void assertBinary(byte[] value) {

        assertEquals(
                EXPECTED_BINARY.length,
                value.length,
                "Binary length mismatch");

        if (!Arrays.equals(EXPECTED_BINARY, value)) {
            throw new AssertionError("Binary payload mismatch");
        }

        if (!Arrays.equals(
                EXPECTED_BINARY_SHA256,
                sha256(value))) {
            throw new AssertionError(
                    "Binary SHA-256 mismatch");
        }
    }
    @Test
    void browserInterop() throws Exception {

        resetRecorder();

        File dir = new File("src/test/resources/js-interop");
        CapturedProcess python = null;
        CapturedProcess node = null;
        Map<String, String> env = new java.util.HashMap<>();
        env.put("HTTP_PORT", String.valueOf(httpPort));
        env.put("SOCKETIO_PORT", String.valueOf(serverPort));
        env.put("SOCKETIO_INTEROP_VERSIONS", JsClientInteropMatrix.configuredVersionsCsv());
        try {
             python = startProcess(
                    dir,
                    null,
                    "python3",
                    "-m",
                    "http.server",
                    String.valueOf(httpPort));

            waitForHttpServer(httpPort);

            node = startProcess(
                    dir,
                    env,
                    "node",
                    "browser-runner.js");
            assertTrue(node.waitFor(BROWSER_RUNNER_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Browser interop runner timed out after " + BROWSER_RUNNER_TIMEOUT_SECONDS
                            + " seconds\n" + node.output());
            node.awaitOutput();
            int exit = node.exitValue();

            assertEquals(0, exit, node.output());

        } finally {

            if (node != null) {
                node.stop();
            }

            if (python != null) {
                python.stop();
            }
        }

        verifyEvents();
    }
    private static void verifyEvents() {

        assertNoCallbackFailures();

        final int expectedEvents =
                BROWSER_COUNT *
                        CLIENT_VERSION_COUNT *
                        TRANSPORT_COUNT *
                        NAMESPACE_COUNT *
                        EVENT_TYPE_COUNT;

        assertEquals(
                expectedEvents,
                EVENTS.size(),
                "Unexpected number of events");

        assertEquals(
                expectedEvents,
                UNIQUE_EVENTS.size(),
                "Duplicate events detected");

        verifyNamespaceDistribution();

        verifyTransportDistribution();

        verifyEngineIOVersions();

        verifyOrdering();

        final int expectedConnections = BROWSER_COUNT * CLIENT_VERSION_COUNT *
                TRANSPORT_COUNT * NAMESPACE_COUNT;

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertEquals(expectedConnections, CONNECTS.get(),
                            "Unexpected number of namespace connects");
                    assertEquals(expectedConnections, DISCONNECTS.get(),
                            "Unexpected number of server-observed namespace disconnects");
                });

        for (SocketIONamespace namespace : server.getAllNamespaces()) {
            NamespaceTestReuseAssertions.assertEmpty(namespace,
                    "after browser interop run");
        }
    }

    private static void assertNoCallbackFailures() {
        if (CALLBACK_FAILURES.isEmpty()) {
            return;
        }

        AssertionError failure = new AssertionError(
                "Server event callback assertion failure(s): " + CALLBACK_FAILURES.size());
        for (Throwable callbackFailure : CALLBACK_FAILURES) {
            failure.addSuppressed(callbackFailure);
        }
        throw failure;
    }
    private static void verifyNamespaceDistribution() {

        int root = 0;
        int chat = 0;

        for (ReceivedEvent e : EVENTS) {

            if ("".equals(e.getNamespace())) {
                root++;
            } else if ("/chat".equals(e.getNamespace())) {
                chat++;
            } else {
                throw new AssertionError(
                        "Unexpected namespace: " +
                                e.getNamespace());
            }
        }

        assertEquals(root, chat);
    }
    private static void verifyTransportDistribution() {

        int polling = 0;
        int websocket = 0;

        for (ReceivedEvent e : EVENTS) {

            switch (e.getTransport()) {

                case POLLING:
                    polling++;
                    break;

                case WEBSOCKET:
                    websocket++;
                    break;
            }

        }

        assertEquals(EVENTS.size() / TRANSPORT_COUNT, polling);
        assertEquals(EVENTS.size() / TRANSPORT_COUNT, websocket);
        assertEquals(BROWSER_COUNT * CLIENT_VERSION_COUNT * TRANSPORT_COUNT *
                NAMESPACE_COUNT * EVENT_TYPE_COUNT, EVENTS.size());
    }
    private static void verifyEngineIOVersions() {

        int v3 = 0;
        int v4 = 0;

        for (ReceivedEvent e : EVENTS) {

            switch (e.getVersion()) {

                case V3:
                    v3++;
                    break;

                case V4:
                    v4++;
                    break;

                default:
                    throw new AssertionError(
                            "Unexpected Engine.IO version "
                                    + e.getVersion());
            }
        }

        assertEquals(BROWSER_COUNT * CLIENT_VERSION_COUNT * TRANSPORT_COUNT *
                NAMESPACE_COUNT * EVENT_TYPE_COUNT, EVENTS.size());
        assertEquals(BROWSER_COUNT * EIO3_CLIENT_VERSION_COUNT * TRANSPORT_COUNT *
                NAMESPACE_COUNT * EVENT_TYPE_COUNT, v3);
        assertEquals(BROWSER_COUNT * (CLIENT_VERSION_COUNT - EIO3_CLIENT_VERSION_COUNT) *
                TRANSPORT_COUNT * NAMESPACE_COUNT * EVENT_TYPE_COUNT, v4);
    }
    private static void verifyOrdering() {

        List<String> expected =
                Arrays.asList(
                        "text",
                        "textAck",
                        "binary",
                        "binaryAck",
                        "mixed",
                        "mixedAck");

        for (Map<String, List<String>> namespaces
                : EVENT_ORDER.values()) {

            for (Map.Entry<String, List<String>> e
                    : namespaces.entrySet()) {

                assertEquals(
                        expected,
                        e.getValue(),
                        "Incorrect ordering for namespace "
                                + e.getKey());
            }
        }
    }
    public static final class JsonData {

        private String text;
        private byte[] binary;
        private Integer number;

        public JsonData() {
        }

        public JsonData(String text, byte[] binary, Integer number) {
            this.text = text;
            this.binary = binary;
            this.number = number;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public byte[] getBinary() {
            return binary;
        }

        public void setBinary(byte[] binary) {
            this.binary = binary;
        }

        public Integer getNumber() {
            return number;
        }

        public void setNumber(Integer number) {
            this.number = number;
        }

        @Override
        public String toString() {
            return "JsonData{" +
                    "text='" + text + '\'' +
                    ", binaryLength=" + (binary != null ? binary.length : 0) +
                    ", number=" + number +
                    '}';
        }

        @Override
        public int hashCode() {
            int result = text != null ? text.hashCode() : 0;
            result = 31 * result + Arrays.hashCode(binary);
            result = 31 * result + (number != null ? number.hashCode() : 0);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof JsonData)) {
                return false;
            }

            JsonData other = (JsonData) obj;

            return Objects.equals(text, other.text)
                    && Arrays.equals(binary, other.binary)
                    && Objects.equals(number, other.number);
        }
    }
}
