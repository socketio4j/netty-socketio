---
layout: default
title: Spring Boot Example
parent: Examples
nav_order: 1
description: "Spring Boot integration example"
permalink: /examples/spring-boot/
---

# Spring Boot Example

This example shows how to integrate Netty-SocketIO with Spring Boot.

## Dependencies

Add the Spring Boot Starter dependency:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-spring-boot-starter</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

## Configuration

### application.properties

```properties
netty-socket-io.hostname=localhost
netty-socket-io.port=9092
netty-socket-io.ping-timeout=60000
netty-socket-io.ping-interval=25000
```

### Custom Configuration Bean

```java
@Configuration
public class SocketIOConfig {
    
    @Bean
    public SocketIOServer socketIOServer() {
        Configuration config = new Configuration();
        config.setHostname("localhost");
        config.setPort(9092);
        return new SocketIOServer(config);
    }
}
```

## Main Application

```java
@SpringBootApplication
public class SpringBootMainApplication implements CommandLineRunner {
    
    private static final Logger log = LoggerFactory.getLogger(SpringBootMainApplication.class);
    
    @Autowired
    private SocketIOServer server;
    
    public static void main(String[] args) {
        SpringApplication.run(SpringBootMainApplication.class, args);
    }
    
    @Override
    public void run(String... args) throws Exception {
        // Add event listeners
        server.addEventListener("chatevent", ChatMessage.class, (client, data, ackRequest) -> {
            server.getBroadcastOperations().sendEvent("chatevent", data);
        });
        
        server.start();
        log.info("Socket.IO server started on port {}", server.getConfiguration().getPort());
    }
}
```

## Event Handlers with Annotations

You can use annotations to define event handlers:

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

## Exception Handling

```java
@Configuration
public class CustomizedSocketIOConfiguration {
    
    @Bean
    public ExceptionListener exceptionListener() {
        return new ExceptionListener() {
            @Override
            public void onEventException(Exception e, List<Object> args, SocketIOClient client) {
                log.error("Event exception", e);
            }
            
            @Override
            public void onDisconnectException(Exception e, SocketIOClient client) {
                log.error("Disconnect exception", e);
            }
            
            // ... implement other methods
        };
    }
}
```

## Full Example

See the complete example in the [netty-socketio-examples-spring-boot-base](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-examples/netty-socketio-examples-spring-boot-base) module.

