---
layout: default
title: About
nav_order: 2
description: "About Netty-SocketIO project"
permalink: /about/
---

# About Netty-SocketIO

This project is an active fork of [netty-socketio](https://github.com/mrniko/netty-socketio), which is no longer actively maintained. The fork aims to keep the library up-to-date with the latest dependencies and improvements, ensuring continued support and functionality for users.

- The main development branch is `main`, which contains the latest features and fixes.
- This project is an open-source Java implementation of [Socket.IO](http://socket.io/) server. Based on [Netty](http://netty.io/) server framework.

Licensed under the Apache License 2.0.

## Migration from Original netty-socketio

3.0.0 is the same as 2.0.13 with split modules, integration tests, and some CI/CD improvements. Replace old dependency:

```xml
<dependency>
  <groupId>com.corundumstudio.socketio</groupId>
  <artifactId>netty-socketio</artifactId>
  <version>2.0.13</version>
</dependency>
```

with new one:
```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-core</artifactId>
  <version>LATEST-VERSION</version>
</dependency>
```

If you use Spring integration (Just one class: `SpringAnnotationScanner`), you should use the new dependency:
```xml
<dependency>
  <groupId>com.socketio4j</groupId>
  <artifactId>netty-socketio-spring</artifactId>
  <version>LATEST-VERSION</version>
</dependency>
```

## Projects Using Netty-SocketIO

- Multiplayer Orchestra: [multiplayer-orchestra.com](https://multiplayer-orchestra.com/)
- AVOS Cloud: [avoscloud.com](https://avoscloud.com/)
- Kambi Sports Solutions: [kambi.com](http://kambi.com/)
- ARSnova: [arsnova.eu](https://arsnova.eu)

## Release History

For complete version history and release notes, see the [Changelog]({% link changelog.md %}).

