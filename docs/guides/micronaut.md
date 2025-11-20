---
layout: default
title: Micronaut Integration
parent: Integration Guides
nav_order: 3
description: "Complete guide for integrating Netty-SocketIO with Micronaut"
permalink: /guides/micronaut/
---

# Micronaut Integration Guide

This guide provides detailed instructions for integrating Netty-SocketIO with Micronaut.

## Overview

The `netty-socketio-micronaut` module provides dependency injection integration for Micronaut applications.

## Dependencies

Add the Micronaut integration to your `pom.xml`:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-micronaut</artifactId>
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

## Lifecycle Management

Use Micronaut lifecycle events:

```java
@Singleton
public class SocketIOService {
    
    @Inject
    SocketIOServer server;
    
    @EventListener
    void onStartup(ServerStartupEvent event) {
        server.start();
    }
}
```

## Next Steps

- Check out the [Micronaut Example]({% link examples/micronaut.md %})
- Read the [API Documentation]({% link api/index.md %})

