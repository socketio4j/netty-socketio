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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socketio4j.socketio.namespace.NamespacesHub;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocketSslConfigEventLoopSafetyTest {

    private EventLoop eventLoop;

    @BeforeEach
    void setUp() {
        eventLoop = new DefaultEventLoop();
    }

    @AfterEach
    void tearDown() {
        if (eventLoop != null) {
            eventLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void shouldAllowSslConfigOperationsOnNormalThread() throws Exception {
        SocketSslConfig config = new SocketSslConfig();
        assertThat(config.hasKeyStore()).isFalse();
        assertThat(config.resolveKeyStoreBytes()).isNull();
        assertThat(config.hasTrustStore()).isFalse();
        assertThat(config.resolveTrustStoreBytes()).isNull();
    }

    @Test
    void shouldRejectSslConfigOperationsOnEventLoop() throws Exception {
        SocketSslConfig config = new SocketSslConfig();

        eventLoop.submit(() -> {
            assertThatThrownBy(config::hasKeyStore)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Blocking control-plane operation must run off the Netty event loop");

            assertThatThrownBy(config::resolveKeyStoreBytes)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Blocking control-plane operation must run off the Netty event loop");

            assertThatThrownBy(config::hasTrustStore)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Blocking control-plane operation must run off the Netty event loop");

            assertThatThrownBy(config::resolveTrustStoreBytes)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Blocking control-plane operation must run off the Netty event loop");

            assertThatThrownBy(() -> SocketSslConfig.openStoreStream(
                    "classpath:ssl/test-socketio.p12",
                    SocketSslConfigEventLoopSafetyTest.class.getClassLoader()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Blocking control-plane operation must run off the Netty event loop");
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldResolveClasspathAndFilesystemStoreStreamsAndRejectMissingLocations(@TempDir Path tempDir) throws Exception {
        ClassLoader loader = SocketSslConfigEventLoopSafetyTest.class.getClassLoader();

        assertThat(SocketSslConfig.openStoreStream(null, loader)).isNull();
        assertThat(SocketSslConfig.openStoreStream("   ", loader)).isNull();

        try (InputStream cpStream = SocketSslConfig.openStoreStream("classpath:ssl/test-socketio.p12", loader)) {
            assertThat(cpStream).isNotNull();
            assertThat(cpStream.read()).isGreaterThanOrEqualTo(0);
        }

        try (InputStream plainCpStream = SocketSslConfig.openStoreStream("ssl/test-socketio.p12", loader)) {
            assertThat(plainCpStream).isNotNull();
            assertThat(plainCpStream.read()).isGreaterThanOrEqualTo(0);
        }

        Path copiedStore = tempDir.resolve("copied-keystore.p12");
        try (InputStream src = SocketSslConfig.openStoreStream("classpath:ssl/test-socketio.p12", loader)) {
            Files.copy(src, copiedStore, StandardCopyOption.REPLACE_EXISTING);
        }

        try (InputStream fsStream = SocketSslConfig.openStoreStream(copiedStore.toString(), loader)) {
            assertThat(fsStream).isNotNull();
            assertThat(fsStream.read()).isGreaterThanOrEqualTo(0);
        }

        try (InputStream fileUriStream = SocketSslConfig.openStoreStream(copiedStore.toUri().toString(), loader)) {
            assertThat(fileUriStream).isNotNull();
            assertThat(fileUriStream.read()).isGreaterThanOrEqualTo(0);
        }

        assertThatThrownBy(() -> SocketSslConfig.openStoreStream("classpath:ssl/does-not-exist.p12", loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SSL store resource not found on classpath");

        assertThatThrownBy(() -> SocketSslConfig.openStoreStream(tempDir.resolve("missing.p12").toString(), loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SSL store not found on classpath or filesystem");

        assertThatThrownBy(() -> SocketSslConfig.openStoreStream("file:" + tempDir.resolve("missing.p12"), loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to open SSL store file");
    }

    @Test
    void shouldInitializeSslContextWithNullTrustStorePasswordAndDefaultProtocols() throws Exception {
        ClassLoader loader = SocketSslConfigEventLoopSafetyTest.class.getClassLoader();
        SocketSslConfig sslConfig = new SocketSslConfig();
        sslConfig.setKeyStoreFormat("PKCS12");
        sslConfig.setKeyStorePassword("password");
        sslConfig.setKeyStore(SocketSslConfig.openStoreStream("classpath:ssl/test-socketio.p12", loader));
        sslConfig.setTrustStoreFormat("PKCS12");
        sslConfig.setTrustStorePassword(null);
        sslConfig.setTrustStore(SocketSslConfig.openStoreStream("classpath:ssl/test-socketio.p12", loader));

        assertThat(sslConfig.isSSLProtocolExplicitlySet()).isFalse();

        Configuration configuration = new Configuration();
        configuration.setSocketSslConfig(sslConfig);
        SocketIOChannelInitializer initializer = new SocketIOChannelInitializer();
        try {
            assertThatCode(() -> initializer.start(configuration, new NamespacesHub(configuration)))
                    .doesNotThrowAnyException();
        } finally {
            initializer.stop();
        }
    }
}
