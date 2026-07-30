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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.annotation.JsonProperty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Official JavaScript Socket.IO Client Interoperability Suite (v1, v2, v4)")
public class JsClientInteropTest extends AbstractSocketIOIntegrationTest {

    private void runJsTest(String version, String transport, String scenario) throws Exception {
        File jsDir = new File("src/test/resources/js-interop");
        if (!jsDir.exists()) {
            jsDir = new File("netty-socketio-core/src/test/resources/js-interop");
        }

        ProcessBuilder pb = new ProcessBuilder(
                "node",
                "test-clients.js",
                "--version=" + version,
                "--port=" + getServerPort(),
                "--transport=" + transport,
                "--scenario=" + scenario);
        pb.directory(jsDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean completed = process.waitFor(15, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("JS client process timed out. Output:\n" + output);
        }

        assertEquals(0, process.exitValue(),
                "JS client exited with non-zero status (" + process.exitValue() + "). Output:\n" + output);
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Connect Scenario")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsConnect(String version, String transport) throws Exception {
        runJsTest(version, transport, "connect");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Text Messaging & Response")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsTextMessaging(String version, String transport) throws Exception {
        AtomicBoolean received = new AtomicBoolean(false);
        getServer().addEventListener("testText", String.class, (client, data, ackRequest) -> {
            received.set(true);
            client.sendEvent("textResponse", "hello from server");
        });

        runJsTest(version, transport, "text");
        assertTrue(received.get(), "Server should have received testText event");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Client Event Text ACK")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsEventAck(String version, String transport) throws Exception {
        getServer().addEventListener("testAck", String.class, (client, data, ackRequest) -> {
            ackRequest.sendAckData("ack_reply_" + data);
        });

        runJsTest(version, transport, "ack");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Client Event Binary ACK")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsEventAckBinary(String version, String transport) throws Exception {
        getServer().addEventListener("testAckBinary", String.class, (client, data, ackRequest) -> {
            ackRequest.sendAckData(new byte[] { 50, 51, 52 });
        });

        runJsTest(version, transport, "ack_binary");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Server-Initiated Text ACK Callback")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsServerInitiatedAckText(String version, String transport) throws Exception {
        AtomicReference<String> ackReply = new AtomicReference<>();

        getServer().addConnectListener(client -> {
            client.sendEvent("serverReqAckText", new com.socketio4j.socketio.AckCallback<String>(String.class, 5) {
                @Override
                public void onSuccess(String result) {
                    ackReply.set(result);
                }
            }, "hello_from_server");
        });

        runJsTest(version, transport, "server_ack_text");
        assertEquals("js_ack_text_reply", ackReply.get(), "Server should receive text ACK reply from JS client callback");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Server-Initiated Binary ACK Callback")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsServerInitiatedAckBinary(String version, String transport) throws Exception {
        AtomicReference<byte[]> ackReply = new AtomicReference<>();

        getServer().addConnectListener(client -> {
            client.sendEvent("serverReqAckBinary", new com.socketio4j.socketio.AckCallback<byte[]>(byte[].class, 5) {
                @Override
                public void onSuccess(byte[] result) {
                    ackReply.set(result);
                }
            }, "hello_for_binary_ack");
        });

        runJsTest(version, transport, "server_ack_binary");
        assertArrayEquals(new byte[] { 55, 66, 77 }, ackReply.get(), "Server should receive binary ACK reply from JS client callback");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Server-Initiated Void ACK Callback")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsServerInitiatedVoidAck(String version, String transport) throws Exception {
        AtomicBoolean voidAckReceived = new AtomicBoolean(false);

        getServer().addConnectListener(client -> {
            client.sendEvent("serverReqVoidAck", new com.socketio4j.socketio.VoidAckCallback(5) {
                @Override
                protected void onSuccess() {
                    voidAckReceived.set(true);
                }
            }, "hello_void");
        });

        runJsTest(version, transport, "server_ack_void");
        assertTrue(voidAckReceived.get(), "Server should receive Void ACK callback from JS client");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Server-Initiated MultiType ACK Callback")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsServerInitiatedMultiTypeAck(String version, String transport) throws Exception {
        AtomicReference<String> stringReply = new AtomicReference<>();
        AtomicReference<byte[]> binaryReply = new AtomicReference<>();

        getServer().addConnectListener(client -> {
            client.sendEvent("serverReqMultiAck", new com.socketio4j.socketio.MultiTypeAckCallback(String.class, byte[].class) {
                @Override
                public void onSuccess(com.socketio4j.socketio.MultiTypeArgs res) {
                    stringReply.set(res.get(0));
                    binaryReply.set(res.get(1));
                }
            }, "hello_multi");
        });

        runJsTest(version, transport, "server_ack_multi");
        assertEquals("reply_string", stringReply.get(), "Server should receive first MultiType ACK arg");
        assertArrayEquals(new byte[] { 88, 99 }, binaryReply.get(), "Server should receive second MultiType ACK arg");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Binary Payload (byte[])")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsBinaryPayload(String version, String transport) throws Exception {
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        getServer().addEventListener("testBinary", byte[].class, (client, data, ackRequest) -> {
            receivedData.set(data);
            client.sendEvent("binaryResponse", new byte[] { 100, 101, 102 });
        });

        runJsTest(version, transport, "binary");
        assertArrayEquals(new byte[] { 10, 20, 30, 40, 50 }, receivedData.get(),
                "Server should receive intact binary payload");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Multiple Binary Attachments")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsMultiBinaryAttachments(String version, String transport) throws Exception {
        AtomicReference<byte[]> attachment1 = new AtomicReference<>();
        AtomicReference<byte[]> attachment2 = new AtomicReference<>();

        // JS sends: socket.emit('testMultiBinary', Buffer[1,2,3], Buffer[4,5,6])
        // Socket.IO binary protocol packs multiple Buffers as separate attachments.
        // addMultiTypeEventListener delivers all args via MultiTypeArgs; regular
        // DataListener<byte[]> only delivers args.get(0) and would miss the second
        // buffer.
        getServer().addMultiTypeEventListener("testMultiBinary", (client, data, ackRequest) -> {
            byte[] buf1 = data.get(0);
            byte[] buf2 = data.get(1);
            attachment1.set(buf1);
            attachment2.set(buf2);
            client.sendEvent("binaryResponse", new byte[] { 100, 101, 102 });
        }, byte[].class, byte[].class);

        runJsTest(version, transport, "multi_binary");
        assertArrayEquals(new byte[] { 1, 2, 3 }, attachment1.get(),
                "Server should receive first binary attachment intact");
        assertArrayEquals(new byte[] { 4, 5, 6 }, attachment2.get(),
                "Server should receive second binary attachment intact");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Map/Generic Object")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    @SuppressWarnings("unchecked")
    public void testJsMapObject(String version, String transport) throws Exception {
        AtomicReference<String> receivedName = new AtomicReference<>();
        AtomicReference<Integer> receivedValue = new AtomicReference<>();

        // JS sends: socket.emit('testObject', {name: 'hello', value: 42})
        // Server receives it as a Map<String,Object> (Jackson's default for generic Object.class)
        getServer().addEventListener("testObject", Object.class, (client, data, ackRequest) -> {
            java.util.Map<String, Object> obj = (java.util.Map<String, Object>) data;
            String name = (String) obj.get("name");
            int value = ((Number) obj.get("value")).intValue();
            receivedName.set(name);
            receivedValue.set(value);
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("echo", name);
            response.put("doubled", value * 2);
            client.sendEvent("objectResponse", response);
        });

        runJsTest(version, transport, "object");
        assertEquals("hello", receivedName.get(), "Server should receive the name field from JS object");
        assertEquals(42, receivedValue.get(), "Server should receive the value field from JS object");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Custom Typed Java POJO Object")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsCustomPojo(String version, String transport) throws Exception {
        AtomicReference<Payload> receivedPayload = new AtomicReference<>();

        // JS sends: socket.emit('testPojo', {name: 'hello', value: 42})
        // Server deserializes directly into typed Custom POJO (Payload.class)
        getServer().addEventListener("testPojo", Payload.class, (client, data, ackRequest) -> {
            receivedPayload.set(data);
            ObjectResponse response = new ObjectResponse(data.getName(), data.getValue() * 2);
            client.sendEvent("pojoResponse", response);
        });

        runJsTest(version, transport, "pojo");
        assertNotNull(receivedPayload.get(), "Server should deserialize into custom POJO");
        assertEquals("hello", receivedPayload.get().getName(), "Server should deserialize name getter");
        assertEquals(42, receivedPayload.get().getValue(), "Server should deserialize value getter");
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Mixed String + Binary Args")
    @CsvSource({
            "1, websocket",
            "1, polling",
            "2, websocket",
            "2, polling",
            "3, websocket",
            "3, polling",
            "4, websocket",
            "4, polling"
    })
    public void testJsMixedArgs(String version, String transport) throws Exception {
        AtomicReference<String> receivedText = new AtomicReference<>();
        AtomicReference<byte[]> receivedBytes = new AtomicReference<>();

        // JS sends: socket.emit('testMixed', 'hello_text', Buffer[7,8,9])
        // MultiTypeEventListener is required because args are heterogeneous: String +
        // byte[].
        // Server echoes both back: text with '_reply' suffix, bytes as-is.
        getServer().addMultiTypeEventListener("testMixed", (client, data, ackRequest) -> {
            String text = data.get(0);
            byte[] bytes = data.get(1);
            receivedText.set(text);
            receivedBytes.set(bytes);
            client.sendEvent("mixedResponse", text + "_reply", bytes);
        }, String.class, byte[].class);

        runJsTest(version, transport, "mixed");
        assertEquals("hello_text", receivedText.get(), "Server should receive the String argument");
        assertArrayEquals(new byte[] { 7, 8, 9 }, receivedBytes.get(),
                "Server should receive the binary argument intact");
    }

    // ---------------------------------------------------------------------------
    // Custom POJO classes used by testJsCustomPojo
    // ---------------------------------------------------------------------------

    public static class Payload {
        @JsonProperty("name")
        public String name;
        @JsonProperty("value")
        public int value;

        public Payload() {}
        public Payload(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }

    public static class ObjectResponse {
        @JsonProperty("echo")
        public String echo;
        @JsonProperty("doubled")
        public int doubled;

        public ObjectResponse() {}
        public ObjectResponse(String echo, int doubled) {
            this.echo = echo;
            this.doubled = doubled;
        }

        public String getEcho() { return echo; }
        public void setEcho(String echo) { this.echo = echo; }
        public int getDoubled() { return doubled; }
        public void setDoubled(int doubled) { this.doubled = doubled; }
    }
}
