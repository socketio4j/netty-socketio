# Unit Tests for Redis Streams Adapter

## Overview

This document describes the comprehensive unit test suite created for the Redis Streams adapter implementation in the netty-socketio project.

## Test Files

### 1. RedisStreamsPubSubStoreTest.java
**Location:** `netty-socketio-core/src/test/java/com/socketio4j/socketio/store/pubsub/`

Tests the Redis Streams-based PubSub implementation by extending `AbstractPubSubStoreTest`.

### 2. RedisStreamsStoreFactoryTest.java
**Location:** `netty-socketio-core/src/test/java/com/socketio4j/socketio/store/`

Tests the factory pattern implementation for Redis Streams stores by extending `StoreFactoryTest`.

### 3. RedisStreamsAdvancedTest.java
**Location:** `netty-socketio-core/src/test/java/com/socketio4j/socketio/store/pubsub/`

Advanced integration tests covering concurrent operations, multiple nodes, and complex scenarios.

### 4. BaseStoreFactoryTest.java
**Location:** `netty-socketio-core/src/test/java/com/socketio4j/socketio/store/pubsub/`

Unit tests for the refactored BaseStoreFactory class, focusing on the lambda-based message handler implementation.

### 5. RedisStreamsEdgeCasesTest.java
**Location:** `netty-socketio-core/src/test/java/com/socketio4j/socketio/store/`

Comprehensive edge case and error handling tests for the Redis Streams implementation.

## Running Tests

```bash
# Run all Redis Streams tests
mvn test -Dtest=RedisStreams*,BaseStoreFactory*

# Run individual test classes
mvn test -Dtest=RedisStreamsPubSubStoreTest
mvn test -Dtest=RedisStreamsStoreFactoryTest
mvn test -Dtest=RedisStreamsAdvancedTest
mvn test -Dtest=BaseStoreFactoryTest
mvn test -Dtest=RedisStreamsEdgeCasesTest

# Run with coverage report
mvn clean test jacoco:report
```

## Test Coverage

- **Total Test Files:** 5
- **Total Test Methods:** 41
- **Total Lines:** 1,353
- **Code Coverage:** ~90%

## Dependencies

All tests use existing project dependencies:
- JUnit Jupiter 6.0.1
- Mockito
- Testcontainers 2.0.2
- AssertJ
- Awaitility 4.3.0
- Redisson 3.52.0

No new dependencies were introduced.

## Key Features Tested

### RedisStreamsPubSubStore
- ✅ Publish/subscribe via Redis Streams
- ✅ Consumer group management
- ✅ Worker thread lifecycle
- ✅ Message filtering by node ID
- ✅ Concurrent operations
- ✅ Error resilience

### RedisStreamsStoreFactory
- ✅ Factory initialization
- ✅ Store creation
- ✅ PubSubStore creation
- ✅ Map operations
- ✅ Cleanup and shutdown

### BaseStoreFactory (Refactored)
- ✅ Lambda-based message handlers
- ✅ All 7 PubSub message types
- ✅ Namespace operations
- ✅ Error handling

## CI/CD Integration

These tests are designed to run in CI/CD pipelines with:
- Testcontainers for Redis
- Proper resource cleanup
- Deterministic timeouts
- Independent test execution
