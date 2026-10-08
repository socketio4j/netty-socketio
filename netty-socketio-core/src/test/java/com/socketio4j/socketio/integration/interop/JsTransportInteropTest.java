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
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("NODE_JS_INTEROP")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JsTransportInteropTest extends AbstractReusableSocketIOInteropTest {

    private static final long JS_TEST_TIMEOUT_SECONDS = 20;

    private void runTransportJsTest(String version, String scenario) throws Exception {

        File jsDir = new File("src/test/resources/js-interop");
        if (!jsDir.exists()) {
            jsDir = new File("netty-socketio-core/src/test/resources/js-interop");
        }

        ProcessBuilder pb = new ProcessBuilder(
                "node",
                "test-clients-transport.js",
                "--version=" + version,
                "--port=" + getServerPort(),
                "--scenario=" + scenario);

        pb.directory(jsDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        AtomicReference<Throwable> outputFailure = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(process.getInputStream()))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }

            } catch (Throwable error) {
                outputFailure.set(error);
            }
        });

        t.setDaemon(true);
        t.start();

        try {

            boolean completed =
                    process.waitFor(JS_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                fail("JS test timed out\n\n" + getOutput(output));
            }
            t.join(TimeUnit.SECONDS.toMillis(1));
            if (t.isAlive()) {
                fail("JS client output reader did not terminate\n" + getOutput(output));
            }
            if (outputFailure.get() != null) {
                throw new AssertionError("Unable to read JS client output", outputFailure.get());
            }

            assertEquals(
                    0,
                    process.exitValue(),
                    getOutput(output));

        } finally {

            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String getOutput(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    @ParameterizedTest(name = "[UPGRADE-001] JS Client v{0} - Transport Upgrade")
    @MethodSource("com.socketio4j.socketio.integration.interop.JsClientInteropMatrix#clientVersions")
    void testTransportUpgrade(String version) throws Exception {

        AtomicInteger connectCount = new AtomicInteger();
        AtomicInteger disconnectCount = new AtomicInteger();
        AtomicInteger whoAreYouCount = new AtomicInteger();

        AtomicReference<UUID> sessionId = new AtomicReference<>();

        CountDownLatch disconnectLatch = new CountDownLatch(1);

        getServer().addConnectListener(client -> {
            connectCount.incrementAndGet();
            sessionId.compareAndSet(null, client.getSessionId());
        });

        getServer().addEventListener(
                "whoAreYou",
                String.class,
                (client, ignored, ackSender) -> {

                    whoAreYouCount.incrementAndGet();

                    assertEquals(
                            sessionId.get(),
                            client.getSessionId(),
                            "Socket.IO session changed during upgrade");

                    assertTrue(
                            ackSender.isAckRequested(),
                            "Client must request an ACK");

                    ackSender.sendAckData(
                            client.getTransport()
                                    .name()
                                    .toLowerCase());
                });

        getServer().addDisconnectListener(client -> {

            disconnectCount.incrementAndGet();

            assertEquals(
                    sessionId.get(),
                    client.getSessionId(),
                    "Disconnect occurred for a different session");

            disconnectLatch.countDown();
        });

        runTransportJsTest(
                version,
                "transport_upgrade");

        assertTrue(
                disconnectLatch.await(5, TimeUnit.SECONDS),
                "Timed out waiting for disconnect");

        assertEquals(
                1,
                connectCount.get(),
                "Exactly one connect expected");

        assertTrue(
                whoAreYouCount.get() >= 1,
                "Client should query transport at least once");

        assertEquals(
                1,
                disconnectCount.get(),
                "Exactly one disconnect expected");
    }



}
