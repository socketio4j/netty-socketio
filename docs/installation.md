---
layout: default
title: Installation
nav_order: 4
description: "Installation guide for Netty-SocketIO"
permalink: /installation/
---

# Installation

This page describes how to add Netty-SocketIO to your project.

## Maven

Add the following dependency to your `pom.xml`:

### Core Module

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-core</artifactId>
  <version>3.0.1</version>
</dependency>
```

### Spring Integration

If you're using Spring, add:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-spring</artifactId>
  <version>3.0.1</version>
</dependency>
```

### Spring Boot Starter

For Spring Boot projects:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-spring-boot-starter</artifactId>
  <version>3.0.1</version>
</dependency>
```

### Quarkus Integration

For Quarkus projects:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-quarkus</artifactId>
  <version>3.0.1</version>
</dependency>
```

### Micronaut Integration

For Micronaut projects:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-micronaut</artifactId>
  <version>3.0.1</version>
</dependency>
```

## Gradle

### Core Module

```gradle
implementation 'com.socketio4j:netty-socketio-core:3.0.1'
```

### Spring Boot Starter

```gradle
implementation 'com.socketio4j:netty-socketio-spring-boot-starter:3.0.1'
```

### Quarkus

```gradle
implementation 'com.socketio4j:netty-socketio-quarkus:3.0.1'
```

### Micronaut

```gradle
implementation 'com.socketio4j:netty-socketio-micronaut:3.0.1'
```

## Version Information

The latest stable version is **3.0.1**.

You can find all available versions on [Maven Central](https://mvnrepository.com/artifact/com.socketio4j/netty-socketio-core).

## Migration from Original netty-socketio

If you're migrating from the original `com.corundumstudio.socketio:netty-socketio`:

Replace:
```xml
<dependency>
  <groupId>com.corundumstudio.socketio</groupId>
  <artifactId>netty-socketio</artifactId>
  <version>2.0.13</version>
</dependency>
```

With:
```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-core</artifactId>
  <version>3.0.1</version>
</dependency>
```

The API is compatible, so no code changes are required.

## Optional Dependencies

### Redisson (for distributed stores)

```xml
<dependency>
  <groupId>org.redisson</groupId>
  <artifactId>redisson</artifactId>
  <version>3.45.1</version>
</dependency>
```

### Hazelcast (for distributed stores)

```xml
<dependency>
  <groupId>com.hazelcast</groupId>
  <artifactId>hazelcast-client</artifactId>
  <version>3.12.12</version>
</dependency>
```

## Java Version Requirements

- **Runtime**: Java 8 or higher
- **Build**: Java 11 or higher (required for module-info compilation)

