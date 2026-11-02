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
package com.socketio4j.socketio.integration.protocol;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.integration.RawWebSocketClient;
import com.socketio4j.socketio.transport.NamespaceClient;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent wire transcripts and exact configured-limit boundaries. */
public class ProtocolWireBoundaryTest {
    private static Configuration configuration() {
        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(0);
        config.setPingInterval(30_000);
        config.setPingTimeout(1_000);
        return config;
    }

    private static String request(int port, String sid, String body, int expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port
                + "/socket.io/?EIO=4&transport=polling" + (sid == null ? "" : "&sid=" + sid)).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            assertEquals(expectedStatus, connection.getResponseCode());
            InputStream input = expectedStatus >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return "";
            try (InputStream stream = input;
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[1_024];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String pollingSession(int port) throws Exception {
        String open = request(port, null, null, 200);
        String sid = new ObjectMapper().readTree(open.substring(1)).get("sid").asText();
        assertEquals("ok", request(port, sid, "40", 200));
        assertTrue(request(port, sid, null, 200).startsWith("40{"));
        return sid;
    }

    @Test
    void pendingBinaryAttachmentAndAckSurviveTheProbeAndUpgrade() throws Exception {
        Configuration config = configuration();
        SocketIOServer server = new SocketIOServer(config);
        AtomicInteger delivered = new AtomicInteger();
        server.addEventListener("upload", byte[].class, (client, bytes, ack) -> {
            delivered.incrementAndGet();
            ack.sendAckData(bytes);
        });
        try {
            server.start();
            String sid = pollingSession(config.getPort());
            ClientHead head = ((NamespaceClient) server.getClient(UUID.fromString(sid))).getBaseClient();
            assertEquals("ok", request(config.getPort(), sid,
                    "451-8[\"upload\",{\"_placeholder\":true,\"num\":0}]", 200));
            await().atMost(5, TimeUnit.SECONDS).until(() -> head.getLastBinaryPacket() != null);
            assertEquals(0, delivered.get());
            try (RawWebSocketClient peer = new RawWebSocketClient(config.getPort(), true, sid)) {
                peer.send("2probe");
                assertEquals("3probe", peer.awaitFrame("3probe"::equals));
                assertEquals("6", request(config.getPort(), sid, null, 200));
                peer.send("5");
                await().atMost(5, TimeUnit.SECONDS).until(() ->
                        head.getCurrentTransport() == com.socketio4j.socketio.Transport.WEBSOCKET);
                byte[] attachment = {0, 1, -1, 4};
                peer.send(attachment);
                assertEquals("461-8[{\"_placeholder\":true,\"num\":0}]",
                        peer.awaitFrame(frame -> frame.startsWith("461-8")));
                assertArrayEquals(attachment, peer.awaitBinaryFrame());
                assertEquals(1, delivered.get());
                assertNull(head.getLastBinaryPacket());
                assertNull(head.getLastBinaryPacketSource());
            }
            await().atMost(5, TimeUnit.SECONDS).until(() -> server.getAllClients().isEmpty());
        } finally {
            server.stop();
        }
    }

    @Test
    void abruptSocketCloseReleasesAnActuallyPendingBinarySource() throws Exception {
        Configuration config = configuration();
        SocketIOServer server = new SocketIOServer(config);
        AtomicInteger delivered = new AtomicInteger();
        server.addEventListener("upload", byte[].class, (client, data, ack) -> delivered.incrementAndGet());
        server.addEventListener("health", String.class, (client, data, ack) -> ack.sendAckData(data));
        try {
            server.start();
            ClientHead head;
            ByteBuf retained;
            try (RawWebSocketClient peer = new RawWebSocketClient(config.getPort(), true)) {
                head = ((NamespaceClient) server.getClient(UUID.fromString(peer.sessionId()))).getBaseClient();
                peer.send("451-8[\"upload\",{\"_placeholder\":true,\"num\":0}]");
                await().atMost(5, TimeUnit.SECONDS).until(() -> head.getLastBinaryPacketSource() != null);
                retained = head.getLastBinaryPacketSource();
                assertTrue(retained.refCnt() > 0, "The binary header must actually retain an incomplete source");
                assertEquals(0, delivered.get());
            } // Cancels TCP/WebSocket, without Socket.IO DISCONNECT or an attachment.
            await().atMost(5, TimeUnit.SECONDS).until(() -> !head.isConnected());
            await().atMost(5, TimeUnit.SECONDS).until(() -> retained.refCnt() == 0);
            assertNull(head.getLastBinaryPacket());
            assertNull(head.getLastBinaryPacketSource());
            assertEquals(0, delivered.get());
            try (RawWebSocketClient fresh = new RawWebSocketClient(config.getPort(), true)) {
                fresh.assertHealthExchange("after-abort");
            }
        } finally {
            server.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1_023, 1_024, 1_025})
    void pollingPayloadLimitIsEnforcedAtTheExactByteBoundary(int wireBytes) throws Exception {
        Configuration config = configuration();
        config.setMaxHttpContentLength(1_024);
        SocketIOServer server = new SocketIOServer(config);
        AtomicInteger delivered = new AtomicInteger();
        server.addEventListener("limit", String.class, (client, data, ack) -> {
            delivered.incrementAndGet();
            ack.sendAckData(data.length());
        });
        try {
            server.start();
            String sid = pollingSession(config.getPort());
            String prefix = "427[\"limit\",\"";
            String suffix = "\"]";
            char[] characters = new char[wireBytes - prefix.length() - suffix.length()];
            Arrays.fill(characters, 'x');
            String wire = prefix + new String(characters) + suffix;
            assertEquals(wireBytes, wire.getBytes(StandardCharsets.UTF_8).length);
            request(config.getPort(), sid, wire, wireBytes <= 1_024 ? 200 : 413);
            if (wireBytes <= 1_024) {
                String response = request(config.getPort(), sid, null, 200);
                assertEquals("437[" + characters.length + "]", response);
                assertEquals(1, delivered.get());
            } else {
                assertEquals(0, delivered.get(), "Oversize payload must not reach application code");
            }
        } finally {
            server.stop();
        }
    }
}
