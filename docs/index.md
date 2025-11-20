---
layout: default
title: Home
nav_order: 1
description: "Socket.IO server implementation for Java based on Netty framework"
permalink: /
---

# Netty-SocketIO

Netty-SocketIO is an open-source Java implementation of the [Socket.IO](http://socket.io/) server, built on top of the [Netty](http://netty.io/) framework. This project is an active fork of the original netty-socketio project, keeping the library up-to-date with the latest dependencies and improvements.

## Features

- **Socket.IO Protocol Support**: Supports Socket.IO client versions 1.x - 4.x
- **Transport Protocols**: Supports both xhr-polling and websocket transports
- **Namespaces & Rooms**: Full support for namespaces and rooms
- **Acknowledgments**: Support for acknowledgment (ack) of received data
- **SSL/TLS**: Built-in SSL support
- **Client Store**: Supports multiple client stores (Redisson, Hazelcast, Memory)
- **Distributed Broadcast**: Supports distributed broadcast across multiple netty-socketio nodes
- **Framework Integration**: 
  - Spring Boot integration
  - Quarkus integration
  - Micronaut integration
- **Thread Safety**: Lock-free and thread-safe implementation
- **Declarative Configuration**: Handler configuration via annotations
- **OSGi Support**: OSGi bundle support
- **Java Module System**: Contains Java module info for JPMS

## Quick Start

Get started with Netty-SocketIO in minutes:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-core</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

```java
Configuration config = new Configuration();
config.setHostname("localhost");
config.setPort(9092);

SocketIOServer server = new SocketIOServer(config);
server.start();
```

[Get Started →]({% link getting-started.md %})

## Documentation

- [About]({% link about.md %}) - Project overview, migration guide, and release history
- [Installation Guide]({% link installation.md %}) - How to add Netty-SocketIO to your project
- [Configuration]({% link configuration.md %}) - Server configuration options
- [Examples]({% link examples/index.md %}) - Code examples for different frameworks
- [Integration Guides]({% link guides/index.md %}) - Framework-specific integration guides
- [API Documentation]({% link api/index.md %}) - Complete API reference
- [Performance]({% link performance.md %}) - Performance benchmarks and reports
- [Changelog]({% link changelog.md %}) - Version history and release notes

## Modules

The project is split into multiple modules:

- **netty-socketio-core**: Core Socket.IO server implementation
- **netty-socketio-spring**: Spring integration module
- **netty-socketio-spring-boot-starter**: Spring Boot auto-configuration
- **netty-socketio-quarkus**: Quarkus integration module
- **netty-socketio-micronaut**: Micronaut integration module

## Requirements

- **Java 8+** (minimum runtime requirement)
- **Java 11+** (required for building module-info)
- **Maven 3.0.5+**

## License

Licensed under the [Apache License 2.0](https://github.com/socketio4j/netty-socketio/blob/main/LICENSE.txt).

## Contributing

We welcome contributions! Please see our [Contributing Guide]({% link contributing.md %}) for details.

## Links

- [GitHub Repository](https://github.com/socketio4j/netty-socketio)
- [Maven Central](https://mvnrepository.com/artifact/com.socketio4j/netty-socketio-core)
- [Socket.IO Protocol](https://socket.io/docs/v4/)

## Examples

Please refer to the [netty-socketio-examples](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-examples) module for complete working examples.

