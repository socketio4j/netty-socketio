---
layout: default
title: Getting Started
nav_order: 3
description: "Quick start guide for Netty-SocketIO"
permalink: /getting-started/
---

# Getting Started

This guide will help you get started with Netty-SocketIO in just a few minutes.

## Prerequisites

- Java 8 or higher
- Maven 3.0.5 or higher (or Gradle)
- Basic knowledge of Java and Socket.IO

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-core</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

For Gradle:

```gradle
implementation 'com.socketio4j:netty-socketio-core:{{ site.data.versions.project.latest_stable }}'
```

## Basic Server Setup

Here's a minimal example to get you started:

```java
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;

public class SimpleServer {
    public static void main(String[] args) throws InterruptedException {
        Configuration config = new Configuration();
        config.setHostname("localhost");
        config.setPort(9092);

        SocketIOServer server = new SocketIOServer(config);
        
        // Add event listeners
        server.addEventListener("chatevent", ChatObject.class, (client, data, ackRequest) -> {
            server.getBroadcastOperations().sendEvent("chatevent", data);
        });
        
        server.start();
        
        Thread.sleep(Integer.MAX_VALUE);
        
        server.stop();
    }
}
```

## Basic Client Connection

On the client side (JavaScript):

```javascript
const socket = io('http://localhost:9092');

socket.on('connect', () => {
  console.log('Connected to server');
  
  socket.emit('chatevent', {
    userName: 'John',
    message: 'Hello, World!'
  });
});

socket.on('chatevent', (data) => {
  console.log('Received:', data);
});
```

## Next Steps

- Learn about [Configuration Options]({% link configuration.md %})
- Check out [Examples]({% link examples/index.md %}) for framework-specific setups
- Read the [API Documentation]({% link api/index.md %}) for detailed information
- Explore [Integration Guides]({% link guides/index.md %}) for Spring Boot, Quarkus, or Micronaut

