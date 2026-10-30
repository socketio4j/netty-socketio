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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.net.ssl.KeyManagerFactory;

import com.socketio4j.socketio.misc.EventLoopSafety;

public class SocketSslConfig {
    private String sslProtocol = "TLSv1";
    private boolean sslProtocolExplicitlySet;

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
        EventLoopSafety.requireBlockingAllowed();
        synchronized (sslMaterialLock) {
            return keyStore != null || cachedKeyStoreBytes != null;
        }
    }

    byte[] resolveKeyStoreBytes() throws IOException {
        EventLoopSafety.requireBlockingAllowed();
        synchronized (sslMaterialLock) {
            if (cachedKeyStoreBytes != null) {
                return cachedKeyStoreBytes;
            }
            if (keyStore == null) {
                return null;
            }
            try (InputStream in = keyStore) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                cachedKeyStoreBytes = out.toByteArray();
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
        EventLoopSafety.requireBlockingAllowed();
        synchronized (sslMaterialLock) {
            return trustStore != null || cachedTrustStoreBytes != null;
        }
    }

    byte[] resolveTrustStoreBytes() throws IOException {
        EventLoopSafety.requireBlockingAllowed();
        synchronized (sslMaterialLock) {
            if (cachedTrustStoreBytes != null) {
                return cachedTrustStoreBytes;
            }
            if (trustStore == null) {
                return null;
            }
            try (InputStream in = trustStore) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                cachedTrustStoreBytes = out.toByteArray();
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
        this.sslProtocolExplicitlySet = true;
    }

    public String getSSLProtocol() {
        return sslProtocol;
    }

    /**
     * Whether {@link #setSSLProtocol(String)} has been called.
     * <p>
     * This flag is driven purely by invocation, not by the semantic intent of the value. Integrations that
     * populate {@link SocketSslConfig} programmatically (e.g., factories, recorders, property binders)
     * should avoid calling {@link #setSSLProtocol(String)} unless they intentionally want to pin the enabled
     * TLS protocol version via {@code SslContextBuilder.protocols(...)}. When not explicitly set, the server
     * will rely on the provider defaults (typically TLSv1.2/1.3 on modern JSSE).
     * </p>
     */
    public boolean isSSLProtocolExplicitlySet() {
        return sslProtocolExplicitlySet;
    }

    /**
     * Resolves an SSL key store or trust store location from a {@code classpath:} resource,
     * a {@code file:} URI/path, a classpath resource path, or a filesystem path.
     *
     * @param location store location (may be {@code null} or blank if not configured)
     * @param classLoader class loader to use for classpath lookups
     * @return an open {@link InputStream} for the store, or {@code null} if {@code location} is {@code null} or blank
     * @throws IllegalStateException if a non-blank location cannot be found or opened
     */
    public static InputStream openStoreStream(String location, ClassLoader classLoader) {
        EventLoopSafety.requireBlockingAllowed();
        if (location == null) {
            return null;
        }
        String trimmed = location.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        ClassLoader effectiveLoader = classLoader;
        if (effectiveLoader == null) {
            effectiveLoader = Thread.currentThread().getContextClassLoader();
        }
        if (effectiveLoader == null) {
            effectiveLoader = SocketSslConfig.class.getClassLoader();
        }

        if (trimmed.startsWith("classpath:")) {
            String resourcePath = stripLeadingSlashes(trimmed.substring("classpath:".length()).trim());
            InputStream stream = getResourceStream(resourcePath, effectiveLoader);
            if (stream == null) {
                throw new IllegalStateException("SSL store resource not found on classpath: " + location);
            }
            return stream;
        }

        if (trimmed.startsWith("file:")) {
            Path filePath = resolveFileLocation(trimmed);
            return openFileStream(filePath, location);
        }

        InputStream classpathStream = getResourceStream(stripLeadingSlashes(trimmed), effectiveLoader);
        if (classpathStream != null) {
            return classpathStream;
        }

        Path path = Paths.get(trimmed);
        if (Files.isRegularFile(path)) {
            return openFileStream(path, location);
        }

        throw new IllegalStateException("SSL store not found on classpath or filesystem: " + location);
    }

    private static String stripLeadingSlashes(String path) {
        int index = 0;
        while (index < path.length() && path.charAt(index) == '/') {
            index++;
        }
        return path.substring(index);
    }

    private static InputStream getResourceStream(String resourcePath, ClassLoader loader) {
        if (resourcePath.isEmpty()) {
            return null;
        }
        InputStream stream = null;
        if (loader != null) {
            stream = loader.getResourceAsStream(resourcePath);
        }
        if (stream == null) {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null && contextLoader != loader) {
                stream = contextLoader.getResourceAsStream(resourcePath);
            }
        }
        if (stream == null) {
            ClassLoader ownLoader = SocketSslConfig.class.getClassLoader();
            if (ownLoader != null && ownLoader != loader) {
                stream = ownLoader.getResourceAsStream(resourcePath);
            }
        }
        return stream;
    }

    private static Path resolveFileLocation(String fileLocation) {
        if (fileLocation.startsWith("file://")) {
            try {
                return Paths.get(URI.create(fileLocation));
            } catch (IllegalArgumentException ignored) {
                // Fall back to stripping the prefix when the path contains unescaped characters
            }
        }
        return Paths.get(fileLocation.substring("file:".length()).trim());
    }

    private static InputStream openFileStream(Path path, String originalLocation) {
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open SSL store file: " + originalLocation, e);
        }
    }
}
