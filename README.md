# Netty-SocketIO

<div align="center">

[![Maven Central](https://img.shields.io/maven-central/v/com.socketio4j/netty-socketio-core.svg?style=flat-square&label=Maven%20Central)](https://mvnrepository.com/artifact/com.socketio4j/netty-socketio-core)
[![GitHub release](https://img.shields.io/github/release/socketio4j/netty-socketio.svg?style=flat-square&logo=github)](https://github.com/socketio4j/netty-socketio/releases)
[![GitHub Stars](https://img.shields.io/github/stars/socketio4j/netty-socketio?style=flat-square&logo=github)](https://github.com/socketio4j/netty-socketio/stargazers)
[![GitHub Forks](https://img.shields.io/github/forks/socketio4j/netty-socketio?style=flat-square&logo=github)](https://github.com/socketio4j/netty-socketio/network/members)
[![GitHub Issues](https://img.shields.io/github/issues/socketio4j/netty-socketio?style=flat-square&logo=github)](https://github.com/socketio4j/netty-socketio/issues)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)

[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg?style=flat-square&logo=java&logoColor=white)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-3.0.5%2B-red.svg?style=flat-square&logo=apache-maven&logoColor=white)](https://maven.apache.org/)
[![Netty](https://img.shields.io/badge/Netty-4.2.7-green.svg?style=flat-square)](https://netty.io/)
[![Socket.IO](https://img.shields.io/badge/Socket.IO-1.x--4.x-black.svg?style=flat-square&logo=socket.io&logoColor=white)](https://socket.io/)

</div>

Socket.IO server implementation for Java based on Netty framework.

## Documentation

📚 **Full documentation is available at: [https://socketio4j.github.io/netty-socketio](https://socketio4j.github.io/netty-socketio)**

The documentation includes:
- [Getting Started Guide](https://socketio4j.github.io/netty-socketio/getting-started/)
- [Installation Instructions](https://socketio4j.github.io/netty-socketio/installation/)
- [Configuration Options](https://socketio4j.github.io/netty-socketio/configuration/)
- [Code Examples](https://socketio4j.github.io/netty-socketio/examples/)
- [Integration Guides](https://socketio4j.github.io/netty-socketio/guides/)
- [API Documentation](https://socketio4j.github.io/netty-socketio/api/)
- [Performance Reports](https://socketio4j.github.io/netty-socketio/performance/)


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

