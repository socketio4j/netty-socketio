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
package com.socketio4j.socketio;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.listener.CatchAllEventListener;
import com.socketio4j.socketio.listener.ClientListeners;
import com.socketio4j.socketio.listener.ConnectListener;
import com.socketio4j.socketio.listener.DataListener;
import com.socketio4j.socketio.listener.DisconnectListener;
import com.socketio4j.socketio.listener.EventInterceptor;
import com.socketio4j.socketio.listener.MultiTypeEventListener;
import com.socketio4j.socketio.listener.PingListener;
import com.socketio4j.socketio.listener.PongListener;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.namespace.NamespacesHub;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.SucceededFuture;

/**
 * Fully thread-safe.
 */
public class SocketIOServer implements ClientListeners {

    private static final Logger log = LoggerFactory.getLogger(SocketIOServer.class);

    private final List<ServerStartListener> startListeners = new CopyOnWriteArrayList<>();
    private final List<ServerStopListener> stopListeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<Channel> serverChannel = new AtomicReference<>();

    private final AtomicBoolean shutdownHookInstalled = new AtomicBoolean();
    private Thread shutdownHook;

    private final AtomicReference<ServerStatus> serverStatus = new AtomicReference<>(ServerStatus.INIT);

    /**
     * Registers a {@link ServerStartListener} that will be invoked when the server
     * has successfully started and is ready to accept connections.
     * <p>
     * Start listeners are executed:
     * <ul>
     * <li>After all transports, event loops, and internal resources are
     * initialized</li>
     * <li>Exactly once per successful server start</li>
     * <li>In a safe execution context with exception isolation</li>
     * </ul>
     * <p>
     * Implementations should keep listener logic lightweight and non-blocking.
     * Heavy or blocking work should be offloaded to a separate thread or executor.
     *
     * @param listener the listener to be notified on server start; must not be
     *                 {@code null}
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public void addStartListener(@NotNull ServerStartListener listener) {
        startListeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Registers a {@link ServerStopListener} that will be invoked when the server
     * is shutting down and all client connections are being closed.
     * <p>
     * Stop listeners are executed:
     * <ul>
     * <li>During an orderly shutdown or JVM termination</li>
     * <li>Exactly once per server stop invocation</li>
     * <li>After connection acceptance has stopped</li>
     * </ul>
     * <p>
     * Listener implementations should not assume that network resources
     * are still available and must tolerate partial shutdown states.
     *
     * @param listener the listener to be notified on server stop; must not be
     *                 {@code null}
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public void addStopListener(@NotNull ServerStopListener listener) {
        stopListeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Removes a previously registered {@link ServerStartListener}.
     * <p>
     * If the listener was registered multiple times, only a single instance
     * is removed per invocation.
     *
     * @param listener the listener to remove; must not be {@code null}
     * @return {@code true} if the listener was present and removed,
     *         {@code false} otherwise
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public boolean removeStartListener(@NotNull ServerStartListener listener) {
        return startListeners.remove(Objects.requireNonNull(listener));
    }

    /**
     * Removes a previously registered {@link ServerStopListener}.
     * <p>
     * Removing a listener after the server has already begun shutdown
     * does not affect listeners currently being executed.
     *
     * @param listener the listener to remove; must not be {@code null}
     * @return {@code true} if the listener was present and removed,
     *         {@code false} otherwise
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public boolean removeStopListener(@NotNull ServerStopListener listener) {
        return stopListeners.remove(Objects.requireNonNull(listener));
    }

    private void fireServerStarted() {
        startListeners.forEach(l -> {
            try {
                l.onStart(this);
            } catch (Exception t) {
                log.error("Exception during server start listener : {} ", t.getMessage(), t);
            }
        });
    }

    private void fireServerStopped() {
        stopListeners.forEach(l -> {
            try {
                l.onStop(this);
            } catch (Exception t) {
                log.error("Exception during server stop listener : {} ", t.getMessage(), t);
            }
        });
    }

    private static boolean isClassAvailable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean isIoUringAvailable() {
        if (!isClassAvailable("io.netty.channel.uring.IoUring")) {
            return false;
        }
        try {
            Class<?> clazz = Class.forName("io.netty.channel.uring.IoUring");
            return (Boolean) clazz.getMethod("isAvailable").invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isEpollAvailable() {
        if (!isClassAvailable("io.netty.channel.epoll.Epoll")) {
            return false;
        }
        try {
            Class<?> clazz = Class.forName("io.netty.channel.epoll.Epoll");
            return (Boolean) clazz.getMethod("isAvailable").invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isKQueueAvailable() {
        if (!isClassAvailable("io.netty.channel.kqueue.KQueue")) {
            return false;
        }
        try {
            Class<?> clazz = Class.forName("io.netty.channel.kqueue.KQueue");
            return (Boolean) clazz.getMethod("isAvailable").invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends ServerChannel> getIoUringServerSocketChannelClass() {
        try {
            return (Class<? extends ServerChannel>) Class.forName("io.netty.channel.uring.IoUringServerSocketChannel");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends ServerChannel> getEpollServerSocketChannelClass() {
        try {
            return (Class<? extends ServerChannel>) Class.forName("io.netty.channel.epoll.EpollServerSocketChannel");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends ServerChannel> getKQueueServerSocketChannelClass() {
        try {
            return (Class<? extends ServerChannel>) Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static IoHandlerFactory getIoUringIoHandlerFactory() {
        try {
            Class<?> clazz = Class.forName("io.netty.channel.uring.IoUringIoHandler");
            return (IoHandlerFactory) clazz.getMethod("newFactory").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static IoHandlerFactory getEpollIoHandlerFactory() {
        try {
            Class<?> clazz = Class.forName("io.netty.channel.epoll.EpollIoHandler");
            return (IoHandlerFactory) clazz.getMethod("newFactory").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static IoHandlerFactory getKQueueIoHandlerFactory() {
        try {
            Class<?> clazz = Class.forName("io.netty.channel.kqueue.KQueueIoHandler");
            return (IoHandlerFactory) clazz.getMethod("newFactory").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    private final Configuration configCopy;
    private final Configuration configuration;

    private final NamespacesHub namespacesHub;
    private final SocketIONamespace mainNamespace;

    private SocketIOChannelInitializer pipelineFactory = new SocketIOChannelInitializer();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public SocketIOServer(Configuration configuration) {
        this.configuration = configuration;
        this.configCopy = new Configuration(configuration);
        namespacesHub = new NamespacesHub(configCopy);
        mainNamespace = addNamespace(Namespace.DEFAULT_NAME);
    }

    public void setPipelineFactory(SocketIOChannelInitializer pipelineFactory) {
        this.pipelineFactory = pipelineFactory;
    }

    public Collection<SocketIOClient> getAllClients() {
        return namespacesHub.get(Namespace.DEFAULT_NAME).getAllClients();
    }

    public SocketIOClient getClient(UUID uuid) {
        return namespacesHub.get(Namespace.DEFAULT_NAME).getClient(uuid);
    }

    public Collection<SocketIONamespace> getAllNamespaces() {
        return namespacesHub.getAllNamespaces();
    }

    public BroadcastOperations getBroadcastOperations() {
        Collection<SocketIONamespace> namespaces = namespacesHub.getAllNamespaces();
        List<BroadcastOperations> list = new ArrayList<>();
        if (namespaces != null) {
            for (SocketIONamespace n : namespaces) {
                list.add(n.getBroadcastOperations());
            }
        }
        return new MultiRoomBroadcastOperations(list);
    }

    public BroadcastOperations getRoomOperations(String... rooms) {
        Collection<SocketIONamespace> namespaces = namespacesHub.getAllNamespaces();
        List<BroadcastOperations> list = new ArrayList<>();
        if (namespaces != null) {
            for (SocketIONamespace n : namespaces) {
                for (String room : rooms) {
                    list.add(n.getRoomOperations(room));
                }
            }
        }
        return new MultiRoomBroadcastOperations(list);
    }

    public void start() {
        startAsync().syncUninterruptibly();
    }

    public boolean isStarted() {
        return serverStatus.get() == ServerStatus.STARTED;
    }

    /**
     * Starts the server asynchronously.
     * <p>
     * This method initiates the full server startup sequence, including:
     * <ul>
     * <li>Validating and transitioning the server lifecycle state</li>
     * <li>Initializing event loop groups and internal pipelines</li>
     * <li>Selecting the most appropriate transport (IO_URING, EPOLL, KQUEUE, or
     * NIO)</li>
     * <li>Binding the server to the configured address</li>
     * </ul>
     *
     * <h3>Lifecycle semantics</h3>
     * <ul>
     * <li>This method may be invoked only when the server is in
     * {@link ServerStatus#INIT}</li>
     * <li>If the server is already starting or running, the call is ignored</li>
     * <li>On success, the server transitions to {@link ServerStatus#STARTED}</li>
     * <li>On failure, the server state is reset back to
     * {@link ServerStatus#INIT}</li>
     * </ul>
     *
     * <h3>Asynchronous behavior</h3>
     * The server startup is non-blocking. The returned {@link Future} completes
     * when the
     * underlying Netty {@link ServerChannel} has been successfully bound or when
     * the bind
     * operation fails.
     * <p>
     * Callers may attach listeners to the returned future to be notified of startup
     * completion.
     *
     * <h3>Transport selection</h3>
     * The transport implementation is chosen based on the configured transport type
     * and
     * platform capabilities:
     * <ul>
     * <li>{@code IO_URING} – preferred when available on supported Linux
     * kernels</li>
     * <li>{@code EPOLL} – used on Linux when io_uring is unavailable</li>
     * <li>{@code KQUEUE} – used on macOS and BSD systems</li>
     * <li>{@code AUTO} – selects the best available transport in the above
     * order</li>
     * <li>{@code NIO} – used as a universal fallback</li>
     * </ul>
     * If a requested native transport is unavailable, the server logs a warning and
     * transparently falls back to NIO.
     *
     * <h3>Threading model</h3>
     * The startup process executes on the calling thread until the bind operation
     * is submitted.
     * The completion of the returned {@link Future} occurs on the Netty event loop
     * thread
     * associated with the bind operation.
     *
     * <h3>Failure handling</h3>
     * <ul>
     * <li>If startup fails asynchronously (e.g., bind error), the returned future
     * completes
     * with failure and the server state is reverted</li>
     * <li>If an exception occurs synchronously during initialization, it is
     * propagated to
     * the caller and the server state is reverted</li>
     * </ul>
     *
     * <h3>Post-start actions</h3>
     * On successful startup, the following actions are performed:
     * <ul>
     * <li>The bound server channel is recorded</li>
     * <li>A JVM shutdown hook is installed (once)</li>
     * <li>Registered {@link ServerStartListener}s are notified</li>
     * </ul>
     *
     * @return a {@link Future} that completes when the server bind operation
     *         succeeds or fails;
     *         if the server was already started or starting, a successfully
     *         completed future
     *         is returned and the request is ignored
     *
     * @throws RuntimeException if a synchronous error occurs during startup
     *                          initialization
     */
    public Future<Void> startAsync() {
        if (!serverStatus.compareAndSet(ServerStatus.INIT, ServerStatus.STARTING)) {
            log.warn("Invalid server state: {}, should be: {}, ignoring start request",
                    serverStatus.get(), ServerStatus.INIT);
            return new SucceededFuture<>(new DefaultEventLoop(), null);
        }

        try {
            log.info("Session store / event store factory: {}", configCopy.getStoreFactory());
            initGroups();
            pipelineFactory.start(configCopy, namespacesHub);

            Class<? extends ServerChannel> channelClass = NioServerSocketChannel.class;

            switch (configCopy.getTransportType()) {
                case IO_URING:
                    if (isIoUringAvailable()) {
                        Class<? extends ServerChannel> clazz = getIoUringServerSocketChannelClass();
                        if (clazz != null) {
                            channelClass = clazz;
                        } else {
                            log.warn("IO_URING transport requested but not available, falling back to NIO");
                        }
                    } else {
                        log.warn("IO_URING transport requested but not available, falling back to NIO");
                    }
                    break;
                case EPOLL:
                    if (isEpollAvailable()) {
                        Class<? extends ServerChannel> clazz = getEpollServerSocketChannelClass();
                        if (clazz != null) {
                            channelClass = clazz;
                        } else {
                            log.warn("EPOLL transport requested but not available, falling back to NIO");
                        }
                    } else {
                        log.warn("EPOLL transport requested but not available, falling back to NIO");
                    }
                    break;
                case KQUEUE:
                    if (isKQueueAvailable()) {
                        Class<? extends ServerChannel> clazz = getKQueueServerSocketChannelClass();
                        if (clazz != null) {
                            channelClass = clazz;
                        } else {
                            log.warn("KQUEUE transport requested but not available, falling back to NIO");
                        }
                    } else {
                        log.warn("KQUEUE transport requested but not available, falling back to NIO");
                    }
                    break;
                case AUTO:
                    if (isIoUringAvailable()) {
                        Class<? extends ServerChannel> clazz = getIoUringServerSocketChannelClass();
                        if (clazz != null) {
                            channelClass = clazz;
                            log.info("AUTO selected IO_URING transport");
                        } else {
                            log.info("AUTO selected NIO transport");
                        }
                    } else if (isEpollAvailable()) {
                        Class<? extends ServerChannel> clazz = getEpollServerSocketChannelClass();
                        if (clazz != null) {
                            channelClass = clazz;
                            log.info("AUTO selected EPOLL transport");
                        } else {
                            log.info("AUTO selected NIO transport");
                        }
                    } else if (isKQueueAvailable()) {
                        Class<? extends ServerChannel> clazz = getKQueueServerSocketChannelClass();
                        if (clazz != null) {
                            channelClass = clazz;
                            log.info("AUTO selected KQUEUE transport");
                        } else {
                            log.info("AUTO selected NIO transport");
                        }
                    } else {
                        log.info("AUTO selected NIO transport");
                    }
                    break;
                default:
                    log.info("NIO transport as default transport");
            }

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(channelClass)
                    .childHandler(pipelineFactory);

            applyConnectionOptions(bootstrap);

            InetSocketAddress address;
            if (configCopy.getHostname() == null) {
                address = new InetSocketAddress(configCopy.getPort());
            } else {
                address = new InetSocketAddress(configCopy.getHostname(), configCopy.getPort());
            }

            return bootstrap.bind(address).addListener((FutureListener<Void>) future -> {
                if (future.isSuccess()) {
                    ChannelFuture cf = (ChannelFuture) future;
                    serverChannel.set(cf.channel());
                    serverStatus.set(ServerStatus.STARTED);
                    log.info("SocketIO server started on port {}", configCopy.getPort());
                    installShutdownHookOnce();
                    fireServerStarted();
                } else {
                    serverStatus.set(ServerStatus.INIT);
                    log.error("Failed to start server on port {}", configCopy.getPort());
                }
            });

        } catch (Exception e) {
            serverStatus.set(ServerStatus.INIT);
            log.error("Server start error on port {}: {}", configCopy.getPort(), e.getMessage(), e);
            throw e;
        }
    }

    protected void applyConnectionOptions(ServerBootstrap bootstrap) {
        SocketConfig config = configCopy.getSocketConfig();

        bootstrap.childOption(ChannelOption.TCP_NODELAY, config.isTcpNoDelay());

        if (config.getTcpSendBufferSize() != -1) {
            bootstrap.childOption(ChannelOption.SO_SNDBUF, config.getTcpSendBufferSize());
        }
        if (config.getTcpReceiveBufferSize() != -1) {
            bootstrap.childOption(ChannelOption.SO_RCVBUF, config.getTcpReceiveBufferSize());
            bootstrap.childOption(ChannelOption.RECVBUF_ALLOCATOR,
                    new FixedRecvByteBufAllocator(config.getTcpReceiveBufferSize()));
        }
        // default value @see WriteBufferWaterMark.DEFAULT
        if (config.getWriteBufferWaterMarkLow() != -1 && config.getWriteBufferWaterMarkHigh() != -1) {
            bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                    config.getWriteBufferWaterMarkLow(), config.getWriteBufferWaterMarkHigh()));
        }

        bootstrap.childOption(ChannelOption.SO_KEEPALIVE, config.isTcpKeepAlive());
        bootstrap.childOption(ChannelOption.SO_LINGER, config.getSoLinger());

        bootstrap.option(ChannelOption.SO_REUSEADDR, config.isReuseAddress());
        bootstrap.option(ChannelOption.SO_BACKLOG, config.getAcceptBackLog());
    }

    protected void initGroups() {

        IoHandlerFactory handler = NioIoHandler.newFactory();

        switch (configCopy.getTransportType()) {
            case IO_URING:
                if (isIoUringAvailable()) {
                    IoHandlerFactory factory = getIoUringIoHandlerFactory();
                    if (factory != null) {
                        handler = factory;
                    } else {
                        log.warn("IO_URING IoHandler requested but not available, falling back to NIO");
                    }
                } else {
                    log.warn("IO_URING IoHandler requested but not available, falling back to NIO");
                }
                break;
            case EPOLL:
                if (isEpollAvailable()) {
                    IoHandlerFactory factory = getEpollIoHandlerFactory();
                    if (factory != null) {
                        handler = factory;
                    } else {
                        log.warn("EPOLL IoHandler requested but not available, falling back to NIO");
                    }
                } else {
                    log.warn("EPOLL IoHandler requested but not available, falling back to NIO");
                }
                break;
            case KQUEUE:
                if (isKQueueAvailable()) {
                    IoHandlerFactory factory = getKQueueIoHandlerFactory();
                    if (factory != null) {
                        handler = factory;
                    } else {
                        log.warn("KQUEUE IoHandler requested but not available, falling back to NIO");
                    }
                } else {
                    log.warn("KQUEUE IoHandler requested but not available, falling back to NIO");
                }
                break;
            case AUTO:
                if (isIoUringAvailable()) {
                    IoHandlerFactory factory = getIoUringIoHandlerFactory();
                    if (factory != null) {
                        handler = factory;
                    } else {
                        handler = NioIoHandler.newFactory();
                    }
                } else if (isEpollAvailable()) {
                    IoHandlerFactory factory = getEpollIoHandlerFactory();
                    if (factory != null) {
                        handler = factory;
                    } else {
                        handler = NioIoHandler.newFactory();
                    }
                } else if (isKQueueAvailable()) {
                    IoHandlerFactory factory = getKQueueIoHandlerFactory();
                    if (factory != null) {
                        handler = factory;
                    } else {
                        handler = NioIoHandler.newFactory();
                    }
                } else {
                    handler = NioIoHandler.newFactory();
                }
                log.info(" AUTO selected transportType {}", handler);
                break;
            default:
                handler = NioIoHandler.newFactory();
                log.info("default transportType {} is selected", handler);
                break;
        }

        bossGroup = new MultiThreadIoEventLoopGroup(configCopy.getBossThreads(), handler);
        workerGroup = new MultiThreadIoEventLoopGroup(configCopy.getWorkerThreads(), handler);
    }

    /**
     * Stops the server synchronously and releases all associated resources.
     * <p>
     * This method initiates an orderly shutdown sequence and blocks the calling
     * thread until all network resources and event loop groups have been
     * terminated.
     *
     * <h3>Lifecycle semantics</h3>
     * <ul>
     * <li>This method is effective only when the server is in
     * {@link ServerStatus#STARTED}</li>
     * <li>If the server is not started, the stop request is ignored</li>
     * <li>The server transitions through {@link ServerStatus#STOPPING} and finally
     * returns to {@link ServerStatus#INIT}</li>
     * </ul>
     *
     * <h3>Shutdown sequence</h3>
     * The shutdown process is performed in the following order:
     * <ol>
     * <li>Transition server state to {@code STOPPING}</li>
     * <li>Remove the JVM shutdown hook, if installed</li>
     * <li>Close the bound server channel and stop accepting new connections</li>
     * <li>Notify registered {@link ServerStopListener}s</li>
     * <li>Stop the internal channel pipeline</li>
     * <li>Gracefully shut down boss and worker event loop groups</li>
     * </ol>
     *
     * <h3>Threading behavior</h3>
     * This method blocks the calling thread until:
     * <ul>
     * <li>The server channel is closed</li>
     * <li>All event loop threads have terminated</li>
     * </ul>
     * It is therefore recommended to invoke this method from a non-event-loop
     * thread.
     *
     * <h3>Failure isolation</h3>
     * <ul>
     * <li>Exceptions thrown by stop listeners or pipeline shutdown logic are caught
     * and logged</li>
     * <li>Failures in one shutdown phase do not prevent subsequent cleanup
     * steps</li>
     * </ul>
     *
     * <h3>Idempotency</h3>
     * Calling this method multiple times or calling it after the server has already
     * stopped has no effect beyond logging a warning.
     *
     * <h3>Post-conditions</h3>
     * After this method returns:
     * <ul>
     * <li>No new client connections are accepted</li>
     * <li>All network resources have been released</li>
     * <li>The server may be started again by invoking {@link #startAsync()}</li>
     * </ul>
     */
    public void stop() {
        if (!serverStatus.compareAndSet(ServerStatus.STARTED, ServerStatus.STOPPING)) {
            log.warn("Server not in STARTED state ({}), ignoring stop()", serverStatus.get());
            return;
        }

        log.info("Stopping SocketIO server...");

        // Remove shutdown hook if installed, atomically
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

        Channel ch = serverChannel.getAndSet(null);
        if (ch != null && ch.isOpen()) {
            ch.close().syncUninterruptibly();
        }

        try {
            fireServerStopped();
        } catch (Exception t) {
            log.warn("Stop listeners failed", t);
        }

        try {
            pipelineFactory.stop();
        } catch (Exception t) {
            log.warn("Pipeline stop failed", t);
        }

        try {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            log.info("SocketIO server stopped");
        } finally {
            serverStatus.set(ServerStatus.INIT);
        }
    }


    private void installShutdownHookOnce() {
        if (shutdownHookInstalled.compareAndSet(false, true)) {
            shutdownHook = new Thread(() -> {
                if (isStarted()) {
                    log.info("JVM shutdown detected — stopping server...");
                    stop();
                }
            }, "socketio4j-shutdown-hook-thread");
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

        try {
            boolean removed = Runtime.getRuntime().removeShutdownHook(shutdownHook);
            if (removed) {
                shutdownHookInstalled.set(false);
                log.debug("Shutdown hook removed");
            }
        } catch (IllegalStateException e) {
            // JVM is already shutting down
            log.warn("Shutdown in progress, cannot remove shutdown hook");
        }
    }

    /**
     * Creates and registers a new {@link SocketIONamespace} with the given name.
     * <p>
     * Namespaces provide logical isolation for events, rooms, and listeners.
     * If a namespace with the given name already exists, the existing instance
     * may be returned depending on the underlying implementation.
     *
     * @param name the namespace name (e.g. {@code "/chat"}); must not be
     *             {@code null}
     * @return the created or existing {@link SocketIONamespace}
     */
    public SocketIONamespace addNamespace(String name) {
        return namespacesHub.create(name);
    }

    /**
     * Returns the {@link SocketIONamespace} associated with the given name.
     *
     * @param name the namespace name
     * @return the namespace instance, or {@code null} if no such namespace exists
     */
    public SocketIONamespace getNamespace(String name) {
        return namespacesHub.get(name);
    }

    /**
     * Removes the namespace with the given name.
     * <p>
     * Once removed, all listeners and rooms associated with the namespace
     * are discarded and the namespace becomes inaccessible.
     *
     * @param name the namespace name to remove
     */
    public void removeNamespace(String name) {
        namespacesHub.remove(name);
    }

    /**
     * Returns the server configuration used to initialize this instance.
     * <p>
     * The returned configuration represents the effective runtime configuration
     * and should be treated as read-only.
     *
     * @return the server {@link Configuration}
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * Registers an event listener capable of handling multiple payload types
     * for the specified event.
     * <p>
     * This listener is registered on the main (default) namespace.
     *
     * @param eventName  the event name
     * @param listener   the multi-type event listener
     * @param eventClass one or more supported payload classes
     */
    @Override
    public void addMultiTypeEventListener(String eventName,
            MultiTypeEventListener listener,
            Class<?>... eventClass) {
        mainNamespace.addMultiTypeEventListener(eventName, listener, eventClass);
    }

    /**
     * Registers a typed event listener for the specified event name.
     * <p>
     * Incoming event payloads are deserialized into the provided event class
     * before being delivered to the listener.
     *
     * @param <T>        the event payload type
     * @param eventName  the event name
     * @param eventClass the expected payload class
     * @param listener   the listener to invoke when the event is received
     */
    @Override
    public <T> void addEventListener(String eventName,
            Class<T> eventClass,
            DataListener<T> listener) {
        mainNamespace.addEventListener(eventName, eventClass, listener);
    }

    /**
     * Registers an {@link EventInterceptor} for the main namespace.
     * <p>
     * Interceptors are invoked before event listeners and may be used for
     * validation, logging, or access control.
     *
     * @param eventInterceptor the interceptor to register
     */
    @Override
    public void addEventInterceptor(EventInterceptor eventInterceptor) {
        mainNamespace.addEventInterceptor(eventInterceptor);
    }

    /**
     * Removes all listeners associated with the specified event name
     * from the main namespace.
     *
     * @param eventName the event name whose listeners should be removed
     */
    @Override
    public void removeAllListeners(String eventName) {
        mainNamespace.removeAllListeners(eventName);
    }

    /**
     * Registers a catch-all event listener that receives all incoming events
     * on the main namespace, regardless of event name.
     *
     * @param listener the catch-all event listener
     */
    @Override
    public void addOnAnyEventListener(CatchAllEventListener listener) {
        mainNamespace.addOnAnyEventListener(listener);
    }

    /**
     * Removes a previously registered catch-all event listener
     * from the main namespace.
     *
     * @param listener the listener to remove
     */
    @Override
    public void removeOnAnyEventListener(CatchAllEventListener listener) {
        mainNamespace.removeOnAnyEventListener(listener);
    }

    /**
     * Alias for {@link #addOnAnyEventListener(CatchAllEventListener)}.
     *
     * @param listener the catch-all event listener
     */
    @Override
    public void onAny(CatchAllEventListener listener) {
        addOnAnyEventListener(listener);
    }

    /**
     * Alias for {@link #removeOnAnyEventListener(CatchAllEventListener)}.
     *
     * @param listener the catch-all event listener to remove
     */
    @Override
    public void offAny(CatchAllEventListener listener) {
        removeOnAnyEventListener(listener);
    }

    /**
     * Registers a listener that is notified when a client disconnects
     * from the main namespace.
     *
     * @param listener the disconnect listener
     */
    @Override
    public void addDisconnectListener(DisconnectListener listener) {
        mainNamespace.addDisconnectListener(listener);
    }

    /**
     * Registers a listener that is notified when a client successfully connects
     * to the main namespace.
     *
     * @param listener the connect listener
     */
    @Override
    public void addConnectListener(ConnectListener listener) {
        mainNamespace.addConnectListener(listener);
    }

    /**
     * Registers a listener that is notified when a ping frame
     * is received from a client.
     *
     * @param listener the ping listener
     */
    @Override
    public void addPingListener(PingListener listener) {
        mainNamespace.addPingListener(listener);
    }

    /**
     * Registers a listener that is notified when a pong frame
     * is received from a client.
     *
     * @param listener the pong listener
     */
    @Override
    public void addPongListener(PongListener listener) {
        mainNamespace.addPongListener(listener);
    }

    /**
     * Registers all compatible listener methods declared on the given object
     * with the main namespace.
     * <p>
     * Listener methods are discovered using reflection based on supported
     * annotations or method signatures.
     *
     * @param listeners the listener object containing handler methods
     */
    @Override
    public void addListeners(Object listeners) {
        mainNamespace.addListeners(listeners);
    }

    /**
     * Registers multiple listener objects with the main namespace.
     *
     * @param <L>       the listener type
     * @param listeners an iterable of listener instances
     */
    @Override
    public <L> void addListeners(Iterable<L> listeners) {
        mainNamespace.addListeners(listeners);
    }

    /**
     * Registers listener methods declared on the given object, restricted
     * to the specified class.
     * <p>
     * This variant is useful when the listener object implements multiple
     * interfaces or contains handlers for different namespaces.
     *
     * @param listeners the listener object
     * @param clazz     the class used to filter listener methods
     */
    @Override
    public void addListeners(Object listeners, Class<?> clazz) {
        mainNamespace.addListeners(listeners, clazz);
    }

}
