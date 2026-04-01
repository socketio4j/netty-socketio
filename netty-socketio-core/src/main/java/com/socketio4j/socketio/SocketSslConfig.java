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

import java.io.IOException;
import java.io.InputStream;

import javax.net.ssl.KeyManagerFactory;

public class SocketSslConfig {
    private String sslProtocol = "TLSv1";

    private String keyStoreFormat = "JKS";
    private InputStream keyStore;
    private String keyStorePassword;

    private String trustStoreFormat = "JKS";
    private InputStream trustStore;
    private String trustStorePassword;

    private final Object sslMaterialLock = new Object();
    private byte[] cachedKeyStoreBytes;
    private byte[] cachedTrustStoreBytes;

    private String keyManagerFactoryAlgorithm = KeyManagerFactory.getDefaultAlgorithm();

    /**
     * SSL key store password
     *
     * @param keyStorePassword - password of key store
     */
    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    /**
     * SSL key store stream, maybe appointed to any source.
     * <p>
     * On the first TLS context build when the server starts, the stream is read fully into memory and closed;
     * later start/stop cycles reuse the buffered bytes so the same {@code SocketSslConfig} instance remains valid.
     * After buffering, {@link #getKeyStore()} returns {@code null}.
     * </p>
     *
     * @param keyStore - key store input stream
     */
    public void setKeyStore(InputStream keyStore) {
        this.keyStore = keyStore;
    }

    public InputStream getKeyStore() {
        return keyStore;
    }

    /**
     * Whether a key store is configured (stream not yet consumed or already buffered).
     */
    public boolean hasKeyStore() {
        synchronized (sslMaterialLock) {
            return keyStore != null || cachedKeyStoreBytes != null;
        }
    }

    byte[] resolveKeyStoreBytes() throws IOException {
        synchronized (sslMaterialLock) {
            if (cachedKeyStoreBytes != null) {
                return cachedKeyStoreBytes;
            }
            if (keyStore == null) {
                return null;
            }
            try (InputStream in = keyStore) {
                cachedKeyStoreBytes = in.readAllBytes();
            }
            keyStore = null;
            return cachedKeyStoreBytes;
        }
    }

    /**
     * Key store format
     *
     * @param keyStoreFormat - key store format
     */
    public void setKeyStoreFormat(String keyStoreFormat) {
        this.keyStoreFormat = keyStoreFormat;
    }

    public String getKeyStoreFormat() {
        return keyStoreFormat;
    }


    public String getTrustStoreFormat() {
        return trustStoreFormat;
    }

    public void setTrustStoreFormat(String trustStoreFormat) {
        this.trustStoreFormat = trustStoreFormat;
    }

    public InputStream getTrustStore() {
        return trustStore;
    }

    /**
     * Trust store stream. Same buffering and lifecycle as {@link #setKeyStore(InputStream)}.
     *
     * @param trustStore trust store input stream
     */
    public void setTrustStore(InputStream trustStore) {
        this.trustStore = trustStore;
    }

    /**
     * Whether a trust store is configured (stream not yet consumed or already buffered).
     */
    public boolean hasTrustStore() {
        synchronized (sslMaterialLock) {
            return trustStore != null || cachedTrustStoreBytes != null;
        }
    }

    byte[] resolveTrustStoreBytes() throws IOException {
        synchronized (sslMaterialLock) {
            if (cachedTrustStoreBytes != null) {
                return cachedTrustStoreBytes;
            }
            if (trustStore == null) {
                return null;
            }
            try (InputStream in = trustStore) {
                cachedTrustStoreBytes = in.readAllBytes();
            }
            trustStore = null;
            return cachedTrustStoreBytes;
        }
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getKeyManagerFactoryAlgorithm() {
        return keyManagerFactoryAlgorithm;
    }

    public void setKeyManagerFactoryAlgorithm(String keyManagerFactoryAlgorithm) {
        this.keyManagerFactoryAlgorithm = keyManagerFactoryAlgorithm;
    }

    /**
     * Set the name of the requested SSL protocol
     *
     * @param sslProtocol - name of protocol
     */
    public void setSSLProtocol(String sslProtocol) {
        this.sslProtocol = sslProtocol;
    }

    public String getSSLProtocol() {
        return sslProtocol;
    }
}
