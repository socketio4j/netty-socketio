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
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

### Spring Integration

If you're using Spring, add:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-spring</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

### Spring Boot Starter

For Spring Boot projects:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-spring-boot-starter</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

### Quarkus Integration

For Quarkus projects:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-quarkus</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

### Micronaut Integration

For Micronaut projects:

```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-micronaut</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

## Gradle

### Core Module

```gradle
implementation 'com.socketio4j:netty-socketio-core:{{ site.data.versions.project.latest_stable }}'
```

### Spring Boot Starter

```gradle
implementation 'com.socketio4j:netty-socketio-spring-boot-starter:{{ site.data.versions.project.latest_stable }}'
```

### Quarkus

```gradle
implementation 'com.socketio4j:netty-socketio-quarkus:{{ site.data.versions.project.latest_stable }}'
```

### Micronaut

```gradle
implementation 'com.socketio4j:netty-socketio-micronaut:{{ site.data.versions.project.latest_stable }}'
```

## Version Information

The latest stable version is **{{ site.data.versions.project.latest_stable }}**.

You can find all available versions on [Maven Central](https://mvnrepository.com/artifact/com.socketio4j/netty-socketio-core).

## Migration from Original netty-socketio

If you're migrating from the original `com.corundumstudio.socketio:netty-socketio`:

Replace:
```xml
<dependency>
  <groupId>com.corundumstudio.socketio</groupId>
  <artifactId>netty-socketio</artifactId>
  <version>{{ site.data.versions.project.legacy_version }}</version>
</dependency>
```

With:
```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-core</artifactId>
  <version>{{ site.data.versions.project.latest_stable }}</version>
</dependency>
```

The API is compatible, so no code changes are required.

## Optional Dependencies

### Redisson (for distributed stores)

```xml
<dependency>
  <groupId>org.redisson</groupId>
  <artifactId>redisson</artifactId>
  <version>{{ site.data.versions.dependencies.redisson.version }}</version>
</dependency>
```

### Hazelcast (for distributed stores)

```xml
<dependency>
  <groupId>com.hazelcast</groupId>
  <artifactId>hazelcast-client</artifactId>
  <version>{{ site.data.versions.dependencies.hazelcast.version }}</version>
</dependency>
```

## Java Version Requirements

- **Runtime**: Java 8 or higher
- **Build**: Java 11 or higher (required for module-info compilation)

