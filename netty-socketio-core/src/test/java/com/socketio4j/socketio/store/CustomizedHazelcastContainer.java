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
package com.socketio4j.socketio.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

/**
 * Optimized Hazelcast container for testing.
 *
 * - No artificial sleeps
 * - No polling loops
 * - Uses Testcontainers' built-in readiness checks
 * - Much faster test execution
 */
public class CustomizedHazelcastContainer extends GenericContainer<CustomizedHazelcastContainer> {

    private static final Logger log = LoggerFactory.getLogger(CustomizedHazelcastContainer.class);

    public static final int HAZELCAST_PORT = 5701;

    public CustomizedHazelcastContainer() {
        super("hazelcast/hazelcast:5.6.0");
    }

    @Override
    protected void configure() {

        withExposedPorts(HAZELCAST_PORT);

        // Map custom test config file
        withEnv("JVM_OPTS", "-Dhazelcast.config=/opt/hazelcast/config_ext/hazelcast.xml");
        withClasspathResourceMapping(
                "hazelcast-test-config.xml",
                "/opt/hazelcast/config_ext/hazelcast.xml",
                BindMode.READ_ONLY
        );

        // Proper readiness check (fast, reliable, no sleeps)
        waitingFor(
                Wait.forListeningPort()
                        .withStartupTimeout(Duration.ofSeconds(300))
        );
    }

    @Override
    public void start() {
        super.start();
        log.info("Hazelcast started at port {}", getHazelcastPort());
    }

    @Override
    public void stop() {
        log.info("Hazelcast shutting down...");
        super.stop();
        log.info("Hazelcast stopped");
    }

    public int getHazelcastPort() {
        return getMappedPort(HAZELCAST_PORT);
    }
}
