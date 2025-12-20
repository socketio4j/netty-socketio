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
package com.socketio4j.socketio.scheduler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

public class HashedWheelScheduler implements CancelableScheduler {

    private final Map<SchedulerKey, Timeout> scheduledFutures = new ConcurrentHashMap<>();
    private final HashedWheelTimer executorService;
    
    public HashedWheelScheduler() {
        executorService = new HashedWheelTimer();
    }
    
    public HashedWheelScheduler(ThreadFactory threadFactory) {
        executorService = new HashedWheelTimer(threadFactory);
    }

    private volatile ChannelHandlerContext ctx;

    @Override
    public void update(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void cancel(SchedulerKey key) {
        Timeout timeout = scheduledFutures.remove(key);
        if (timeout != null) {
            timeout.cancel();
        }
    }

    @Override
    public void schedule(final Runnable runnable, long delay, TimeUnit unit) {
        executorService.newTimeout(timeout -> runnable.run(), delay, unit);
    }

    @Override
    public void scheduleCallback(final SchedulerKey key, final Runnable runnable, long delay, TimeUnit unit) {
        Timeout timeout = executorService.newTimeout(timeout1 -> ctx.executor().execute(() -> {
            try {
                runnable.run();
            } finally {
                scheduledFutures.remove(key);
            }
        }), delay, unit);

        if (!timeout.isExpired()) {
            scheduledFutures.put(key, timeout);
        }
    }

    @Override
    public void schedule(final SchedulerKey key, final Runnable runnable, long delay, TimeUnit unit) {
        Timeout timeout = executorService.newTimeout(timeout1 -> {
            try {
                runnable.run();
            } finally {
                scheduledFutures.remove(key);
            }
        }, delay, unit);

        if (!timeout.isExpired()) {
            scheduledFutures.put(key, timeout);
        }
    }

    @Override
    public void shutdown() {
        executorService.stop();
    }

}
