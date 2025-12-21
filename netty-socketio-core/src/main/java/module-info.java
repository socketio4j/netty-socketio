module netty.socketio.core {
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
  exports com.socketio4j.socketio.store;
  exports com.socketio4j.socketio.store.event;
  exports com.socketio4j.socketio.transport;
  exports com.socketio4j.socketio.nativeio;
    exports com.socketio4j.socketio.store.hazelcast;
    exports com.socketio4j.socketio.store.memory;
    exports com.socketio4j.socketio.store.redis_pubsub;
    exports com.socketio4j.socketio.store.redis_reliable;
    exports com.socketio4j.socketio.store.redis_stream;
    requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.annotation;
  requires com.fasterxml.jackson.databind;

  requires static com.hazelcast.core;
  requires static redisson;
  requires static kafka.clients;
  requires static io.netty.transport.classes.epoll;
  requires static io.netty.transport.classes.io_uring;
  requires static io.netty.transport.classes.kqueue;
  requires io.netty.codec;
  requires io.netty.transport;
  requires io.netty.buffer;
  requires io.netty.common;
  requires io.netty.handler;
  requires io.netty.codec.http;
  requires org.slf4j;
    requires org.jetbrains.annotations;


}
