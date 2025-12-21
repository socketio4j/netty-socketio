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

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:16 pm
 */

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class CustomizedKafkaContainer extends KafkaContainer {

    private static final Logger log =
            LoggerFactory.getLogger(CustomizedKafkaContainer.class);

    public CustomizedKafkaContainer() {
        super(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        withReuse(false)
                .withStartupAttempts(3)
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    @Override
    public void start() {
        super.start();
        log.info("Kafka started at {}", getBootstrapServers());
    }

    @Override
    public void stop() {
        super.stop();
        log.info("Kafka stopped");
    }
}
