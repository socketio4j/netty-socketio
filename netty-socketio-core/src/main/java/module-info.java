/*******************************************************************************
 * netty.socketio.core module
 *
 * Export strategy:
 *   - Core socketio API & common packages           → exported
 *   - Store implementations (Redis/Hazelcast/etc.) → exported (public SPI)
 *   - Kafka serializer                             → opened only to kafka.clients (reflection)
 *
 * Dependency strategy:
 *   - `requires static` means optional integration when dependency is present
 *   - `opens … to` enables reflective access without exposing public API
 ******************************************************************************/

module netty.socketio.core {

  // ============================================================
  // Core API exports — used directly by library users
  // ============================================================
  exports com.socketio4j.socketio;
  exports com.socketio4j.socketio.ack;
  exports com.socketio4j.socketio.annotation;
  exports com.socketio4j.socketio.handler;
  exports com.socketio4j.socketio.listener;
  exports com.socketio4j.socketio.namespace;
  exports com.socketio4j.socketio.misc;
  exports com.socketio4j.socketio.messages;
  exports com.socketio4j.socketio.protocol;
  exports com.socketio4j.socketio.scheduler;
  exports com.socketio4j.socketio.transport;
  exports com.socketio4j.socketio.nativeio;

  // ============================================================
  // Core store interfaces + event APIs
  // ============================================================
  exports com.socketio4j.socketio.store;
  exports com.socketio4j.socketio.store.event;

  // ============================================================
  // Built-in store implementations (usable without extra deps)
  // ============================================================
  exports com.socketio4j.socketio.store.memory;

  // ============================================================
  // Optional stores — exported but dependency is static
  // These packages are part of the public store SPI surface
  // ============================================================
  exports com.socketio4j.socketio.store.hazelcast;
  exports com.socketio4j.socketio.store.redis_pubsub;
  exports com.socketio4j.socketio.store.redis_reliable;
  exports com.socketio4j.socketio.store.redis_stream;

  // ============================================================
  // Kafka-specific store
  // DO NOT export publicly
  // Only allow runtime reflection by Kafka client
  // ============================================================
  opens com.socketio4j.socketio.store.kafka.serialization to kafka.clients;


  // ============================================================
  // JSON serialization
  // ============================================================
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.annotation;
  requires com.fasterxml.jackson.databind;

  // ============================================================
  // Optional distributed stores — only required if present
  // ============================================================
  requires static com.hazelcast.core;
  requires static redisson;
  requires static kafka.clients;

  // ============================================================
  // Optional Netty native transports — only if available
  // ============================================================
  requires static io.netty.transport.classes.epoll;
  requires static io.netty.transport.classes.io_uring;
  requires static io.netty.transport.classes.kqueue;

  // ============================================================
  // Required Netty components
  // ============================================================
  requires io.netty.codec;
  requires io.netty.transport;
  requires io.netty.buffer;
  requires io.netty.common;
  requires io.netty.handler;
  requires io.netty.codec.http;

  // ============================================================
  // Logging + annotations
  // ============================================================
  requires org.slf4j;
  requires org.jetbrains.annotations;
}
