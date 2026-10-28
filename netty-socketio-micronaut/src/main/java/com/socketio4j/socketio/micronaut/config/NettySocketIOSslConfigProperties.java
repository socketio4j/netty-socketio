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
package com.socketio4j.socketio.micronaut.config;

import javax.net.ssl.KeyManagerFactory;

import org.jspecify.annotations.Nullable;

import io.micronaut.context.annotation.ConfigurationProperties;

import static com.socketio4j.socketio.micronaut.config.NettySocketIOSslConfigProperties.PREFIX;

/**
 * SSL configuration properties for Netty Socket.IO server in Micronaut.
 * @see com.socketio4j.socketio.SocketSslConfig
 */
@ConfigurationProperties(PREFIX)
public class NettySocketIOSslConfigProperties {
    public static final String PREFIX = "netty-socket-io.ssl";

    private String sslProtocol = "TLSv1";
    private String keyStoreFormat = "JKS";
    private @Nullable String keyStore;
    private @Nullable String keyStorePassword;
    private String trustStoreFormat = "JKS";
    private @Nullable String trustStore;
    private @Nullable String trustStorePassword;
    private String keyManagerFactoryAlgorithm = KeyManagerFactory.getDefaultAlgorithm();

    public String getSslProtocol() {
        return sslProtocol;
    }

    public void setSslProtocol(String sslProtocol) {
        this.sslProtocol = sslProtocol;
    }

    public String getKeyStoreFormat() {
        return keyStoreFormat;
    }

    public void setKeyStoreFormat(String keyStoreFormat) {
        this.keyStoreFormat = keyStoreFormat;
    }

    public @Nullable String getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(@Nullable String keyStore) {
        this.keyStore = keyStore;
    }

    public @Nullable String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(@Nullable String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getTrustStoreFormat() {
        return trustStoreFormat;
    }

    public void setTrustStoreFormat(String trustStoreFormat) {
        this.trustStoreFormat = trustStoreFormat;
    }

    public @Nullable String getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(@Nullable String trustStore) {
        this.trustStore = trustStore;
    }

    public @Nullable String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(@Nullable String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getKeyManagerFactoryAlgorithm() {
        return keyManagerFactoryAlgorithm;
    }

    public void setKeyManagerFactoryAlgorithm(String keyManagerFactoryAlgorithm) {
        this.keyManagerFactoryAlgorithm = keyManagerFactoryAlgorithm;
    }
}
