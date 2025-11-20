---
layout: default
title: Performance
nav_order: 9
has_children: true
description: "Performance benchmarks and reports"
permalink: /performance/
---

# Performance

Netty-SocketIO is designed for high performance and scalability.

## Performance Reports

Daily smoke test results are automatically generated and available in the [Performance Test Report]({% link PERFORMANCE_REPORT.md %}).

## Historical Performance Data

### Customer Feedback (2012)

- **Environment**: CentOS, 1 CPU, 4GB RAM on VM
- **Resource Usage**: CPU 10%, Memory 15%
- **Capacity**: 
  - 6,000 xhr-long polling sessions, or
  - 15,000 websocket sessions
- **Throughput**: 4,000 messages per second

### Customer Feedback (2014)

From Viktor Endersz - Kambi Sports Solutions:

> "To stress test the solution we run 30,000 simultaneous websocket clients and managed to peak at total of about 140,000 messages per second with less than 1 second average delay."

## Performance Characteristics

### Scalability

- Supports thousands of concurrent connections
- Efficient memory usage with connection pooling
- Lock-free and thread-safe implementation

### Throughput

- High message throughput (140,000+ messages/second)
- Low latency message delivery
- Efficient binary and text message handling

### Resource Efficiency

- Low CPU usage under load
- Efficient memory management
- Minimal garbage collection pressure

## Performance Testing

The project includes a smoke test module that runs daily performance tests:

- [netty-socketio-smoke-test](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-smoke-test)

### Running Performance Tests

```bash
cd netty-socketio-smoke-test
mvn test
```

## Optimization Tips

1. **Use WebSocket Transport**: WebSocket is more efficient than polling
2. **Configure Worker Threads**: Adjust worker thread count based on your CPU cores
3. **Use Distributed Stores**: For multi-node deployments, use Redisson or Hazelcast
4. **Tune Buffer Sizes**: Configure write buffer watermarks appropriately
5. **Monitor Resource Usage**: Keep an eye on CPU and memory usage

## Benchmarks

For the latest benchmark results, see the [Performance Test Report]({% link PERFORMANCE_REPORT.md %}) or check the [performance results directory](https://github.com/socketio4j/netty-socketio/tree/main/netty-socketio-smoke-test/performance-results).

