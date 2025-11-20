---
layout: default
title: Configuration
nav_order: 5
description: "Configuration options for Netty-SocketIO server"
permalink: /configuration/
---

# Configuration

The `Configuration` class provides various options to customize your Socket.IO server.

## Basic Configuration

```java
Configuration config = new Configuration();
config.setHostname("localhost");
config.setPort(9092);
```

## Network Configuration

### Hostname and Port

```java
config.setHostname("0.0.0.0");  // Listen on all interfaces
config.setPort(9092);
```

### Context Path

```java
config.setContext("/socket.io");
```

## SSL/TLS Configuration

### Basic SSL Setup

```java
SocketSslConfig sslConfig = new SocketSslConfig();
sslConfig.setKeyStoreFile("path/to/keystore.jks");
sslConfig.setKeyStorePassword("password");
config.setSocketSslConfig(sslConfig);
```

### Advanced SSL Options

```java
sslConfig.setKeyStoreType("JKS");
sslConfig.setTrustStoreFile("path/to/truststore.jks");
sslConfig.setTrustStorePassword("password");
sslConfig.setSslProtocol("TLSv1.2");
sslConfig.setNeedClientAuth(true);
```

## Transport Configuration

### Allowed Transports

```java
config.setTransports(Transport.WEBSOCKET, Transport.POLLING);
```

### Max Frame Payload Length

```java
config.setMaxFramePayloadLength(1048576); // 1MB
```

## Heartbeat Configuration

### Ping/Pong Timeout

```java
config.setPingTimeout(60000);  // 60 seconds
config.setPingInterval(25000);  // 25 seconds
```

## Authorization

### Authorization Listener

```java
config.setAuthorizationListener(data -> {
    // Validate authorization data
    String token = data.getSingleUrlParam("token");
    return token != null && token.equals("valid-token");
});
```

## Client Store Configuration

### Memory Store (Default)

```java
// No configuration needed, uses memory store by default
```

### Redisson Store

```java
Config redissonConfig = new Config();
redissonConfig.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redisson = Redisson.create(redissonConfig);

config.setStoreFactory(new RedissonStoreFactory(redisson));
```

### Hazelcast Store

```java
ClientConfig hazelcastConfig = new ClientConfig();
hazelcastConfig.getNetworkConfig().addAddress("127.0.0.1:5701");
HazelcastInstance hazelcast = HazelcastClient.newHazelcastClient(hazelcastConfig);

config.setStoreFactory(new HazelcastStoreFactory(hazelcast));
```

## Event Configuration

### ACK Mode

```java
config.setAckMode(AckMode.AUTO);  // AUTO, MANUAL, or AUTO_SUCCESS_ONLY
```

### Exception Listener

```java
config.setExceptionListener(new ExceptionListener() {
    @Override
    public void onEventException(Exception e, List<Object> args, SocketIOClient client) {
        logger.error("Event exception", e);
    }
    
    // ... other exception methods
});
```

## CORS Configuration

### Enable CORS

```java
config.setEnableCors(true);
```

### Custom Origin

```java
config.setOrigin("https://example.com");
```

### Allow Headers

```java
config.setAllowHeaders(Arrays.asList("X-Custom-Header"));
```

## Performance Tuning

### Worker Threads

```java
config.setBossThreads(1);
config.setWorkerThreads(100);
```

### Write Buffer Watermarks

```java
config.setWriteBufferWaterMarkLow(32 * 1024);
config.setWriteBufferWaterMarkHigh(64 * 1024);
```

## Complete Example

```java
Configuration config = new Configuration();
config.setHostname("localhost");
config.setPort(9092);
config.setContext("/socket.io");
config.setPingTimeout(60000);
config.setPingInterval(25000);
config.setMaxFramePayloadLength(1048576);
config.setTransports(Transport.WEBSOCKET, Transport.POLLING);
config.setEnableCors(true);

SocketIOServer server = new SocketIOServer(config);
server.start();
```

For more details, see the [API Documentation]({% link api/index.md %}).

