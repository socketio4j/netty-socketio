package com.socketio4j.socketio.listener;

import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.SocketIOClient;

import java.util.List;

/**
 * @author https://github.com/sanjomo
 * @date 27/11/25 10:31 am
 */
@FunctionalInterface
public interface CatchAllEventListener {
    void onEvent(SocketIOClient client, String eventName, List<Object> args, AckRequest ackRequest);
}
