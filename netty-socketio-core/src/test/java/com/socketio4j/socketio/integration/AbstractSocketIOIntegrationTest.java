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

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
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
        // Create SocketIO server configuration
        Configuration serverConfig = new Configuration();
        serverConfig.setHostname(SERVER_HOST);

        boolean successful = false;
        while (!successful) {
            try {
                // Find an available port for this test
                serverPort = findAvailablePort();
                serverConfig.setPort(serverPort);

                // Allow subclasses to customize configuration
                configureServer(serverConfig);

                // Create and start server
                server = new SocketIOServer(serverConfig);
                server.start();

                // Verify server started successfully
                successful = true;
            } catch (Exception e) {
                log.warn("Port {} is not available, retrying...", serverPort);
                // If server failed to start, try again with a different port
                TimeUnit.SECONDS.sleep(1);
            }
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
        // Allow subclasses to do additional teardown
        additionalTeardown();

        // Stop SocketIO server
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                // Log but don't fail the test
                System.err.println("Error stopping SocketIO server: " + e.getMessage());
            }
        }
    }

    /**
     * Hook method for subclasses to add custom server configuration.
     * Called after basic configuration but before server start.
     */
    protected void configureServer(Configuration config) {
        // Default implementation does nothing
        // Subclasses can override to add custom configuration
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
}
