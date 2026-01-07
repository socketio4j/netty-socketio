package com.socketio4j.example.core;

import com.socketio4j.socketio.AckMode;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;


import java.util.UUID;

import com.socketio4j.socketio.metrics.MicrometerMetricsFactory;
import com.socketio4j.socketio.metrics.MicrometerSocketIOMetrics;
import com.socketio4j.socketio.namespace.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class CoreExampleMain {

    private static final Logger log =
            LoggerFactory.getLogger(CoreExampleMain.class);

    public static void main(String[] args) {
        Configuration config = new Configuration();
        config.setPort(4000);
        config.setMetricsEnabled(true);

        config.setAckMode(AckMode.AUTO_SUCCESS_ONLY);


        config.setMicrometerHistogramEnabled(true);
        config.setMetricsEnabled(true);
        MicrometerSocketIOMetrics mic = MicrometerMetricsFactory.otlpDefault(config.isMicrometerHistogramEnabled());
        config.setMetrics(mic);
        SocketIOServer server = new SocketIOServer(config);
        Namespace namespace = new Namespace("/example", config);
        server.addNamespace(namespace.getName());
        /*
        MicrometerSocketIOMetrics micrometerSocketIOMetrics = (MicrometerSocketIOMetrics) config.getMetrics();
        MetricsHttpServer metricsServer =
                new MetricsHttpServer(
                        micrometerSocketIOMetrics.prometheus(),
                        "0.0.0.0",
                        8080,
                        "/metrics"
                );

        //metricsServer.start();
*/
        server.getNamespace("/example").addConnectListener(client -> {
            String sessionRoom = client.getSessionId().toString(); // automatically exists
            client.joinRoom("room1");
            client.joinRoom("room2");
            //client.leaveRoom("room1");
            System.out.println("connected: " + sessionRoom + client);
            System.out.println(server.getNamespace("").getClient(UUID.fromString(sessionRoom)));
            // send private welcome
            server.getRoomOperations(sessionRoom)
                    .sendEvent("welcome", "your private room is " + sessionRoom);
        });
        server.addConnectListener(client -> {
            String sessionRoom = client.getSessionId().toString(); // automatically exists
            client.joinRoom("room1");
            //client.leaveRoom("room1");
            System.out.println("connected: " + sessionRoom + client);
            System.out.println(server.getNamespace("").getClient(UUID.fromString(sessionRoom)));
            // send private welcome
            server.getRoomOperations(sessionRoom)
                    .sendEvent("welcome", "your private room is " + sessionRoom);
        });

        server.addEventListener("ping-me", String.class, (client, data, ack) -> {
            String room = client.getSessionId().toString();
            server.getRoomOperations(room)
                    .sendEvent("pong", "pong: " + data);
        });
        server.addEventListener("hi", String.class, (client, data, ack) -> {
            String room = client.getSessionId().toString();
            server.getRoomOperations(room)
                    .sendEvent("pong", "pong: " + data);
        });
        server.getNamespace("/example").addEventListener("hi", String.class, (client, data, ack) -> {
            String room = client.getSessionId().toString();
            server.getRoomOperations(room)
                    .sendEvent("pong", "pong: " + data);
        });

        server.start();

        Runtime runtime = Runtime.getRuntime();

        // Register an anonymous inner class that extends Thread as a shutdown hook
        runtime.addShutdownHook(new Thread() {
            @Override
            public void run() {
                server.stop();
                //metricsServer.stop();
            }
        });
        System.out.println("Shutdown Hook Attached.");
        System.out.println("Socket.IO listening @ http://localhost:"+config.getPort());
        //System.out.println("Metrics exposed @ http://localhost:"+metricsServer.getPort()+"/metrics");
    }
}
