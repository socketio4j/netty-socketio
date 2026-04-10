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
package com.socketio4j.socketio.transport;

import java.io.InputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.SocketSslConfig;
import com.socketio4j.socketio.nativeio.TransportType;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import okhttp3.OkHttpClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests using the official Java {@code socket.io-client} (OkHttp/WebSocket).
 */
public class SocketIoJavaClientSslTest {

    private SocketIOServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Test
    public void shouldReceiveHelloEventAndAckOverWssFromJavaClient() throws Exception {
        CountDownLatch serverReceivedHello = new CountDownLatch(1);
        CountDownLatch clientReceivedAck = new CountDownLatch(1);
        CountDownLatch engineHandshakeDone = new CountDownLatch(1);
        AtomicReference<Throwable> connectError = new AtomicReference<>();

        server = startServer(0, testSslConfig(), serverReceivedHello);
        int port = awaitBoundPort(server);
        assertTrue(port > 0, "server did not bind an ephemeral port");

        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());

        OkHttpClient okHttp = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                .hostnameVerifier((hostname, session) -> true)
                .readTimeout(1, TimeUnit.MINUTES)
                .build();

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = false;
        opts.transports = new String[] { "websocket" };
        opts.webSocketFactory = okHttp;
        opts.callFactory = okHttp;

        Socket socket = IO.socket("https://127.0.0.1:" + port, opts);
        try {
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                Object first = args.length > 0 ? args[0] : null;
                if (first instanceof Throwable) {
                    connectError.set((Throwable) first);
                } else {
                    connectError.set(new IllegalStateException(String.valueOf(first)));
                }
                engineHandshakeDone.countDown();
            });
            socket.on(Socket.EVENT_CONNECT, args -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("a", 1);
                    socket.emit("hello", payload, (Ack) ackArgs -> {
                        if (ackArgs.length > 0 && "ok".equals(String.valueOf(ackArgs[0]))) {
                            clientReceivedAck.countDown();
                        }
                    });
                } catch (Exception e) {
                    connectError.set(e);
                } finally {
                    engineHandshakeDone.countDown();
                }
            });
            socket.connect();

            assertTrue(engineHandshakeDone.await(20, TimeUnit.SECONDS),
                    () -> "Engine.IO handshake did not complete: " + connectError.get());
            assertNull(connectError.get(), () -> "connect_error: " + connectError.get());
            assertTrue(serverReceivedHello.await(15, TimeUnit.SECONDS), "server did not receive hello event");
            assertTrue(clientReceivedAck.await(15, TimeUnit.SECONDS), "client did not receive ack");
        } finally {
            socket.disconnect();
        }
    }

    @Test
    public void shouldHandshakeOverWssWhenSslProtocolNotExplicitlyConfigured() throws Exception {
        CountDownLatch serverReceivedHello = new CountDownLatch(1);
        CountDownLatch clientReceivedAck = new CountDownLatch(1);
        CountDownLatch engineHandshakeDone = new CountDownLatch(1);
        AtomicReference<Throwable> connectError = new AtomicReference<>();

        server = startServer(0, testSslConfigWithoutExplicitProtocol(), serverReceivedHello);
        int port = awaitBoundPort(server);
        assertTrue(port > 0, "server did not bind an ephemeral port");

        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());

        OkHttpClient okHttp = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                .hostnameVerifier((hostname, session) -> true)
                .readTimeout(1, TimeUnit.MINUTES)
                .build();

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = false;
        opts.transports = new String[] { "websocket" };
        opts.webSocketFactory = okHttp;
        opts.callFactory = okHttp;

        Socket socket = IO.socket("https://127.0.0.1:" + port, opts);
        try {
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                Object first = args.length > 0 ? args[0] : null;
                if (first instanceof Throwable) {
                    connectError.set((Throwable) first);
                } else {
                    connectError.set(new IllegalStateException(String.valueOf(first)));
                }
                engineHandshakeDone.countDown();
            });
            socket.on(Socket.EVENT_CONNECT, args -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("a", 1);
                    socket.emit("hello", payload, (Ack) ackArgs -> {
                        if (ackArgs.length > 0 && "ok".equals(String.valueOf(ackArgs[0]))) {
                            clientReceivedAck.countDown();
                        }
                    });
                } catch (Exception e) {
                    connectError.set(e);
                } finally {
                    engineHandshakeDone.countDown();
                }
            });
            socket.connect();

            assertTrue(engineHandshakeDone.await(20, TimeUnit.SECONDS),
                    () -> "Engine.IO handshake did not complete: " + connectError.get());
            assertNull(connectError.get(), () -> "connect_error: " + connectError.get());
            assertTrue(serverReceivedHello.await(15, TimeUnit.SECONDS), "server did not receive hello event");
            assertTrue(clientReceivedAck.await(15, TimeUnit.SECONDS), "client did not receive ack");
        } finally {
            socket.disconnect();
        }
    }

    /**
     * Exercises polling first (POST body via {@code PollingTransport.onPost}), then upgrade to WebSocket.
     * Server pushes an event right after connect so delivery runs while the client may still be on polling.
     */
    @Test
    public void shouldPollUpgradeToWebSocketWithServerPushAndClientHelloAckOverWss() throws Exception {
        CountDownLatch serverReceivedHello = new CountDownLatch(1);
        CountDownLatch clientReceivedAck = new CountDownLatch(1);
        CountDownLatch clientReceivedWelcome = new CountDownLatch(1);
        CountDownLatch engineHandshakeDone = new CountDownLatch(1);
        AtomicReference<Throwable> connectError = new AtomicReference<>();
        AtomicBoolean unexpectedDisconnect = new AtomicBoolean(false);

        server = startServer(0, testSslConfig(), serverReceivedHello, true);
        int port = awaitBoundPort(server);
        assertTrue(port > 0, "server did not bind an ephemeral port");

        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());

        OkHttpClient okHttp = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                .hostnameVerifier((hostname, session) -> true)
                .readTimeout(1, TimeUnit.MINUTES)
                .build();

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = false;
        opts.transports = new String[] { "polling", "websocket" };
        opts.webSocketFactory = okHttp;
        opts.callFactory = okHttp;

        Socket socket = IO.socket("https://127.0.0.1:" + port, opts);
        try {
            socket.on(Socket.EVENT_DISCONNECT, args -> unexpectedDisconnect.set(true));
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                Object first = args.length > 0 ? args[0] : null;
                if (first instanceof Throwable) {
                    connectError.set((Throwable) first);
                } else {
                    connectError.set(new IllegalStateException(String.valueOf(first)));
                }
                engineHandshakeDone.countDown();
            });
            socket.on("welcome", args -> clientReceivedWelcome.countDown());
            socket.on(Socket.EVENT_CONNECT, args -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("a", 1);
                    socket.emit("hello", payload, (Ack) ackArgs -> {
                        if (ackArgs.length > 0 && "ok".equals(String.valueOf(ackArgs[0]))) {
                            clientReceivedAck.countDown();
                        }
                    });
                } catch (Exception e) {
                    connectError.set(e);
                } finally {
                    engineHandshakeDone.countDown();
                }
            });
            socket.connect();

            assertTrue(engineHandshakeDone.await(20, TimeUnit.SECONDS),
                    () -> "Engine.IO handshake did not complete: " + connectError.get());
            assertNull(connectError.get(), () -> "connect_error: " + connectError.get());
            assertFalse(unexpectedDisconnect.get(), "client disconnected before assertions");
            assertTrue(clientReceivedWelcome.await(15, TimeUnit.SECONDS), "client did not receive server welcome on polling/upgrade path");
            assertTrue(serverReceivedHello.await(15, TimeUnit.SECONDS), "server did not receive hello event");
            assertTrue(clientReceivedAck.await(15, TimeUnit.SECONDS), "client did not receive ack");
            assertFalse(unexpectedDisconnect.get(), "client disconnected during polling upgrade or ack");
        } finally {
            socket.disconnect();
        }
    }

    @Test
    public void shouldReceiveHelloEventAndAckOverPlainWebSocketFromJavaClient() throws Exception {
        CountDownLatch serverReceivedHello = new CountDownLatch(1);
        CountDownLatch clientReceivedAck = new CountDownLatch(1);
        CountDownLatch engineHandshakeDone = new CountDownLatch(1);
        AtomicReference<Throwable> connectError = new AtomicReference<>();

        server = startServer(0, null, serverReceivedHello);
        int port = awaitBoundPort(server);
        assertTrue(port > 0, "server did not bind an ephemeral port");

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = false;
        opts.transports = new String[] { "websocket" };

        Socket socket = IO.socket("http://127.0.0.1:" + port, opts);
        try {
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                Object first = args.length > 0 ? args[0] : null;
                if (first instanceof Throwable) {
                    connectError.set((Throwable) first);
                } else {
                    connectError.set(new IllegalStateException(String.valueOf(first)));
                }
                engineHandshakeDone.countDown();
            });
            socket.on(Socket.EVENT_CONNECT, args -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("a", 1);
                    socket.emit("hello", payload, (Ack) ackArgs -> {
                        if (ackArgs.length > 0 && "ok".equals(String.valueOf(ackArgs[0]))) {
                            clientReceivedAck.countDown();
                        }
                    });
                } catch (Exception e) {
                    connectError.set(e);
                } finally {
                    engineHandshakeDone.countDown();
                }
            });
            socket.connect();

            assertTrue(engineHandshakeDone.await(20, TimeUnit.SECONDS),
                    () -> "Engine.IO handshake did not complete: " + connectError.get());
            assertNull(connectError.get(), () -> "connect_error: " + connectError.get());
            assertTrue(serverReceivedHello.await(15, TimeUnit.SECONDS), "server did not receive hello event");
            assertTrue(clientReceivedAck.await(15, TimeUnit.SECONDS), "client did not receive ack");
        } finally {
            socket.disconnect();
        }
    }

    @Test
    public void shouldReceiveHelloEventAndAckOverPollingFromJavaClient() throws Exception {
        CountDownLatch serverReceivedHello = new CountDownLatch(1);
        CountDownLatch clientReceivedAck = new CountDownLatch(1);
        CountDownLatch engineHandshakeDone = new CountDownLatch(1);
        AtomicReference<Throwable> connectError = new AtomicReference<>();

        server = startServer(0, testSslConfig(), serverReceivedHello);
        int port = awaitBoundPort(server);
        assertTrue(port > 0, "server did not bind an ephemeral port");

        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());

        OkHttpClient okHttp = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                .hostnameVerifier((hostname, session) -> true)
                .readTimeout(1, TimeUnit.MINUTES)
                .build();

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = false;
        opts.transports = new String[] { "polling" };
        opts.webSocketFactory = okHttp;
        opts.callFactory = okHttp;

        Socket socket = IO.socket("https://127.0.0.1:" + port, opts);
        try {
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                Object first = args.length > 0 ? args[0] : null;
                if (first instanceof Throwable) {
                    connectError.set((Throwable) first);
                } else {
                    connectError.set(new IllegalStateException(String.valueOf(first)));
                }
                engineHandshakeDone.countDown();
            });
            socket.on(Socket.EVENT_CONNECT, args -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("a", 1);
                    socket.emit("hello", payload, (Ack) ackArgs -> {
                        if (ackArgs.length > 0 && "ok".equals(String.valueOf(ackArgs[0]))) {
                            clientReceivedAck.countDown();
                        }
                    });
                } catch (Exception e) {
                    connectError.set(e);
                } finally {
                    engineHandshakeDone.countDown();
                }
            });
            socket.connect();

            assertTrue(engineHandshakeDone.await(20, TimeUnit.SECONDS),
                    () -> "Engine.IO handshake did not complete: " + connectError.get());
            assertNull(connectError.get(), () -> "connect_error: " + connectError.get());
            assertTrue(serverReceivedHello.await(15, TimeUnit.SECONDS), "server did not receive hello event");
            assertTrue(clientReceivedAck.await(15, TimeUnit.SECONDS), "client did not receive ack");
        } finally {
            socket.disconnect();
        }
    }

    @Test
    public void shouldReceiveHelloEventAndAckOverPlainPollingFromJavaClient() throws Exception {
        CountDownLatch serverReceivedHello = new CountDownLatch(1);
        CountDownLatch clientReceivedAck = new CountDownLatch(1);
        CountDownLatch engineHandshakeDone = new CountDownLatch(1);
        AtomicReference<Throwable> connectError = new AtomicReference<>();

        server = startServer(0, null, serverReceivedHello);
        int port = awaitBoundPort(server);
        assertTrue(port > 0, "server did not bind an ephemeral port");

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = false;
        opts.transports = new String[] { "polling" };

        Socket socket = IO.socket("http://127.0.0.1:" + port, opts);
        try {
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                Object first = args.length > 0 ? args[0] : null;
                if (first instanceof Throwable) {
                    connectError.set((Throwable) first);
                } else {
                    connectError.set(new IllegalStateException(String.valueOf(first)));
                }
                engineHandshakeDone.countDown();
            });
            socket.on(Socket.EVENT_CONNECT, args -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("a", 1);
                    socket.emit("hello", payload, (Ack) ackArgs -> {
                        if (ackArgs.length > 0 && "ok".equals(String.valueOf(ackArgs[0]))) {
                            clientReceivedAck.countDown();
                        }
                    });
                } catch (Exception e) {
                    connectError.set(e);
                } finally {
                    engineHandshakeDone.countDown();
                }
            });
            socket.connect();

            assertTrue(engineHandshakeDone.await(20, TimeUnit.SECONDS),
                    () -> "Engine.IO handshake did not complete: " + connectError.get());
            assertNull(connectError.get(), () -> "connect_error: " + connectError.get());
            assertTrue(serverReceivedHello.await(15, TimeUnit.SECONDS), "server did not receive hello event");
            assertTrue(clientReceivedAck.await(15, TimeUnit.SECONDS), "client did not receive ack");
        } finally {
            socket.disconnect();
        }
    }

    private SocketIOServer startServer(int port, SocketSslConfig ssl, CountDownLatch hello) {
        return startServer(port, ssl, hello, false);
    }

    private SocketIOServer startServer(int port, SocketSslConfig ssl, CountDownLatch hello, boolean sendWelcomeOnConnect) {
        Configuration cfg = new Configuration();
        cfg.setPort(port);
        cfg.setOrigin("*");
        if (ssl != null) {
            cfg.setSocketSslConfig(ssl);
        }
        cfg.setTransportType(TransportType.NIO);

        SocketIOServer s = new SocketIOServer(cfg);
        s.addEventListener("hello", Map.class, (client, data, ackSender) -> {
            hello.countDown();
            ackSender.sendAckData("ok");
        });
        if (sendWelcomeOnConnect) {
            s.addConnectListener(client -> client.sendEvent("welcome", "from-server"));
        }
        s.start();
        return s;
    }

    private SocketSslConfig testSslConfig() throws Exception {
        SocketSslConfig ssl = new SocketSslConfig();
        ssl.setSSLProtocol("TLSv1.2");
        ssl.setKeyStoreFormat("PKCS12");
        ssl.setKeyStorePassword("password");

        InputStream ks = SocketIoJavaClientSslTest.class.getClassLoader()
                .getResourceAsStream("ssl/test-socketio.p12");
        assertNotNull(ks, "Missing test keystore resource ssl/test-socketio.p12");
        ssl.setKeyStore(ks);
        return ssl;
    }

    private SocketSslConfig testSslConfigWithoutExplicitProtocol() throws Exception {
        SocketSslConfig ssl = new SocketSslConfig();
        ssl.setKeyStoreFormat("PKCS12");
        ssl.setKeyStorePassword("password");

        InputStream ks = SocketIoJavaClientSslTest.class.getClassLoader()
                .getResourceAsStream("ssl/test-socketio.p12");
        assertNotNull(ks, "Missing test keystore resource ssl/test-socketio.p12");
        ssl.setKeyStore(ks);
        return ssl;
    }

    private static int awaitBoundPort(SocketIOServer server) throws InterruptedException {
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int port = server.getConfiguration().getPort();
        while (port == 0 && System.nanoTime() < deadlineNs) {
            Thread.sleep(10);
            port = server.getConfiguration().getPort();
        }
        return port;
    }
}
