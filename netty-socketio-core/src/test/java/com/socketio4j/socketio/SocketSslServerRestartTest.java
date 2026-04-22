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

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.nativeio.TransportType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures TLS material from {@link SocketSslConfig} survives stop/start when streams are not reusable.
 */
public class SocketSslServerRestartTest {

    @Test
    public void shouldStartStopStartWithSameSocketSslConfig() throws Exception {
        Configuration cfg = new Configuration();
        cfg.setPort(0);
        cfg.setOrigin("*");
        cfg.setTransportType(TransportType.NIO);

        SocketSslConfig ssl = new SocketSslConfig();
        ssl.setSSLProtocol("TLSv1.2");
        ssl.setKeyStoreFormat("PKCS12");
        ssl.setKeyStorePassword("password");
        InputStream ks = SocketSslServerRestartTest.class.getClassLoader()
                .getResourceAsStream("ssl/test-socketio.p12");
        assertNotNull(ks, "Missing test keystore ssl/test-socketio.p12");
        ssl.setKeyStore(ks);

        cfg.setSocketSslConfig(ssl);

        SocketIOServer server = new SocketIOServer(cfg);
        server.start();
        int port = awaitBoundPort(server);
        assertTrue(port > 0);
        server.stop();

        assertDoesNotThrow(server::start, "second start should rebuild SSL from buffered keystore bytes");
        server.stop();
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
