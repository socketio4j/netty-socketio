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

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import io.nats.client.Connection;
import io.nats.client.Nats;

import com.socketio4j.socketio.store.event.EventType;

/**
 * CI-safe customized NATS container for socketio4j tests.
 *
 * NATS Core:
 *  - no topics to create
 *  - subjects are implicit
 *  - readiness = successful connection + round-trip publish
 */
public class CustomizedNatsContainer extends GenericContainer<CustomizedNatsContainer> {

    private static final Logger log =
            LoggerFactory.getLogger(CustomizedNatsContainer.class);

    private static final int NATS_PORT = 4222;

    private static final List<String> SUBJECTS =
            Arrays.stream(EventType.values())
                    .map(EventType::name)
                    .collect(Collectors.toList());

    private Connection connection;

    public CustomizedNatsContainer() {
        super(DockerImageName.parse("nats:2.10-alpine"));

        withExposedPorts(NATS_PORT);
        withReuse(false);
        withStartupAttempts(3);
        withStartupTimeout(Duration.ofMinutes(1));
    }

    @Override
    public void start() {
        super.start();

        waitUntilNatsReady();
        warmUpSubjects();

        log.info("NATS ready at {}", getNatsUrl());
    }

    // ---------------------------------------------------------------------
    // Readiness
    // ---------------------------------------------------------------------

    /**
     * CI-safe readiness check:
     *  - connect
     *  - publish
     *  - flush
     */
    private void waitUntilNatsReady() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);

        while (true) {
            try {
                connection = Nats.connect(getNatsUrl());
                connection.publish("socketio4j.ready", "ping".getBytes());
                connection.flush(Duration.ofSeconds(2));
                return; // ready
            } catch (Exception e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new RuntimeException(
                            "NATS not ready after 30s", e);
                }
                sleep(500);
            }
        }
    }


    /**
     * NATS does not require subject creation,
     * but publishing once ensures:
     *  - connection stability
     *  - no lazy init surprises in tests
     */
    private void warmUpSubjects() {
        try {
            for (String subject : SUBJECTS) {
                connection.publish(subject, new byte[0]);
            }
            connection.flush(Duration.ofSeconds(2));
        } catch (Exception e) {
            throw new RuntimeException("Failed to warm up NATS subjects", e);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    public String getNatsUrl() {
        return "nats://" + getHost() + ":" + getMappedPort(NATS_PORT);
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void stop() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception ignored) {
        }
        super.stop();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted while waiting for NATS", ie);
        }
    }
}