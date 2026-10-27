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
package com.socketio4j.socketio.integration.resilience;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.SocketSslConfig;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.listener.DataListener;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-readiness test: SSL/TLS Encrypted WSS & HTTPS Transport.
 *
 * <p>Verifies end-to-end TLS encryption, SslHandler Netty pipeline integration, and encrypted event delivery
 * using a PKCS12 test keystore.
 */
public class SSLSecureSocketTransportTest {

    private static final Logger log = LoggerFactory.getLogger(SSLSecureSocketTransportTest.class);

    private static final long TIMEOUT_SECS = 20L;

    private SocketIOServer server;
    private int port;
    private Socket clientSocket;

    @AfterEach
    public void tearDown() {
        if (clientSocket != null) {
            try {
                clientSocket.off();
                clientSocket.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting client: {}", e.getMessage());
            }
        }

        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("Error stopping server: {}", e.getMessage());
            }
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find available TCP port", e);
        }
    }

    private SocketSslConfig testSslConfig() {
        SocketSslConfig ssl = new SocketSslConfig();
        ssl.setKeyStoreFormat("PKCS12");
        ssl.setKeyStorePassword("password");

        InputStream ks = SSLSecureSocketTransportTest.class.getClassLoader()
                .getResourceAsStream("ssl/test-socketio.p12");
        assertNotNull(ks, "Missing test keystore resource ssl/test-socketio.p12");
        ssl.setKeyStore(ks);
        return ssl;
    }

    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
    };

    private SSLContext trustAllSSLContext() throws Exception {
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, new TrustManager[]{ TRUST_ALL_MANAGER }, new java.security.SecureRandom());
        return sc;
    }

    @Test
    @DisplayName("SSL/TLS Secure Transport Test: WSS Encrypted Handshake and Payload")
    public void testSecureSSLEncryptedTransport() throws Exception {
        port = findAvailablePort();

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setTransports(Transport.WEBSOCKET);
        config.setSocketSslConfig(testSslConfig());

        server = new SocketIOServer(config);

        AtomicReference<String> serverReceived = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        server.addEventListener("ssl-event", String.class, new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String data, AckRequest ackSender) {
                serverReceived.set(data);
                serverLatch.countDown();
                if (ackSender.isAckRequested()) {
                    ackSender.sendAckData("SSL-ACK");
                }
            }
        });

        server.start();

        SSLContext sslContext = trustAllSSLContext();
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), TRUST_ALL_MANAGER)
                .hostnameVerifier((hostname, session) -> true)
                .build();

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.transports = new String[]{ "websocket" };
        opts.callFactory = okHttpClient;
        opts.webSocketFactory = okHttpClient;

        clientSocket = IO.socket("https://127.0.0.1:" + port, opts);
        CountDownLatch connectLatch = new CountDownLatch(1);

        clientSocket.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        clientSocket.connect();

        assertTrue(connectLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Client failed to connect via WSS / SSL");

        clientSocket.emit("ssl-event", new Object[]{ "ENCRYPTED_SECRET_DATA" }, new Ack() {
            @Override
            public void call(Object... args) {
                if (args.length > 0 && "SSL-ACK".equals(args[0])) {
                    ackLatch.countDown();
                }
            }
        });

        assertTrue(serverLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Server failed to receive SSL event");
        assertTrue(ackLatch.await(TIMEOUT_SECS, TimeUnit.SECONDS), "Client ACK failed over SSL");

        assertEquals("ENCRYPTED_SECRET_DATA", serverReceived.get(), "SSL payload mismatch");
    }
}
