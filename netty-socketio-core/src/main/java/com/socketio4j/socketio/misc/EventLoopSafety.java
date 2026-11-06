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

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.ThreadExecutorMap;

/**
 * Internal boundary for operations that must run off the Netty event loop thread.
 */
public final class EventLoopSafety {

    private EventLoopSafety() {
    }

    /**
     * Detects whether the current thread is running within a Netty event loop executor
     * or is a {@link FastThreadLocalThread}.
     *
     * @return {@code true} if running in a Netty event loop, {@code false} otherwise
     */
    public static boolean inEventLoop() {
        EventExecutor executor = ThreadExecutorMap.currentExecutor();
        return executor instanceof EventLoop
                || (executor == null
                    && Thread.currentThread() instanceof FastThreadLocalThread);
    }

    /**
     * Rejects before acquiring a monitor or performing a blocking operation if executed on the Netty event loop.
     *
     * @throws IllegalStateException if called on a Netty event loop thread
     */
    public static void requireBlockingAllowed() {
        if (inEventLoop()) {
            throw new IllegalStateException(
                    "Blocking control-plane operation must run off the Netty event loop");
        }
    }
}
