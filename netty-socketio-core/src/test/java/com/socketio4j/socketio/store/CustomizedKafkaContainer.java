package com.socketio4j.socketio.store;

/**
 * @author https://github.com/sanjomo
 * @date 15/12/25 6:16 pm
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class CustomizedKafkaContainer extends KafkaContainer {

    private static final Logger log =
            LoggerFactory.getLogger(CustomizedKafkaContainer.class);

    public CustomizedKafkaContainer() {
        super(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        withReuse(true);
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
