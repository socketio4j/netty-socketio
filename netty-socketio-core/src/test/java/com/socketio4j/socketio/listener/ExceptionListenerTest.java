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

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.SocketIOClient;

import io.netty.channel.ChannelHandlerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

@DisplayName("ExceptionListener Tests")
class ExceptionListenerTest {

    private final SocketIOClient client = mock(SocketIOClient.class);
    private final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    private final Exception exception = new IllegalStateException("failed");

    @Test
    @DisplayName("Should swallow every exception and not handle exceptionCaught by default")
    void adapterShouldSwallowExceptions() throws Exception {
        ExceptionListenerAdapter listener = new ExceptionListenerAdapter() {
            @Override
            public void onAuthException(Throwable e, SocketIOClient client) {
            }
        };

        assertThatCode(() -> {
            listener.onEventException(exception, Collections.singletonList("data"), client);
            listener.onDisconnectException(exception, client);
            listener.onConnectException(exception, client);
            listener.onPingException(exception, client);
            listener.onPongException(exception, client);
        }).doesNotThrowAnyException();

        assertThat(listener.exceptionCaught(ctx, exception)).isFalse();
    }

    @Test
    @DisplayName("Should log every exception and handle exceptionCaught by default")
    void defaultListenerShouldHandleExceptionCaught() throws Exception {
        DefaultExceptionListener listener = new DefaultExceptionListener();

        assertThatCode(() -> {
            listener.onEventException(exception, Collections.singletonList("data"), client);
            listener.onDisconnectException(exception, client);
            listener.onConnectException(exception, client);
            listener.onPingException(exception, client);
            listener.onPongException(exception, client);
            listener.onAuthException(exception, client);
        }).doesNotThrowAnyException();

        assertThat(listener.exceptionCaught(ctx, exception)).isTrue();
    }
}
