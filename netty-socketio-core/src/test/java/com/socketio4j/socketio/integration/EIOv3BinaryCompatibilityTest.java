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
package com.socketio4j.socketio.integration;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Engine.IO v3 Binary Compatibility Tests")
public class EIOv3BinaryCompatibilityTest extends AbstractSocketIOIntegrationTest {

    @Test
    @DisplayName("Should successfully decode binary event attachment from EIOv3 WebSocket client")
    public void testEIOv3BinaryWebSocketAttachment() throws Exception {
        final AtomicReference<byte[]> receivedData = new AtomicReference<byte[]>();
        final AtomicReference<Boolean> handshakeReceived = new AtomicReference<Boolean>(false);

        // 1. Add event listener for the binary event on the server
        getServer().addEventListener(
                "testBinary", byte[].class,
                (client, data, ackRequest) -> {
                    receivedData.set(data);
                }
        );

        // 2. Connect OkHttp WebSocket client simulating EIOv3 (EIO=3)
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("ws://" + getServerHost() + ":" + getServerPort() + "/socket.io/?EIO=3&transport=websocket")
                .build();

        WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (text.startsWith("0")) {
                    handshakeReceived.set(true);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("WebSocket failure: " + t.getMessage());
            }
        });

        // 3. Wait for handshaking message from server
        await().atMost(5, SECONDS)
                .until(handshakeReceived::get);

        // 4. Send connection packet to default namespace: "40"
        webSocket.send("40");

        // 5. Send event metadata packet: "451-[\"testBinary\",{\"_placeholder\":true,\"num\":0}]"
        webSocket.send("451-[\"testBinary\",{\"_placeholder\":true,\"num\":0}]");

        // 6. Send binary frame starting with byte 4 (EIOv3 MESSAGE type prefix) + data [10, 20, 30]
        byte[] rawPayload = {4, 10, 20, 30};
        webSocket.send(ByteString.of(rawPayload));

        // 7. Verify the server successfully received and decoded the raw payload (minus prefix byte 4)
        await().atMost(5, SECONDS)
                .until(() -> receivedData.get() != null);

        byte[] expectedData = {10, 20, 30};
        assertNotNull(receivedData.get());
        assertArrayEquals(expectedData, receivedData.get(), "The EIOv3 prefix byte 4 should be stripped, yielding [10, 20, 30]");

        webSocket.close(1000, "Done");
    }

    @Test
    @DisplayName("Should successfully decode binary event attachment from EIOv3 Polling client using Base64")
    public void testEIOv3BinaryPollingBase64() throws Exception {
        final AtomicReference<byte[]> receivedData = new AtomicReference<byte[]>();

        // 1. Add event listener
        getServer().addEventListener(
                "testBinary", byte[].class,
                (client, data, ackRequest) -> {
                    receivedData.set(data);
                }
        );

        OkHttpClient client = new OkHttpClient();

        // 2. Perform handshake
        String sid = performHandshake(client);

        // 3. Namespace connect packet: "40" -> payload: "2:40"
        sendPollingPost(client, sid, "2:40");

        // 4. Send event metadata packet: "451-[\"testBinary\",{\"_placeholder\":true,\"num\":0}]" -> length 48
        sendPollingPost(client, sid, "48:451-[\"testBinary\",{\"_placeholder\":true,\"num\":0}]");

        // 5. Send base64-encoded attachment packet: "b4AQID" (base64 of [1, 2, 3]) -> length 6
        sendPollingPost(client, sid, "6:b4AQID");

        // 6. Verify server received payload
        await().atMost(5, SECONDS)
                .until(() -> receivedData.get() != null);

        byte[] expectedData = {1, 2, 3};
        assertNotNull(receivedData.get());
        assertArrayEquals(expectedData, receivedData.get());
    }

    @Test
    @DisplayName("Should successfully decode binary event attachment from EIOv3 Polling client using raw binary wrapper")
    public void testEIOv3BinaryPollingWrapper() throws Exception {
        final AtomicReference<byte[]> receivedData = new AtomicReference<byte[]>();

        // 1. Add event listener
        getServer().addEventListener(
                "testBinary", byte[].class,
                (client, data, ackRequest) -> {
                    receivedData.set(data);
                }
        );

        OkHttpClient client = new OkHttpClient();

        // 2. Perform handshake
        String sid = performHandshake(client);

        // 3. Namespace connect packet: "40" -> payload: "2:40"
        sendPollingPost(client, sid, "2:40");

        // 4. Send event metadata packet: "451-[\"testBinary\",{\"_placeholder\":true,\"num\":0}]" -> length 48
        sendPollingPost(client, sid, "48:451-[\"testBinary\",{\"_placeholder\":true,\"num\":0}]");

        // 5. Send raw binary wrapped payload: indicator 1, length 4 (since payload [4, 10, 20, 30] has length 4), separator 255
        // bytes: [1, 52, -1, 4, 10, 20, 30] (where 52 is ASCII '4', -1 is delimiter 255, 4 is EIOv3 MESSAGE prefix)
        byte[] binaryBody = {1, 52, -1, 4, 10, 20, 30};
        sendPollingPostBinary(client, sid, binaryBody);

        // 6. Verify server received payload
        await().atMost(5, SECONDS)
                .until(() -> receivedData.get() != null);

        byte[] expectedData = {10, 20, 30};
        assertNotNull(receivedData.get());
        assertArrayEquals(expectedData, receivedData.get());
    }

    private String performHandshake(OkHttpClient client) throws Exception {
        Request request = new Request.Builder()
                .url("http://" + getServerHost() + ":" + getServerPort() + "/socket.io/?EIO=3&transport=polling")
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body().string();
            int jsonStartIndex = body.indexOf('{');
            if (jsonStartIndex == -1) {
                throw new IllegalStateException("Invalid handshake response format: " + body);
            }
            String json = body.substring(jsonStartIndex);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"sid\":\"([^\"]+)\"");
            java.util.regex.Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new IllegalStateException("sid not found in handshake response: " + body);
        }
    }

    private void sendPollingPost(OkHttpClient client, String sid, String textBody) throws Exception {
        RequestBody requestBody = RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), textBody);
        Request request = new Request.Builder()
                .url("http://" + getServerHost() + ":" + getServerPort() + "/socket.io/?EIO=3&transport=polling&sid=" + sid)
                .post(requestBody)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("POST failed: " + response.code() + " " + response.body().string());
            }
        }
    }

    private void sendPollingPostBinary(OkHttpClient client, String sid, byte[] binaryBody) throws Exception {
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/octet-stream"), binaryBody);
        Request request = new Request.Builder()
                .url("http://" + getServerHost() + ":" + getServerPort() + "/socket.io/?EIO=3&transport=polling&sid=" + sid)
                .post(requestBody)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("POST failed: " + response.code() + " " + response.body().string());
            }
        }
    }
}
