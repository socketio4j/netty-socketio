---
layout: default
title: Spring Boot Integration
parent: Integration Guides
nav_order: 1
description: "Complete guide for integrating Netty-SocketIO with Spring Boot"
permalink: /guides/spring-boot/
---

# Spring Boot Integration Guide

This guide provides detailed instructions for integrating Netty-SocketIO with Spring Boot.

## Overview

The `netty-socketio-spring-boot-starter` module provides auto-configuration for Spring Boot applications, making it easy to set up and use Socket.IO in your Spring Boot project.

## Dependencies

Add the Spring Boot Starter to your `pom.xml`:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-spring-boot-starter</artifactId>
  <version>3.0.1</version>
</dependency>
```

## Auto-Configuration

The starter automatically configures a `SocketIOServer` bean based on your application properties.

### Application Properties

```properties
# Socket.IO Server Configuration
socketio.hostname=localhost
socketio.port=9092
socketio.context=/socket.io
socketio.ping-timeout=60000
socketio.ping-interval=25000
socketio.max-frame-payload-length=1048576
socketio.enable-cors=true
```

## Custom Configuration

### Using @Configuration

```java
@Configuration
public class SocketIOConfig {
    
    @Bean
    @Primary
    public SocketIOServer socketIOServer() {
        Configuration config = new Configuration();
        config.setHostname("localhost");
        config.setPort(9092);
        
        // Custom configuration
        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        
        return new SocketIOServer(config);
    }
}
```

## Event Handlers

### Using Annotations

The Spring integration supports annotation-based event handlers:

```java
@Component
public class ChatEventHandler {
    
    @Autowired
    private SocketIOServer server;
    
    @OnConnect
    public void onConnect(SocketIOClient client) {
        log.info("Client connected: {}", client.getSessionId());
    }
    
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        log.info("Client disconnected: {}", client.getSessionId());
    }
    
    @OnEvent("chatevent")
    public void onChatEvent(SocketIOClient client, ChatMessage data, AckRequest ackRequest) {
        server.getBroadcastOperations().sendEvent("chatevent", data);
    }
}
```

### Manual Registration

You can also register event listeners manually:

```java
@Component
public class SocketIOInitializer implements CommandLineRunner {
    
    @Autowired
    private SocketIOServer server;
    
    @Override
    public void run(String... args) throws Exception {
        server.addEventListener("chatevent", ChatMessage.class, (client, data, ackRequest) -> {
            server.getBroadcastOperations().sendEvent("chatevent", data);
        });
    }
}
```

## Exception Handling

Configure a custom exception listener:

```java
@Configuration
public class SocketIOExceptionConfig {
    
    @Bean
    public ExceptionListener exceptionListener() {
        return new ExceptionListener() {
            @Override
            public void onEventException(Exception e, List<Object> args, SocketIOClient client) {
                log.error("Event exception", e);
            }
            
            // Implement other exception methods
        };
    }
}
```

## Lifecycle Management

The server is automatically started when the Spring application starts and stopped when the application shuts down.

## Best Practices

1. **Use @Component for Event Handlers**: Keep event handlers as Spring beans for better dependency injection
2. **Configure Exception Listeners**: Always provide custom exception handling
3. **Use Application Properties**: Prefer configuration via properties over code
4. **Resource Cleanup**: The starter handles server lifecycle automatically

## Troubleshooting

### Server Not Starting

Check that the port is not already in use:

```bash
netstat -an | grep 9092
```

### Events Not Received

Ensure that:
- Event handlers are registered as Spring beans
- The `@OnEvent` annotation is correctly used
- Event names match between client and server

## Next Steps

- Check out the [Spring Boot Example]({% link examples/spring-boot.md %})
- Read the [API Documentation]({% link api/index.md %})
- Explore [Configuration Options]({% link configuration.md %})

