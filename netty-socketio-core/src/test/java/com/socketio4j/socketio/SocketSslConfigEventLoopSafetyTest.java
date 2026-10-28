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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

import static org.assertj.core.api.Assertions.assertThat;
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
        }).get(5, TimeUnit.SECONDS);
    }
}
