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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.handler.AuthorizeHandler;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.handler.ClientsBox;
import com.socketio4j.socketio.handler.EncoderHandler;
import com.socketio4j.socketio.handler.InPacketHandler;
import com.socketio4j.socketio.handler.PacketListener;
import com.socketio4j.socketio.handler.WrongUrlHandler;
import com.socketio4j.socketio.namespace.NamespacesHub;
import com.socketio4j.socketio.protocol.JsonSupport;
import com.socketio4j.socketio.protocol.PacketDecoder;
import com.socketio4j.socketio.protocol.PacketEncoder;
import com.socketio4j.socketio.scheduler.CancelableScheduler;
import com.socketio4j.socketio.scheduler.HashedWheelTimeoutScheduler;
import com.socketio4j.socketio.store.StoreFactory;
import com.socketio4j.socketio.store.event.DisconnectMessage;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.transport.PollingTransport;
import com.socketio4j.socketio.transport.WebSocketTransport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;

public class SocketIOChannelInitializer extends ChannelInitializer<Channel> implements DisconnectableHub {

    public static final String SOCKETIO_ENCODER = "socketioEncoder";
    public static final String WEB_SOCKET_TRANSPORT_COMPRESSION = "webSocketTransportCompression";
    public static final String WEB_SOCKET_TRANSPORT = "webSocketTransport";
    public static final String WEB_SOCKET_AGGREGATOR = "webSocketAggregator";
    public static final String XHR_POLLING_TRANSPORT = "xhrPollingTransport";
    public static final String AUTHORIZE_HANDLER = "authorizeHandler";
    public static final String PACKET_HANDLER = "packetHandler";
    public static final String HTTP_ENCODER = "httpEncoder";
    public static final String HTTP_COMPRESSION = "httpCompression";
    public static final String HTTP_AGGREGATOR = "httpAggregator";
    public static final String HTTP_REQUEST_DECODER = "httpDecoder";
    public static final String SSL_HANDLER = "ssl";

    public static final String RESOURCE_HANDLER = "resourceHandler";
    public static final String WRONG_URL_HANDLER = "wrongUrlBlocker";

    private static final Logger log = LoggerFactory.getLogger(SocketIOChannelInitializer.class);

    private @Nullable AckManager ackManager;

    private ClientsBox clientsBox = new ClientsBox();
    private @Nullable AuthorizeHandler authorizeHandler;
    private @Nullable PollingTransport xhrPollingTransport;
    private @Nullable WebSocketTransport webSocketTransport;
    private @Nullable EncoderHandler encoderHandler;
    private @Nullable WrongUrlHandler wrongUrlHandler;

    private CancelableScheduler scheduler = new HashedWheelTimeoutScheduler();

    private @Nullable InPacketHandler packetHandler;
    private @Nullable SslContext sslContext;
    private @Nullable Configuration configuration;
    private boolean needClientAuth;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduler.update(ctx);
    }

    public void start(Configuration configuration, NamespacesHub namespacesHub) {
        this.configuration = configuration;

        ackManager = new AckManager(scheduler);

        JsonSupport jsonSupport = configuration.getJsonSupport();
        if (jsonSupport == null) {
            throw new IllegalStateException("Configuration does not have JsonSupport configured");
        }
        PacketEncoder encoder = new PacketEncoder(configuration, jsonSupport);
        PacketDecoder decoder = new PacketDecoder(jsonSupport, ackManager);

        String connectPath = configuration.getContext() + "/";

        SocketSslConfig socketSslConfig = configuration.getSocketSslConfig();
        boolean isSsl = socketSslConfig != null && socketSslConfig.hasKeyStore();
        if (isSsl) {
            try {
                sslContext = createSSLContext(socketSslConfig);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        this.needClientAuth = configuration.isNeedClientAuth()
                && socketSslConfig != null
                && socketSslConfig.hasTrustStore();

        StoreFactory factory = configuration.getStoreFactory();
        authorizeHandler = new AuthorizeHandler(connectPath, scheduler, configuration, namespacesHub, factory, this, ackManager, clientsBox);
        factory.init(namespacesHub, authorizeHandler, jsonSupport);
        xhrPollingTransport = new PollingTransport(decoder, authorizeHandler, clientsBox);
        webSocketTransport = new WebSocketTransport(isSsl, authorizeHandler, configuration, scheduler, clientsBox);

        PacketListener packetListener = new PacketListener(ackManager, namespacesHub, xhrPollingTransport, scheduler);


        packetHandler = new InPacketHandler(packetListener, decoder, namespacesHub, configuration.getExceptionListener());

        try {
            encoderHandler = new EncoderHandler(configuration, encoder);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        wrongUrlHandler = new WrongUrlHandler();
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        addSslHandler(pipeline);
        addSocketioHandlers(pipeline);
    }

    /**
     * Adds the ssl handler
     *
     * @param pipeline - channel pipeline
     */
    protected void addSslHandler(ChannelPipeline pipeline) {
        if (sslContext != null) {
            SSLEngine engine = sslContext.newEngine(pipeline.channel().alloc());
            engine.setUseClientMode(false);
            if (needClientAuth) {
                engine.setNeedClientAuth(true);
            }
            pipeline.addLast(SSL_HANDLER, new SslHandler(engine));
        }
    }

    /**
     * Adds the socketio channel handlers
     *
     * @param pipeline - channel pipeline
     */
    protected void addSocketioHandlers(ChannelPipeline pipeline) {
        Configuration config = configuration;
        if (config == null) {
            throw new IllegalStateException("SocketIOChannelInitializer has not been started with Configuration");
        }
        pipeline.addLast(HTTP_REQUEST_DECODER, new HttpRequestDecoder(config.getHttpDecoderConfig()));
        pipeline.addLast(HTTP_AGGREGATOR, new HttpObjectAggregator(config.getMaxHttpContentLength()) {
            @Override
            protected @Nullable Object newContinueResponse(HttpMessage start, int maxContentLength,
                    ChannelPipeline pipeline) {
                return null;
            }

        });
        pipeline.addLast(HTTP_ENCODER, new HttpResponseEncoder());

        if (config.isHttpCompression()) {
            pipeline.addLast(HTTP_COMPRESSION, new HttpContentCompressor());
        }

        if (packetHandler != null) {
            pipeline.addLast(PACKET_HANDLER, packetHandler);
        }

        if (authorizeHandler != null) {
            pipeline.addLast(AUTHORIZE_HANDLER, authorizeHandler);
        }
        if (xhrPollingTransport != null) {
            pipeline.addLast(XHR_POLLING_TRANSPORT, xhrPollingTransport);
        }
        if (config.isWebsocketCompression()) {
            pipeline.addLast(WEB_SOCKET_TRANSPORT_COMPRESSION, new WebSocketServerCompressionHandler(config.getDecompressionBufferSize()));
        }
        if (webSocketTransport != null) {
            pipeline.addLast(WEB_SOCKET_TRANSPORT, webSocketTransport);
        }

        if (encoderHandler != null) {
            pipeline.addLast(SOCKETIO_ENCODER, encoderHandler);
        }

        if (wrongUrlHandler != null) {
            pipeline.addLast(WRONG_URL_HANDLER, wrongUrlHandler);
        }
    }

    private SslContext createSSLContext(SocketSslConfig socketSslConfig) throws Exception {
        byte[] keyMaterial = socketSslConfig.resolveKeyStoreBytes();
        if (keyMaterial == null) {
            throw new IllegalStateException("SocketSslConfig key store material is missing");
        }
        KeyStore ks = KeyStore.getInstance(socketSslConfig.getKeyStoreFormat());
        char[] keyPass;
        if (socketSslConfig.getKeyStorePassword() != null) {
            keyPass = socketSslConfig.getKeyStorePassword().toCharArray();
        } else {
            keyPass = new char[0];
        }
        try (InputStream keyStoreStream = new ByteArrayInputStream(keyMaterial)) {
            ks.load(keyStoreStream, keyPass);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(socketSslConfig.getKeyManagerFactoryAlgorithm());
        kmf.init(ks, keyPass);

        SslProvider sslProvider;
        if (OpenSsl.isAvailable()) {
            sslProvider = SslProvider.OPENSSL;
        } else {
            sslProvider = SslProvider.JDK;
        }

        SslContextBuilder builder = SslContextBuilder.forServer(kmf).sslProvider(sslProvider);
        String sslProtocol = socketSslConfig.getSSLProtocol();
        if (socketSslConfig.isSSLProtocolExplicitlySet() && sslProtocol != null) {
            // SocketSslConfig historically accepted SSLContext algorithm names like "TLS".
            // SslContextBuilder.protocols(...) expects concrete enabled protocol versions.
            if (isTlsProtocolVersion(sslProtocol)) {
                builder.protocols(sslProtocol);
            } else {
                log.warn("Ignoring SocketSslConfig.sslProtocol='{}' because it is not a concrete TLS protocol " +
                        "version (expected values like 'TLSv1.2' or 'TLSv1.3'). Using provider defaults instead.",
                        sslProtocol);
            }
        }
        if (socketSslConfig.hasTrustStore()) {
            byte[] trustMaterial = socketSslConfig.resolveTrustStoreBytes();
            if (trustMaterial == null) {
                throw new IllegalStateException("SocketSslConfig trust store material is missing");
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore ts = KeyStore.getInstance(socketSslConfig.getTrustStoreFormat());
            char[] trustPass;
            if (socketSslConfig.getTrustStorePassword() != null) {
                trustPass = socketSslConfig.getTrustStorePassword().toCharArray();
            } else {
                trustPass = new char[0];
            }
            try (InputStream trustStoreStream = new ByteArrayInputStream(trustMaterial)) {
                ts.load(trustStoreStream, trustPass);
            }
            tmf.init(ts);
            builder.trustManager(tmf);
        }
        return builder.build();
    }

    private static boolean isTlsProtocolVersion(String value) {
        // Common enabled-protocol tokens used by JSSE/Netty.
        // Accept "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"; reject "TLSv1.0" and other dotted minors.
        if (value == null) {
            return false;
        }
        return value.matches("^TLSv1(\\.(1|2|3))?$");
    }

    @Override
    public void onDisconnect(ClientHead client) {
        if (ackManager != null) {
            ackManager.onDisconnect(client);
        }
        if (authorizeHandler != null) {
            authorizeHandler.onDisconnect(client);
        }
        if (configuration != null) {
            configuration.getStoreFactory().onDisconnect(client);
            configuration.getStoreFactory().eventStore().publish(EventType.DISCONNECT, new DisconnectMessage(client.getSessionId()));
        }

        log.debug("Client with sessionId: {} disconnected", client.getSessionId());
    }

    public void stop() {
        if (ackManager != null) {
            ackManager.clear();
        }
        if (configuration != null) {
            StoreFactory factory = configuration.getStoreFactory();
            factory.shutdown();
        }
        scheduler.shutdown();
    }

}
