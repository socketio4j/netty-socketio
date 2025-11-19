package com.socketio4j.socketio.examples.micronaut.base.controller;

import java.util.concurrent.atomic.AtomicReference;

import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.annotation.OnConnect;

import io.micronaut.context.annotation.Bean;
import jakarta.inject.Singleton;

/**
 * Test controller to demonstrate Socket.IO event handling in a Micronaut application.
 * This controller listens for client connections and stores the connected client reference.
 * It throws a RuntimeException in the onConnect method to simulate an error scenario.
 */
@Bean
@Singleton
public class TestController {
    AtomicReference<SocketIOClient> baseClient = new AtomicReference<>();

    public SocketIOClient getBaseClient() {
        return baseClient.get();
    }

    @OnConnect
    public void onConnect(SocketIOClient client) {
        baseClient.set(client);
        throw new RuntimeException("onConnect");
    }
}
