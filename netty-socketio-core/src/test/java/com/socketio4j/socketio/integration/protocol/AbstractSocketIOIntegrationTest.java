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
package com.socketio4j.socketio.integration.protocol;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.nativeio.TransportType;
import com.github.javafaker.Faker;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Abstract base class for SocketIO integration tests.
 * Provides common setup, teardown, and utility methods.
 *
 * Features:
 * - Automatic Redis container management
 * - Dynamic port allocation for concurrent testing
 * - Common SocketIO server configuration
 * - Utility methods for client creation and management
 */

public abstract class AbstractSocketIOIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractSocketIOIntegrationTest.class);
    private static final int MAX_SERVER_START_ATTEMPTS = 10;
    protected final Faker faker = new Faker();

    private SocketIOServer server;
    private int serverPort;

    private static final String SERVER_HOST = "localhost";

    /**
     * Get the current server port for this test instance
     */
    protected int getServerPort() {
        return serverPort;
    }

    /**
     * Get the server host
     */
    protected String getServerHost() {
        return SERVER_HOST;
    }

    /**
     * Get the SocketIO server instance
     */
    protected SocketIOServer getServer() {
        return server;
    }

    /**
     * Attaches a server owned by a higher-level test fixture. The fixture, not
     * this test instance, is responsible for stopping the server.
     */
    protected final void useServerFromTestFixture(SocketIOServer fixtureServer, int fixtureServerPort) {
        if (fixtureServer == null || !fixtureServer.isStarted()) {
            throw new IllegalArgumentException("Test fixture server must be running");
        }
        if (fixtureServerPort <= 0 || fixtureServerPort > 65535) {
            throw new IllegalArgumentException("Test fixture server port is invalid: " + fixtureServerPort);
        }
        if (server != null && server != fixtureServer) {
            throw new IllegalStateException("A different test server is already attached");
        }

        server = fixtureServer;
        serverPort = fixtureServerPort;
    }

    /**
     * Allows an isolated test suite to reuse the same server for all of its
     * test methods. The default remains one server per test invocation.
     */
    protected boolean reuseServerForTestClass() {
        return false;
    }

    /**
     * Create a Socket.IO client connected to the test server
     */
    protected Socket createClient() {
        try {
            return IO.socket("http://" + SERVER_HOST + ":" + serverPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket client", e);
        }
    }

    /**
     * Create a Socket.IO client with specific transports and no upgrade
     */
    protected Socket createClient(String[] transports) {
        try {
            IO.Options options = new IO.Options();
            options.transports = transports;
            options.upgrade = false;
            return IO.socket("http://" + SERVER_HOST + ":" + serverPort, options);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket client", e);
        }
    }

    /**
     * Create a Socket.IO client connected to a specific namespace
     */
    protected Socket createClient(String namespace) {
        try {
            return IO.socket("http://" + SERVER_HOST + ":" + serverPort + namespace);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket client for namespace: " + namespace, e);
        }
    }

    /**
     * Create a Socket.IO client connected to a specific namespace with specific transports and no upgrade
     */
    protected Socket createClient(String namespace, String[] transports) {
        try {
            IO.Options options = new IO.Options();
            options.transports = transports;
            options.upgrade = false;
            return IO.socket("http://" + SERVER_HOST + ":" + serverPort + namespace, options);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket client for namespace: " + namespace, e);
        }
    }

    /**
     * Find an available port with retry mechanism
     */
    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { // using zero will auto assign port
            return socket.getLocalPort();
        }
    }

    /**
     * Setup method called before each test.
     * Initializes Redis container, Redisson client, and SocketIO server.
     */
    @BeforeEach
    public void setUp() throws Exception {
        if (server == null && reuseServerForTestClass()) {
            initializeReusableServerFixture();
        }

        if (server != null) {
            if (reuseServerForTestClass()) {
                beforeReusedServerTestCase();
                additionalSetup();
                return;
            }
            throw new IllegalStateException("Previous test server was not stopped before setup");
        }

        // Create SocketIO server configuration
        Configuration serverConfig = new Configuration();
        serverConfig.setHostname(SERVER_HOST);

        Exception lastFailure = null;
        boolean started = false;
        for (int attempt = 1; attempt <= MAX_SERVER_START_ATTEMPTS; attempt++) {
            try {
                // Find an available port for this test
                serverPort = findAvailablePort();
                serverConfig.setPort(serverPort);

                // Allow subclasses to customize configuration
                configureServer(serverConfig);

                // Create and start server
                server = new SocketIOServer(serverConfig);
                configureNamespaces(server);
                server.start();

                started = true;
                break;
            } catch (Exception e) {
                lastFailure = e;

                if (server != null) {
                    try {
                        server.stop();
                    } catch (Exception stopFailure) {
                        e.addSuppressed(stopFailure);
                    } finally {
                        server = null;
                    }
                }

                log.warn(
                        "Socket.IO server setup attempt {}/{} on port {} failed: {}",
                        attempt,
                        MAX_SERVER_START_ATTEMPTS,
                        serverPort,
                        e.toString());

                if (attempt < MAX_SERVER_START_ATTEMPTS) {
                    TimeUnit.SECONDS.sleep(1);
                }
            }
        }

        if (!started) {
            throw new IllegalStateException(
                    "Unable to start Socket.IO integration server after "
                            + MAX_SERVER_START_ATTEMPTS + " attempts",
                    lastFailure);
        }

        // Allow subclasses to do additional setup
        additionalSetup();
    }

    /**
     * Teardown method called after each test.
     * Cleans up all resources to ensure test isolation.
     */
    @AfterEach
    public void tearDown() throws Exception {
        Exception failure = null;

        try {
            additionalTeardown();
        } catch (Exception e) {
            failure = e;
        }

        if (reuseServerForTestClass() && server != null) {
            try {
                afterReusedServerTestCase();
            } catch (Exception e) {
                if (failure != null) {
                    failure.addSuppressed(e);
                } else {
                    failure = e;
                }
            }
        } else if (server != null) {
            try {
                stopServer();
            } catch (Exception e) {
                if (failure != null) {
                    failure.addSuppressed(e);
                } else {
                    failure = e;
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Stops the current server. Test fixtures that are shared beyond one test
     * class own their lifecycle and are stopped by their root fixture instead.
     */
    protected final void stopServer() {
        if (server == null) {
            return;
        }
        try {
            server.stop();
        } finally {
            server = null;
        }
    }

    /**
     * Hook method for subclasses to add custom server configuration.
     * Called after basic configuration but before server start.
     */
    protected void configureServer(Configuration config) {
        // NIO avoids io_uring shutdown hangs on Linux CI runners
        config.setTransportType(TransportType.NIO);
    }

    /**
     * Hook method for subclasses to add custom setup logic.
     * Called after server start.
     */
    protected void additionalSetup() throws Exception {
        // Default implementation does nothing
        // Subclasses can override to add custom setup
    }

    /**
     * Hook method for subclasses to add custom teardown logic.
     * Called before resource cleanup.
     */
    protected void additionalTeardown() throws Exception {
        // Default implementation does nothing
        // Subclasses can override to add custom teardown
    }

    /**
     * Invoked immediately before {@link #additionalSetup()} when a test class
     * reuses a fixture-owned server. Subclasses use this to prove that the
     * prior case left no observable state before adding this case's listeners.
     */
    protected void beforeReusedServerTestCase() throws Exception {
        // Default implementation does nothing.
    }

    /**
     * Gives a reusable test base a chance to attach its fixture before this
     * method decides whether a new server must be started. The default keeps
     * the ordinary per-test server lifecycle unchanged.
     */
    protected void initializeReusableServerFixture() throws Exception {
        // Default implementation does nothing.
    }

    /**
     * Invoked after {@link #additionalTeardown()} when a test class reuses a
     * fixture-owned server. Subclasses use this to reset and verify all
     * mutable state before another case can acquire the fixture.
     */
    protected void afterReusedServerTestCase() throws Exception {
        // Default implementation does nothing.
    }

    /**
     * Generate a random event name using faker
     */
    protected String generateEventName() {
        return faker.lorem().word() + "Event";
    }

    /**
     * Generate a random event name with a specific prefix
     */
    protected String generateEventName(String prefix) {
        return prefix + faker.lorem().word() + "Event";
    }

    /**
     * Generate a random event name with a specific suffix
     */
    protected String generateEventNameWithSuffix(String suffix) {
        return faker.lorem().word() + suffix;
    }

    /**
     * Generate a random test data string
     */
    protected String generateTestData() {
        return faker.lorem().sentence();
    }

    /**
     * Generate a random test data string with specific length
     */
    protected String generateTestData(int wordCount) {
        return faker.lorem().sentence(wordCount);
    }

    /**
     * Generate a random room name
     */
    protected String generateRoomName() {
        return faker.lorem().word() + "Room";
    }

    /**
     * Generate a random room name with a specific prefix
     */
    protected String generateRoomName(String prefix) {
        return prefix + faker.lorem().word() + "Room";
    }

    /**
     * Generate a random namespace name
     */
    protected String generateNamespaceName() {
        return "/" + faker.lorem().word();
    }

    /**
     * Generate a random namespace name with a specific prefix
     */
    protected String generateNamespaceName(String prefix) {
        return "/" + prefix + faker.lorem().word();
    }

    /**
     * Generate a random acknowledgment message
     */
    protected String generateAckMessage() {
        return "Acknowledged: " + faker.lorem().sentence();
    }

    /**
     * Generate a random acknowledgment message with specific data
     */
    protected String generateAckMessage(String data) {
        return "Acknowledged: " + data;
    }

    /**
     * Generate a random error message
     */
    protected String generateErrorMessage() {
        return faker.lorem().sentence() + " error";
    }

    /**
     * Generate a random status message
     */
    protected String generateStatusMessage() {
        return faker.lorem().word() + " status: " + faker.lorem().sentence();
    }


    protected void configureNamespaces(SocketIOServer server) {}

}
