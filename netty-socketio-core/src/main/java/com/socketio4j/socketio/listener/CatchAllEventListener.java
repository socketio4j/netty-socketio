package com.socketio4j.socketio.listener;

import java.util.List;

import com.socketio4j.socketio.AckRequest;
// import com.socketio4j.socketio.SocketIOClient; // Removed as not needed after parameter removal


/**
 * @author https://github.com/sanjomo
 * @date 27/11/25 10:31 am
 */
@FunctionalInterface
public interface CatchAllEventListener {
    void onEvent(String eventName, List<Object> args, AckRequest ackRequest);
}
