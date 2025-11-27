package com.socketio4j.socketio;

import com.socketio4j.socketio.namespace.Namespace;

/**
 * @author https://github.com/sanjomo
 * @date 27/11/25 10:37 am
 */
public class zTest {
    public static void main(String[] args) {
        Configuration config = new Configuration();
        config.setPort(9092);

        SocketIOServer server = new SocketIOServer(config);

        Namespace ns = (Namespace) server.addNamespace("/stream");

// your new catch-all registration
        ns.onAny((client, eventName, fff, ack) -> {
            System.out.println("ANY EVENT: " + eventName + " args=" + fff);
        });

// normal event listener
        ns.addEventListener("message", String.class, (client, data, ack) -> {
            System.out.println("Message: " + data);
        });

        server.start();

    }
}
