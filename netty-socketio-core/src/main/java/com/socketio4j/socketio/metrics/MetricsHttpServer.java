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
package com.socketio4j.socketio.metrics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.SucceededFuture;

public final class MetricsHttpServer {

    private static final Logger log =
            LoggerFactory.getLogger(MetricsHttpServer.class);

    private enum ServerStatus {
        INIT, STARTING, STARTED, STOPPING
    }

    private final AtomicReference<ServerStatus> status =
            new AtomicReference<>(ServerStatus.INIT);
    private final AtomicBoolean shutdownHookInstalled = new AtomicBoolean();
    private final PrometheusMeterRegistry registry;
    private final String host;
    private final int port;
    private final String metricsUrl;
    private Thread shutdownHook;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public MetricsHttpServer(PrometheusMeterRegistry registry,
                             String host,
                             int port,
                             String metricsUrl) {
        this.registry = registry;
        this.host = host;
        this.port = port;
        this.metricsUrl = metricsUrl;
    }

    public MetricsHttpServer(PrometheusMeterRegistry registry,
                             String host,
                             int port) {
        this(registry, host, port, "/metrics");
    }

    /* ===================== Lifecycle ===================== */

    public void start() {
        startAsync().syncUninterruptibly();
    }

    public boolean isStarted() {
        return status.get() == ServerStatus.STARTED;
    }

    public Future<Void> startAsync() {

        if (!status.compareAndSet(ServerStatus.INIT, ServerStatus.STARTING)) {
            log.warn("Invalid state {}, start() ignored", status.get());
            return new SucceededFuture<>(ImmediateEventExecutor.INSTANCE, null);
        }

        try {
            bossGroup =
                    new MultiThreadIoEventLoopGroup(
                            1, NioIoHandler.newFactory());

            workerGroup =
                    new MultiThreadIoEventLoopGroup(
                            NioIoHandler.newFactory());

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new io.netty.handler.codec.http.HttpServerCodec());
                            p.addLast(new io.netty.handler.codec.http.HttpObjectAggregator(64 * 1024));
                            p.addLast(new MetricsHandler(registry, metricsUrl));
                        }
                    });


            return bootstrap.bind(host, port)
                    .addListener((FutureListener<Void>) future -> {
                        if (future.isSuccess()) {
                            status.set(ServerStatus.STARTED);
                            installShutdownHookOnce();
                            log.info("Metrics HTTP server started at {}:{}{}",
                                    host, port, metricsUrl);
                        } else {
                            status.set(ServerStatus.INIT);
                            log.error("Failed to start metrics server", future.cause());
                        }
                    });

        } catch (Exception e) {
            status.set(ServerStatus.INIT);
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            log.error("Metrics server startup error", e);
            throw e;
        }
    }

    public void stop() {

        if (!status.compareAndSet(ServerStatus.STARTED, ServerStatus.STOPPING)) {
            log.warn("Invalid state {}, stop() ignored", status.get());
            return;
        }
        removeShutdownHook();
        try {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().syncUninterruptibly();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
            log.info("Metrics HTTP server stopped");
        } finally {
            status.set(ServerStatus.INIT);
        }
    }

    private void installShutdownHookOnce() {
        if (shutdownHookInstalled.compareAndSet(false, true)) {
            shutdownHook = new Thread(() -> {
                if (isStarted()) {
                    log.info("JVM shutdown detected — stopping server...");
                    stop();
                }
            }, "socketio4j-metrics-server-shutdown-hook-thread");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }
    /**
     * Removes the JVM shutdown hook previously installed by this server.
     * <p>
     * This method allows external lifecycle managers (e.g. application frameworks,
     * containers, or test harnesses) to take full control over server shutdown.
     * <p>
     * If the JVM shutdown sequence has already started, the hook cannot be removed
     * and the request is ignored.
     */
    public void removeShutdownHook() {
        if (!shutdownHookInstalled.get() || shutdownHook == null) {
            return;
        }
        if (shutdownHookInstalled.compareAndSet(true, false)) {
            Thread hook = shutdownHook;
            if (hook != null && Thread.currentThread() != hook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // JVM is already shutting down
                    log.debug("Shutdown hook already triggered");
                } catch (IllegalArgumentException e) {
                    // Hook was already removed
                    log.debug("Shutdown hook already removed");
                }
            }
        }
    }

}
