---
layout: default
title: Quarkus Integration
parent: Integration Guides
nav_order: 2
description: "Complete guide for integrating Netty-SocketIO with Quarkus"
permalink: /guides/quarkus/
---

# Quarkus Integration Guide

This guide provides detailed instructions for integrating Netty-SocketIO with Quarkus.

## Overview

The `netty-socketio-quarkus` module provides CDI integration for Quarkus applications.

## Dependencies

Add the Quarkus integration to your `pom.xml`:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-quarkus</artifactId>
  <version>3.0.1</version>
</dependency>
```

## Configuration

### Application Properties

```properties
socketio.hostname=localhost
socketio.port=9092
socketio.context=/socket.io
socketio.ping-timeout=60000
socketio.ping-interval=25000
```

## Event Handlers

### Using Annotations

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

## Lifecycle Management

Use Quarkus lifecycle events:

```java
@ApplicationScoped
public class SocketIOService {
    
    @Inject
    SocketIOServer server;
    
    void onStart(@Observes StartupEvent ev) {
        server.start();
    }
    
    void onStop(@Observes ShutdownEvent ev) {
        server.stop();
    }
}
```

## Next Steps

- Check out the [Quarkus Example]({% link examples/quarkus.md %})
- Read the [API Documentation]({% link api/index.md %})

