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
package com.socketio4j.socketio.misc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.FastThreadLocalThread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventLoopSafetyTest {

    private EventLoop eventLoop;

    @BeforeEach
    void setUp() {
        eventLoop = new DefaultEventLoop();
    }

    @AfterEach
    void tearDown() {
        if (eventLoop != null) {
            eventLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void shouldAllowBlockingOnNormalThread() {
        assertThat(EventLoopSafety.inEventLoop()).isFalse();
        assertThatCode(EventLoopSafety::requireBlockingAllowed).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBlockingOnEventLoopThread() throws Exception {
        CompletableFuture<Boolean> inLoopFuture = new CompletableFuture<>();
        CompletableFuture<Throwable> exceptionFuture = new CompletableFuture<>();

        eventLoop.execute(() -> {
            inLoopFuture.complete(EventLoopSafety.inEventLoop());
            try {
                EventLoopSafety.requireBlockingAllowed();
                exceptionFuture.complete(null);
            } catch (Throwable t) {
                exceptionFuture.complete(t);
            }
        });

        assertThat(inLoopFuture.get(5, TimeUnit.SECONDS)).isTrue();
        Throwable error = exceptionFuture.get(5, TimeUnit.SECONDS);
        assertThat(error)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Blocking control-plane operation must run off the Netty event loop");
    }

    @Test
    void shouldRejectBlockingOnFastThreadLocalThread() throws Exception {
        AtomicBoolean inLoop = new AtomicBoolean();
        AtomicReference<Throwable> exception = new AtomicReference<>();

        Thread thread = new FastThreadLocalThread(() -> {
            inLoop.set(EventLoopSafety.inEventLoop());
            try {
                EventLoopSafety.requireBlockingAllowed();
            } catch (Throwable t) {
                exception.set(t);
            }
        });
        thread.start();
        thread.join(5000);

        assertThat(inLoop.get()).isTrue();
        assertThat(exception.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Blocking control-plane operation must run off the Netty event loop");
    }
}
