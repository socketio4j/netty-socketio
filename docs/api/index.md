---
layout: default
title: API Documentation
nav_order: 8
description: "Complete API reference for Netty-SocketIO"
permalink: /api/
---

# API Documentation

Complete API reference for all Netty-SocketIO modules.

## Core Module

The core module provides the main Socket.IO server implementation.

### Main Classes

- **[SocketIOServer]({{ site.javadoc.core }}/com/socketio4j/socketio/SocketIOServer.html)** - Main server class for managing Socket.IO connections
- **[SocketIONamespace]({{ site.javadoc.core }}/com/socketio4j/socketio/SocketIONamespace.html)** - Namespace management interface
- **[SocketIOClient]({{ site.javadoc.core }}/com/socketio4j/socketio/SocketIOClient.html)** - Client connection representation
- **[Configuration]({{ site.javadoc.core }}/com/socketio4j/socketio/Configuration.html)** - Server configuration class

### Key Interfaces

- **[ClientListeners]({{ site.javadoc.core }}/com/socketio4j/socketio/listener/ClientListeners.html)** - Client event listeners
- **[BroadcastOperations]({{ site.javadoc.core }}/com/socketio4j/socketio/BroadcastOperations.html)** - Broadcast operations interface
- **[StoreFactory]({{ site.javadoc.core }}/com/socketio4j/socketio/store/StoreFactory.html)** - Client store factory interface

## Module Javadoc

- [Core Module Javadoc]({{ site.javadoc.core }}) - Core Socket.IO server implementation
- [Spring Module Javadoc]({{ site.javadoc.spring }}) - Spring integration
- [Spring Boot Starter Javadoc]({{ site.javadoc.spring_boot_starter }}) - Spring Boot auto-configuration
- [Quarkus Module Javadoc]({{ site.javadoc.quarkus }}) - Quarkus integration
- [Micronaut Module Javadoc]({{ site.javadoc.micronaut }}) - Micronaut integration

## Quick Reference

### Creating a Server

```java
Configuration config = new Configuration();
config.setHostname("localhost");
config.setPort(9092);

SocketIOServer server = new SocketIOServer(config);
server.start();
```

### Adding Event Listeners

```java
server.addEventListener("eventName", EventData.class, (client, data, ackRequest) -> {
    // Handle event
});
```

### Broadcasting Messages

```java
// Broadcast to all clients
server.getBroadcastOperations().sendEvent("eventName", data);

// Broadcast to a room
server.getRoomOperations("roomName").sendEvent("eventName", data);
```

### Client Operations

```java
// Get all clients
Collection<SocketIOClient> clients = server.getAllClients();

// Get client by ID
SocketIOClient client = server.getClient(uuid);

// Send to specific client
client.sendEvent("eventName", data);
```

## Package Structure

### Core Packages

- `com.socketio4j.socketio` - Main server classes
- `com.socketio4j.socketio.listener` - Event listeners
- `com.socketio4j.socketio.annotation` - Annotation-based configuration
- `com.socketio4j.socketio.store` - Client store implementations
- `com.socketio4j.socketio.transport` - Transport implementations

For detailed API documentation, see the [Javadoc links](#module-javadoc) above.

