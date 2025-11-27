package com.socketio4j.socketio.listener;

import java.util.List;

import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.SocketIOClient;


/**
 * @author https://github.com/sanjomo
 * @date 27/11/25 10:31 am
 */
@FunctionalInterface
public interface CatchAllEventListener {

    void onEvent(SocketIOClient client, String event, List<Object> args, AckRequest ackRequest);
}
