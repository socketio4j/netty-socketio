---
layout: default
title: Examples
nav_order: 6
has_children: true
description: "Code examples for Netty-SocketIO"
permalink: /examples/
---

# Examples

This section contains practical code examples for using Netty-SocketIO with different frameworks.

## Framework Examples

- [Spring Boot Example]({% link examples/spring-boot.md %})
- [Quarkus Example]({% link examples/quarkus.md %})
- [Micronaut Example]({% link examples/micronaut.md %})

## Basic Examples

### Simple Chat Server

```java
Configuration config = new Configuration();
config.setHostname("localhost");
config.setPort(9092);

SocketIOServer server = new SocketIOServer(config);

server.addEventListener("chatevent", ChatMessage.class, (client, data, ackRequest) -> {
    // Broadcast to all clients
    server.getBroadcastOperations().sendEvent("chatevent", data);
});

server.start();
```

### Room-based Chat

```java
server.addEventListener("join", String.class, (client, room, ackRequest) -> {
    client.joinRoom(room);
    server.getRoomOperations(room).sendEvent("message", 
        client.getSessionId() + " joined room " + room);
});

server.addEventListener("message", ChatMessage.class, (client, data, ackRequest) -> {
    String room = data.getRoom();
    server.getRoomOperations(room).sendEvent("message", data);
});
```

### Event with Acknowledgment

```java
server.addEventListener("request", RequestData.class, (client, data, ackRequest) -> {
    // Process request
    ResponseData response = processRequest(data);
    
    // Send acknowledgment
    ackRequest.sendAckData(response);
});
```

## Full Example Projects

Complete example projects are available in the [netty-socketio-examples](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-examples) module:

- [Spring Boot Base Example](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-examples/netty-socketio-examples-spring-boot-base)
- [Quarkus Base Example](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-examples/netty-socketio-examples-quarkus-base)
- [Micronaut Base Example](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-examples/netty-socketio-examples-micronaut-base)

