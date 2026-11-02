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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.SocketSslConfig;
import com.socketio4j.socketio.TestResourceCleanup;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real certificate chains, default hostname verification and required client identity. */
class TlsPeerVerificationTest {
    private static final char[] PASSWORD = "test-only-password".toCharArray();
    @TempDir
    static Path certificates;

    @BeforeAll
    static void generateTestIdentities() throws Exception {
        for (String identity : new String[]{"server", "trusted-client", "untrusted-client"}) {
            java.util.List<String> command = new java.util.ArrayList<>(java.util.Arrays.asList(
                    java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", identity, "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", certificates.resolve(identity + ".p12").toString(),
                    "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD),
                    "-dname", "CN=" + (identity.equals("server") ? "localhost" : identity),
                    "-validity", "2", "-noprompt"));
            if (identity.equals("server")) {
                command.addAll(java.util.Arrays.asList("-ext", "SAN=dns:localhost"));
            }
            Process keytool = new ProcessBuilder(command).redirectErrorStream(true).start();
            try {
                assertTrue(keytool.waitFor(30, TimeUnit.SECONDS), "Test certificate generation must finish");
                String output;
                try (java.util.Scanner scanner = new java.util.Scanner(keytool.getInputStream(), "UTF-8")) {
                    scanner.useDelimiter("\\A");
                    output = scanner.hasNext() ? scanner.next() : "";
                }
                assertEquals(0, keytool.exitValue(), output);
            } finally {
                if (keytool.isAlive()) keytool.destroyForcibly();
            }
        }
    }

    private static KeyStore identity(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(certificates.resolve(name + ".p12"))) {
            store.load(input, PASSWORD);
        }
        return store;
    }

    private static KeyStore trust(String identity) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, PASSWORD);
        store.setCertificateEntry(identity, identity(identity).getCertificate(identity));
        return store;
    }

    @ParameterizedTest(name = "mutual={0}, client={1}, trustedServer={2}, host={3}, accepted={4}")
    @CsvSource({
        "false, none, true, localhost, true",
        "false, none, false, localhost, false",
        "false, none, true, 127.0.0.1, false",
        "true, none, true, localhost, false",
        "true, trusted-client, true, localhost, true",
        "true, untrusted-client, true, localhost, false"
    })
    void verifiesServerAndClientIdentity(boolean mutual, String clientIdentity, boolean trustedServer,
                                        String host, boolean accepted) throws Exception {
        SocketSslConfig ssl = new SocketSslConfig();
        ssl.setSSLProtocol("TLSv1.2");
        ssl.setKeyStoreFormat("PKCS12");
        ssl.setKeyStorePassword(new String(PASSWORD));
        ssl.setKeyStore(new ByteArrayInputStream(Files.readAllBytes(certificates.resolve("server.p12"))));
        ByteArrayOutputStream trustBytes = new ByteArrayOutputStream();
        trust("trusted-client").store(trustBytes, PASSWORD);
        ssl.setTrustStoreFormat("PKCS12");
        ssl.setTrustStorePassword(new String(PASSWORD));
        ssl.setTrustStore(new ByteArrayInputStream(trustBytes.toByteArray()));
        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(0);
        config.setNeedClientAuth(mutual);
        config.setSocketSslConfig(ssl);
        SocketIOServer server = new SocketIOServer(config);
        OkHttpClient.Builder http = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false);
        KeyManager[] keys = null;
        if (!clientIdentity.equals("none")) {
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(identity(clientIdentity), PASSWORD);
            keys = factory.getKeyManagers();
        }
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(trustedServer ? trust("server") : (KeyStore) null);
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(keys, trusts.getTrustManagers(), null);
        http.sslSocketFactory(context.getSocketFactory(), (X509TrustManager) trusts.getTrustManagers()[0]);
        // Keep OkHttp's default hostname verifier; no trust-all bypass is installed.
        OkHttpClient client = http.build();
        try {
            server.start();
            Request request = new Request.Builder().url("https://" + host + ":" + config.getPort()
                    + "/socket.io/?EIO=4&transport=polling").build();
            if (accepted) {
                try (Response response = client.newCall(request).execute()) {
                    assertEquals(200, response.code());
                    String body = response.body().string();
                    assertTrue(body.startsWith("0{"));
                    assertNotNull(new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(body.substring(1)).get("sid"));
                }
            } else {
                assertThrows(IOException.class, () -> {
                    try (Response response = client.newCall(request).execute()) {
                        throw new AssertionError("Peer authentication unexpectedly accepted HTTP " + response.code());
                    }
                });
            }
        } finally {
            TestResourceCleanup.runAll("TLS peer verification cleanup", server::stop, () -> {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdownNow();
            });
        }
    }
}
