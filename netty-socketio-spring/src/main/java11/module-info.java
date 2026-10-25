module netty.socketio.spring {
  exports com.socketio4j.socketio.spring;

  requires netty.socketio.core;
  requires static spring.beans;
  requires static spring.core;
  requires org.slf4j;
}
