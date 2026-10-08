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
package com.socketio4j.socketio.integration.protocol;

import java.net.ServerSocket;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;

/**
 * JVM-scoped pool of immutable single-node Socket.IO test fixtures.
 *
 * <p>The Maven execution that selects pooled tests uses one reusable fork, so
 * an instance of a profile is shared only by serial tests that explicitly
 * restore its clients, namespaces, rooms, and listeners. The fixtures are
 * stopped once at JVM exit; they are never released to ordinary tests.</p>
 */
public final class SharedSocketIOServerFixtures {

    private static final Logger log = LoggerFactory.getLogger(SharedSocketIOServerFixtures.class);
    private static final String HOST = "localhost";
    private static final int MAX_START_ATTEMPTS = 10;
    private static final Map<SharedServerFixtureProfile, Fixture> FIXTURES =
            new EnumMap<SharedServerFixtureProfile, Fixture>(SharedServerFixtureProfile.class);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                stopAll();
            }
        }, "shared-socketio-test-fixtures-shutdown"));
    }

    private SharedSocketIOServerFixtures() {
    }

    public static synchronized Fixture fixture(SharedServerFixtureProfile profile) throws Exception {
        Fixture current = FIXTURES.get(profile);
        if (current != null && current.server.isStarted()) {
            return current;
        }

        Fixture started = startFixture(profile);
        FIXTURES.put(profile, started);
        return started;
    }

    private static Fixture startFixture(SharedServerFixtureProfile profile) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
            SocketIOServer server = null;
            int port = 0;
            try {
                port = findAvailablePort();
                Configuration configuration = new Configuration();
                configuration.setHostname(HOST);
                configuration.setPort(port);
                profile.configure(configuration);

                server = new SocketIOServer(configuration);
                server.start();
                return new Fixture(server, port);
            } catch (Exception failure) {
                lastFailure = failure;
                if (server != null) {
                    try {
                        server.stop();
                    } catch (Exception stopFailure) {
                        failure.addSuppressed(stopFailure);
                    }
                }

                log.warn("Shared {} server start attempt {}/{} on port {} failed: {}",
                        profile, attempt, MAX_START_ATTEMPTS, port, failure.toString());
                if (attempt < MAX_START_ATTEMPTS) {
                    TimeUnit.SECONDS.sleep(1);
                }
            }
        }

        throw new IllegalStateException(
                "Unable to start shared " + profile + " Socket.IO fixture after "
                        + MAX_START_ATTEMPTS + " attempts",
                lastFailure);
    }

    private static int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static synchronized void stopAll() {
        for (Fixture fixture : FIXTURES.values()) {
            fixture.stop();
        }
        FIXTURES.clear();
    }

    public static final class Fixture {

        private final SocketIOServer server;
        private final int port;

        private Fixture(SocketIOServer server, int port) {
            this.server = server;
            this.port = port;
        }

        public SocketIOServer server() {
            return server;
        }

        public int port() {
            return port;
        }

        private void stop() {
            if (server.isStarted()) {
                server.stop();
            }
        }
    }
}
