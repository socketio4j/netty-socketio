---
layout: default
title: Quarkus Example
parent: Examples
nav_order: 2
description: "Quarkus integration example"
permalink: /examples/quarkus/
---

# Quarkus Example

This example shows how to integrate Netty-SocketIO with Quarkus.

## Dependencies

Add the Quarkus integration dependency:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-quarkus</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

## Configuration

### application.properties

```properties
socketio.hostname=localhost
socketio.port=9092
socketio.ping-timeout=60000
socketio.ping-interval=25000
```

## Main Application

```java
@ApplicationScoped
public class QuarkusMainApplication {
    
    @Inject
    SocketIOServer server;
    
    void onStart(@Observes StartupEvent ev) {
        // Add event listeners
        server.addEventListener("chatevent", ChatMessage.class, (client, data, ackRequest) -> {
            server.getBroadcastOperations().sendEvent("chatevent", data);
        });
        
        server.start();
        log.info("Socket.IO server started on port {}", server.getConfiguration().getPort());
    }
    
    void onStop(@Observes ShutdownEvent ev) {
        server.stop();
    }
}
```

## Event Handlers

```java
@ApplicationScoped
public class ChatEventHandler {
    
    @Inject
    SocketIOServer server;
    
    @OnConnect
    public void onConnect(SocketIOClient client) {
        log.info("Client connected: {}", client.getSessionId());
    }
    
    @OnEvent("chatevent")
    public void onChatEvent(SocketIOClient client, ChatMessage data) {
        server.getBroadcastOperations().sendEvent("chatevent", data);
    }
}
```

## Full Example

See the complete example in the [netty-socketio-examples-quarkus-base](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-examples/netty-socketio-examples-quarkus-base) module.

