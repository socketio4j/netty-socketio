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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    private final AtomicReference<ServerStatus> serverStatus = new AtomicReference<>(ServerStatus.INIT);

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

    public Future<Void> startAsync() {
        if (!serverStatus.compareAndSet(ServerStatus.INIT, ServerStatus.STARTING)) {
            log.warn("Invalid server state: {}, should be: {}, ignoring start request",
                    serverStatus.get(), ServerStatus.INIT);
            return new SucceededFuture<>(new DefaultEventLoop(), null);
        }

        try {
            log.info("Session store / pubsub factory: {}", configCopy.getStoreFactory());
            initGroups();
            pipelineFactory.start(configCopy, namespacesHub);

            Class<? extends ServerChannel> channelClass;

            switch (configCopy.getTransportType()) {

                case IO_URING:
                    if (ioUringAvailable()) {
                        channelClass = loadChannelClass("io.netty.channel.uring.IoUringServerSocketChannel");
                    } else {
                        channelClass = fallback("IO_URING unavailable");
                    }
                    break;

                case EPOLL:
                    if (epollAvailable()) {
                        channelClass = loadChannelClass("io.netty.channel.epoll.EpollServerSocketChannel");
                    } else {
                        channelClass = fallback("EPOLL unavailable");
                    }
                    break;

                case KQUEUE:
                    if (kqueueAvailable()) {
                        channelClass = loadChannelClass("io.netty.channel.kqueue.KQueueServerSocketChannel");
                    } else {
                        channelClass = fallback("KQUEUE unavailable");
                    }
                    break;

                case NIO:
                    channelClass = NioServerSocketChannel.class;
                    break;

                case AUTO:
                default:
                    if (ioUringAvailable()) {
                        channelClass = loadChannelClass("io.netty.channel.uring.IoUringServerSocketChannel");
                        log.info("AUTO selected IO_URING transport");
                    } else if (epollAvailable()) {
                        channelClass = loadChannelClass("io.netty.channel.epoll.EpollServerSocketChannel");
                        log.info("AUTO selected EPOLL transport");
                    } else if (kqueueAvailable()) {
                        channelClass = loadChannelClass("io.netty.channel.kqueue.KQueueServerSocketChannel");
                        log.info("AUTO selected KQUEUE transport");
                    } else {
                        channelClass = NioServerSocketChannel.class;
                        log.info("AUTO selected NIO transport");
                    }
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
                    serverStatus.set(ServerStatus.STARTED);
                    log.info("SocketIO server started on port {}", configCopy.getPort());
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

    private Class<? extends ServerChannel> loadChannelClass(String name) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends ServerChannel> c = (Class<? extends ServerChannel>) Class.forName(name);
            return c;
        } catch (Exception ignored) {
            log.warn("Unable to load native channel {}. Using NIO.", name);
            return NioServerSocketChannel.class;
        }
    }

    private Class<? extends ServerChannel> fallback(String msg) {
        log.warn("{} → Falling back to NIO.", msg);
        return NioServerSocketChannel.class;
    }

    private IoHandlerFactory fallbackFactory(String msg) {
        log.warn("{} → Falling back to NIO IoHandler.", msg);
        return NioIoHandler.newFactory();
    }

    protected void applyConnectionOptions(ServerBootstrap bootstrap) {
        SocketConfig config = configCopy.getSocketConfig();

        bootstrap.childOption(ChannelOption.TCP_NODELAY, config.isTcpNoDelay());

        if (config.getTcpSendBufferSize() != -1) {
            bootstrap.childOption(ChannelOption.SO_SNDBUF, config.getTcpSendBufferSize());
        }
        if (config.getTcpReceiveBufferSize() != -1) {
            bootstrap.childOption(ChannelOption.SO_RCVBUF, config.getTcpReceiveBufferSize());
            bootstrap.childOption(ChannelOption.RECVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(config.getTcpReceiveBufferSize()));
        }
        //default value @see WriteBufferWaterMark.DEFAULT
        if (config.getWriteBufferWaterMarkLow() != -1 && config.getWriteBufferWaterMarkHigh() != -1) {
            bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                    config.getWriteBufferWaterMarkLow(), config.getWriteBufferWaterMarkHigh()
            ));
        }

        bootstrap.childOption(ChannelOption.SO_KEEPALIVE, config.isTcpKeepAlive());
        bootstrap.childOption(ChannelOption.SO_LINGER, config.getSoLinger());

        bootstrap.option(ChannelOption.SO_REUSEADDR, config.isReuseAddress());
        bootstrap.option(ChannelOption.SO_BACKLOG, config.getAcceptBackLog());
    }

    protected void initGroups() {

        IoHandlerFactory handler;

        switch (configCopy.getTransportType()) {

            case IO_URING:
                handler = createHandler("io.netty.channel.uring.IoUringIoHandler", ioUringAvailable());
                break;

            case EPOLL:
                handler = createHandler("io.netty.channel.epoll.EpollIoHandler", epollAvailable());
                break;

            case KQUEUE:
                handler = createHandler("io.netty.channel.kqueue.KQueueIoHandler", kqueueAvailable());
                break;

            case NIO:
                handler = NioIoHandler.newFactory();
                break;

            case AUTO:
            default:
                if (ioUringAvailable()) {
                    handler = createHandler("io.netty.channel.uring.IoUringIoHandler", true);
                } else if (epollAvailable()) {
                    handler = createHandler("io.netty.channel.epoll.EpollIoHandler", true);
                } else if (kqueueAvailable()) {
                    handler = createHandler("io.netty.channel.kqueue.KQueueIoHandler", true);
                } else {
                    handler = fallbackFactory("No native transport available.");
                }
        }

        bossGroup = new MultiThreadIoEventLoopGroup(configCopy.getBossThreads(), handler);
        workerGroup = new MultiThreadIoEventLoopGroup(configCopy.getWorkerThreads(), handler);
    }

    private IoHandlerFactory createHandler(String className, boolean available) {
        if (!available) {
            return fallbackFactory(className + " unavailable");
        }
        try {
            return (IoHandlerFactory) Class.forName(className)
                    .getMethod("newFactory")
                    .invoke(null);
        } catch (Exception ignored) {
            return fallbackFactory("Failed to load " + className);
        }
    }

    public void stop() {
        if (!serverStatus.compareAndSet(ServerStatus.STARTED, ServerStatus.STOPPING)) {
            log.warn("Invalid server state {}, ignoring stop()", serverStatus.get());
            return;
        }
        try {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            pipelineFactory.stop();
            log.info("SocketIO server stopped");
        } finally {
            serverStatus.set(ServerStatus.INIT);
        }
    }

    public SocketIONamespace addNamespace(String name) {
        return namespacesHub.create(name);
    }

    public SocketIONamespace getNamespace(String name) {
        return namespacesHub.get(name);
    }

    public void removeNamespace(String name) {
        namespacesHub.remove(name);
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void addMultiTypeEventListener(String eventName, MultiTypeEventListener listener, Class<?>... eventClass) {
        mainNamespace.addMultiTypeEventListener(eventName, listener, eventClass);
    }

    @Override
    public <T> void addEventListener(String eventName, Class<T> eventClass, DataListener<T> listener) {
        mainNamespace.addEventListener(eventName, eventClass, listener);
    }

    @Override
    public void addEventInterceptor(EventInterceptor eventInterceptor) {
        mainNamespace.addEventInterceptor(eventInterceptor);

    }

    @Override
    public void removeAllListeners(String eventName) {
        mainNamespace.removeAllListeners(eventName);
    }

    @Override
    public void onAnyEvent(CatchAllEventListener listener) {
        mainNamespace.onAnyEvent(listener);
    }

    @Override
    public void onAny(CatchAllEventListener listener) {
        onAnyEvent(listener);
    }

    @Override
    public void addDisconnectListener(DisconnectListener listener) {
        mainNamespace.addDisconnectListener(listener);
    }

    @Override
    public void addConnectListener(ConnectListener listener) {
        mainNamespace.addConnectListener(listener);
    }

    @Override
    public void addPingListener(PingListener listener) {
        mainNamespace.addPingListener(listener);
    }

    @Override
    public void addPongListener(PongListener listener) {
        mainNamespace.addPongListener(listener);
    }

    @Override
    public void addListeners(Object listeners) {
        mainNamespace.addListeners(listeners);
    }

    @Override
    public <L> void addListeners(Iterable<L> listeners) {
        mainNamespace.addListeners(listeners);
    }

    @Override
    public void addListeners(Object listeners, Class<?> clazz) {
        mainNamespace.addListeners(listeners, clazz);
    }

    /* ---------------------------------------------------------------------
     * Native Transport Detection — Safe Reflection
     * --------------------------------------------------------------------- */

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, SocketIOServer.class.getClassLoader());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isNativeAvailable(String className, String method) {
        try {
            Class<?> cls = Class.forName(className, false, SocketIOServer.class.getClassLoader());
            return (Boolean) cls.getMethod(method).invoke(null);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean ioUringAvailable() {
        return isClassPresent("io.netty.channel.uring.IoUring")
                && isNativeAvailable("io.netty.channel.uring.IoUring", "isAvailable");
    }

    private static boolean epollAvailable() {
        return isClassPresent("io.netty.channel.epoll.Epoll")
                && isNativeAvailable("io.netty.channel.epoll.Epoll", "isAvailable");
    }

    private static boolean kqueueAvailable() {
        return isClassPresent("io.netty.channel.kqueue.KQueue")
                && isNativeAvailable("io.netty.channel.kqueue.KQueue", "isAvailable");
    }

}
