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
package com.socketio4j.socketio.spring.boot.starter.config;

import java.io.FileInputStream;
import java.io.InputStream;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.socketio4j.socketio.SocketSslConfig;

import static com.socketio4j.socketio.spring.boot.starter.config.NettySocketIOSslConfigProperties.PREFIX;

/**
 * SSL configuration properties for Netty Socket.IO server.
 * @see SocketSslConfig
 */
@ConfigurationProperties(prefix = PREFIX)
public class NettySocketIOSslConfigProperties extends SocketSslConfig {
    public static final String PREFIX = "netty-socket-io.ssl";

    private String keyStorePath;
    private String trustStorePath;

    public String getSslProtocol() {
        return getSSLProtocol();
    }

    public void setSslProtocol(String sslProtocol) {
        setSSLProtocol(sslProtocol);
    }

    public String getProtocol() {
        return getSSLProtocol();
    }

    public void setProtocol(String protocol) {
        setSSLProtocol(protocol);
    }

    public String getKeyStorePath() {
        return keyStorePath;
    }

    public void setKeyStorePath(String keyStorePath) {
        this.keyStorePath = keyStorePath;
        InputStream stream = openResource(keyStorePath);
        if (stream != null) {
            setKeyStore(stream);
        }
    }

    public String getTrustStorePath() {
        return trustStorePath;
    }

    public void setTrustStorePath(String trustStorePath) {
        this.trustStorePath = trustStorePath;
        InputStream stream = openResource(trustStorePath);
        if (stream != null) {
            setTrustStore(stream);
        }
    }

    private InputStream openResource(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String cleanPath = path.trim();
        if (cleanPath.startsWith("classpath:")) {
            cleanPath = cleanPath.substring("classpath:".length());
        }
        String classPathNoSlash = cleanPath;
        if (classPathNoSlash.startsWith("/")) {
            classPathNoSlash = classPathNoSlash.substring(1);
        }
        String classPathWithSlash = "/" + classPathNoSlash;

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream stream = null;
        if (cl != null) {
            stream = cl.getResourceAsStream(classPathNoSlash);
        }
        if (stream == null) {
            stream = getClass().getResourceAsStream(classPathWithSlash);
        }
        if (stream == null) {
            try {
                stream = new FileInputStream(cleanPath);
            } catch (Exception ignored) {
                // ignore file system error
            }
        }
        return stream;
    }
}
