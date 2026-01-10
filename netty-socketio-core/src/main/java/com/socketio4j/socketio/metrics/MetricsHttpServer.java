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

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.concurrent.SucceededFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Metrics HTTP server exposing Prometheus metrics.
 *
 * <p>
 * Uses Netty 4.2+ new IO API (MultiThreadIoEventLoopGroup).
 * </p>
 */
public final class MetricsHttpServer {

    private static final Logger log =
            LoggerFactory.getLogger(MetricsHttpServer.class);

    /* ===================== State ===================== */

    private enum ServerStatus {
        INIT, STARTING, STARTED, STOPPING
    }

    private final AtomicReference<ServerStatus> status =
            new AtomicReference<>(ServerStatus.INIT);

    private final AtomicReference<Channel> serverChannelRef =
            new AtomicReference<>();

    private final AtomicBoolean shutdownHookInstalled =
            new AtomicBoolean(false);

    /* ===================== Config ===================== */

    private final PrometheusMeterRegistry registry;
    private final String host;
    private final int port;
    private final String metricsPath;
    private final int bossThreads;
    private final int workerThreads;
    private final boolean installShutdownHook;

    /* ===================== Runtime ===================== */

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Thread shutdownHook;

    /* ===================== Constructors ===================== */

    /**
     * @apiNote Added in API version {@code 4.0.0}
     * @param registry
     * @param host
     * @param port
     */
    public MetricsHttpServer(PrometheusMeterRegistry registry,
                             String host,
                             int port) {
        this(registry, host, port, "/metrics", 1, 0, true);
    }

    /**
     * @apiNote Added in API version {@code 4.0.0}
     * @param registry
     * @param host
     * @param port
     * @param metricsPath
     * @param bossThreads
     * @param workerThreads
     * @param installShutdownHook
     */
    public MetricsHttpServer(PrometheusMeterRegistry registry,
                             String host,
                             int port,
                             String metricsPath,
                             int bossThreads,
                             int workerThreads,
                             boolean installShutdownHook) {

        this.registry = Objects.requireNonNull(registry, "registry");
        this.host = Objects.requireNonNull(host, "host");
        this.metricsPath = Objects.requireNonNull(metricsPath, "metricsPath");

        this.port = port;
        this.bossThreads = bossThreads <= 0 ? 1 : bossThreads;
        this.workerThreads = workerThreads;
        this.installShutdownHook = installShutdownHook;
    }

    /* ===================== Accessors ===================== */

    public boolean isStarted() {
        return status.get() == ServerStatus.STARTED;
    }

    public int getBoundPort() {
        Channel ch = serverChannelRef.get();
        return ch != null
                ? ((InetSocketAddress) ch.localAddress()).getPort()
                : -1;
    }

    /* ===================== Lifecycle ===================== */

    public void start() {
        startAsync().syncUninterruptibly();
    }

    public Future<Void> startAsync() {

        if (status.get() == ServerStatus.STARTED) {
            return new SucceededFuture<>(ImmediateEventExecutor.INSTANCE, null);
        }

        if (!status.compareAndSet(ServerStatus.INIT, ServerStatus.STARTING)) {
            Promise<Void> p = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
            p.setFailure(new IllegalStateException(
                    "Invalid state " + status.get() + " for start"));
            return p;
        }

        try {
            EventLoopGroup tmpBoss = null;
            EventLoopGroup tmpWorker = null;

            try {
                tmpBoss = new MultiThreadIoEventLoopGroup(
                        bossThreads, NioIoHandler.newFactory());

                tmpWorker = workerThreads > 0
                        ? new MultiThreadIoEventLoopGroup(
                        workerThreads, NioIoHandler.newFactory())
                        : new MultiThreadIoEventLoopGroup(
                        NioIoHandler.newFactory());

                bossGroup = tmpBoss;
                workerGroup = tmpWorker;

            } catch (Exception e) {
                if (tmpBoss != null) tmpBoss.shutdownGracefully();
                if (tmpWorker != null) tmpWorker.shutdownGracefully();
                throw e;
            }

            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new io.netty.handler.codec.http.HttpServerCodec());
                            p.addLast(new io.netty.handler.codec.http.HttpObjectAggregator(64 * 1024));
                            p.addLast(new MetricsHandler(registry, metricsPath));
                        }
                    });

            Promise<Void> promise =
                    new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);

            ChannelFuture bindFuture = bootstrap.bind(host, port);
            bindFuture.addListener(f -> {

                if (status.get() == ServerStatus.STOPPING) {
                    if (f.isSuccess()) {
                        bindFuture.channel().close();
                    }
                    promise.setFailure(
                            new CancellationException("Server stopped during startup"));
                    return;
                }

                if (f.isSuccess()) {
                    serverChannelRef.set(bindFuture.channel());
                    status.set(ServerStatus.STARTED);

                    if (installShutdownHook) {
                        installShutdownHookOnce();
                    }

                    promise.setSuccess(null);
                } else {
                    shutdownEventLoops();
                    status.set(ServerStatus.INIT);
                    promise.setFailure(f.cause());
                }
            });

            return promise;

        } catch (Exception e) {
            shutdownEventLoops();
            status.set(ServerStatus.INIT);
            log.error("Metrics server startup error", e);
            throw e;
        }
    }

    public Future<Void> stopAsync() {

        ServerStatus s = status.get();
        if (s == ServerStatus.INIT) {
            return new SucceededFuture<>(ImmediateEventExecutor.INSTANCE, null);
        }

        if (!status.compareAndSet(ServerStatus.STARTED, ServerStatus.STOPPING)
                && !status.compareAndSet(ServerStatus.STARTING, ServerStatus.STOPPING)) {
            return new SucceededFuture<>(ImmediateEventExecutor.INSTANCE, null);
        }

        removeShutdownHook();

        Promise<Void> stopPromise =
                new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);

        Channel ch = serverChannelRef.getAndSet(null);
        if (ch != null) {
            ch.close();
        }

        PromiseCombiner combiner =
                new PromiseCombiner(ImmediateEventExecutor.INSTANCE);

        if (bossGroup != null) {
            combiner.add(bossGroup.shutdownGracefully());
        }
        if (workerGroup != null) {
            combiner.add(workerGroup.shutdownGracefully());
        }

        bossGroup = null;
        workerGroup = null;

        combiner.finish(stopPromise);

        stopPromise.addListener(f -> {
            status.set(ServerStatus.INIT);
            log.info("Metrics HTTP server stopped");
        });

        return stopPromise;
    }

    public void stop() {
        if (bossGroup != null && bossGroup.next().inEventLoop()) {
            log.warn("Blocking stop() called from I/O thread. Use stopAsync().");
        }
        stopAsync().syncUninterruptibly();
    }

    /* ===================== Helpers ===================== */

    private void shutdownEventLoops() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
    }

    private void installShutdownHookOnce() {
        if (shutdownHookInstalled.compareAndSet(false, true)) {
            shutdownHook = new Thread(() -> {
                try {
                    if (isStarted()) {
                        log.info("JVM shutdown detected — stopping metrics server");
                        stopAsync().syncUninterruptibly();
                    }
                } catch (Throwable t) {
                    log.warn("Error during metrics server shutdown", t);
                }
            }, "socketio4j-metrics-server-shutdown-hook");

            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    public void removeShutdownHook() {
        if (!shutdownHookInstalled.get() || shutdownHook == null) {
            return;
        }
        if (shutdownHookInstalled.compareAndSet(true, false)) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                log.debug("Shutdown hook already executing or removed");
            }
            shutdownHook = null;
        }
    }
}
