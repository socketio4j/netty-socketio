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
package com.socketio4j.socketio.store.container;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

/**
 * CI-safe customized MongoDB container for socketio4j tests.
 * <p>
 * Uses a single-node replica set so that change streams are available.
 */
public class CustomizedMongoContainer extends GenericContainer<CustomizedMongoContainer> {

    private static final Logger log =
            LoggerFactory.getLogger(CustomizedMongoContainer.class);

    private static final int MONGO_PORT = 27017;

    public CustomizedMongoContainer() {
        super(DockerImageName.parse("mongo:7.0.40"));

        withExposedPorts(MONGO_PORT);
        withCommand("--replSet", "rs0");
        withReuse(false);
        withStartupAttempts(3);
        withStartupTimeout(Duration.ofMinutes(2));
    }

    @Override
    public void start() {
        super.start();
        initReplicaSet();
        log.info("MongoDB replica set ready at {}", getConnectionString());
    }

    private void initReplicaSet() {
        try {
            ExecResult result = execInContainer(
                    "mongosh", "--eval",
                    "rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'localhost:" + MONGO_PORT + "'}]})"
            );
            log.debug("rs.initiate output: {}", result.getStdout());

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            while (System.currentTimeMillis() < deadline) {
                // rs.initiate() only starts an election, so rs.status().ok == 1 does not
                // yet mean this member can accept writes — wait for it to be PRIMARY.
                // --quiet keeps banners out of stdout, hello() throws until the set is
                // initiated (swallowed below), and the result is compared exactly: a
                // substring match would accept any output that merely contains the value.
                ExecResult status = execInContainer(
                        "mongosh", "--quiet", "--eval",
                        "try { db.hello().isWritablePrimary } catch (e) { false }"
                );
                String out = status.getStdout().trim();
                if ("true".equals(out)) {
                    return;
                }
                Thread.sleep(500);
            }
            throw new RuntimeException("MongoDB replica set not ready after 30s");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MongoDB replica set", e);
        }
    }

    public String getConnectionString() {
        return "mongodb://" + getHost() + ":" + getMappedPort(MONGO_PORT)
                + "/?replicaSet=rs0&directConnection=true";
    }

    public MongoClient createClient() {
        return MongoClients.create(getConnectionString());
    }
}
