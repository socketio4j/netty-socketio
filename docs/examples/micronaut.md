---
layout: default
title: Micronaut Example
parent: Examples
nav_order: 3
description: "Micronaut integration example"
permalink: /examples/micronaut/
---

# Micronaut Example

This example shows how to integrate Netty-SocketIO with Micronaut.

## Dependencies

Add the Micronaut integration dependency:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-micronaut</artifactId>
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
@Singleton
public class MicronautMainApplication {
    
    @Inject
    SocketIOServer server;
    
    @EventListener
    void onStartup(ServerStartupEvent event) {
        // Add event listeners
        server.addEventListener("chatevent", ChatMessage.class, (client, data, ackRequest) -> {
            server.getBroadcastOperations().sendEvent("chatevent", data);
        });
        
        server.start();
        log.info("Socket.IO server started on port {}", server.getConfiguration().getPort());
    }
}
```

## Event Handlers

```java
@Singleton
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

See the complete example in the [netty-socketio-examples-micronaut-base](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-examples/netty-socketio-examples-micronaut-base) module.

