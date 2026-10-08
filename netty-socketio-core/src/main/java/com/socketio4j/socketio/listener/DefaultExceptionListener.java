/**
 * Copyright (c) 2025 The Socketio4j Project
 * Parent project : Copyright (c) 2012-2025 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.socketio4j.socketio.listener;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.SocketIOClient;

import io.netty.channel.ChannelHandlerContext;

public class DefaultExceptionListener extends ExceptionListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DefaultExceptionListener.class);

    @Override
    public void onEventException(Exception e, List<Object> args, SocketIOClient client) {
        log.error(e.getMessage(), e);
    }

    @Override
    public void onDisconnectException(Exception e, SocketIOClient client) {
        log.error(e.getMessage(), e);
    }

    @Override
    public void onConnectException(Exception e, SocketIOClient client) {
        log.error(e.getMessage(), e);
    }

    @Override
    public void onPingException(Exception e, SocketIOClient client) {
        log.error(e.getMessage(), e);
    }

    @Override
    public void onPongException(Exception e, SocketIOClient client) {
        log.error(e.getMessage(), e);
    }

    @Override
    public boolean exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        logException(e);
        return true;
    }

    private void logException(Throwable t) {
        if (log.isDebugEnabled()) {
            log.debug("Exception caught", t);
            return;
        }

        if (!isExpectedDisconnect(t)) {
            log.error("Unhandled exception", t);
        }
    }

    private boolean isExpectedDisconnect(Throwable t) {
        while (t != null) {
            if (t instanceof ClosedChannelException
                    || t instanceof EOFException) {
                return true;
            }

            if (t instanceof IOException) {
                String msg = t.getMessage();
                if (msg != null) {
                    msg = msg.toLowerCase(Locale.ROOT);
                    if (msg.contains("connection reset")
                            || msg.contains("broken pipe")
                            || msg.contains("connection aborted")
                            || msg.contains("connection closed")
                            || msg.contains("forcibly closed")
                            || msg.contains("software caused connection abort")) {
                        return true;
                    }
                }
            }

            t = t.getCause();
        }

        return false;
    }

    @Override
    public void onAuthException(Throwable e, SocketIOClient client) {
        log.error(e.getMessage(), e);
    }

}
