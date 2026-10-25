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
package com.socketio4j.socketio.integration.interop;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.annotation.JsonProperty;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ResourceLock("NODE_JS_INTEROP")
@DisplayName("Official JavaScript Socket.IO Client Interoperability Suite (v1, v2, v3, v4)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JsClientInteropTest extends AbstractReusableSocketIOInteropTest {

    private static Stream<String> clientVersions() {
        return JsClientInteropMatrix.clientVersions();
    }

    private static Stream<Arguments> clientTransports() {
        return JsClientInteropMatrix.clientTransports();
    }

    private static Stream<Arguments> clientPollingTransports() {
        return JsClientInteropMatrix.pollingClientTransports();
    }

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
        AtomicReference<Throwable> outputFailure = new AtomicReference<>();

        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                }
            } catch (Throwable error) {
                outputFailure.set(error);
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        try {
            boolean completed = process.waitFor(20, TimeUnit.SECONDS);
            if (!completed) {
                fail(String.format("JS client process timed out after 20s (v%s, %s, scenario=%s, port=%d).\nOutput logs:\n%s",
                        version, transport, scenario, getServerPort(), getOutput(output)));
            }
            outputThread.join(TimeUnit.SECONDS.toMillis(1));
            if (outputThread.isAlive()) {
                fail("JS client output reader did not terminate\n" + getOutput(output));
            }
            if (outputFailure.get() != null) {
                throw new AssertionError("Unable to read JS client output", outputFailure.get());
            }

            assertEquals(0, process.exitValue(),
                    String.format("JS client process exited with non-zero status %d (v%s, %s, scenario=%s, port=%d).\nOutput logs:\n%s",
                            process.exitValue(), version, transport, scenario, getServerPort(), getOutput(output)));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String getOutput(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Connect Scenario")
    @MethodSource("clientTransports")
    public void testJsConnect(String version, String transport) throws Exception {
        AtomicBoolean connected = new AtomicBoolean(false);
        com.socketio4j.socketio.listener.ConnectListener listener = client -> connected.set(true);
        getServer().addConnectListener(listener);
        try {
            runJsTest(version, transport, "connect");
            assertTrue(connected.get(), "Server ConnectListener should have been invoked for client connection");
        } finally {
            getServer().removeConnectListener(listener);
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Text Messaging & Response")
    @MethodSource("clientTransports")
    public void testJsTextMessaging(String version, String transport) throws Exception {
        AtomicBoolean received = new AtomicBoolean(false);
        AtomicReference<String> clientReceived = new AtomicReference<>();
        getServer().addEventListener("testText", String.class, (client, data, ackRequest) -> {
            received.set(true);
            client.sendEvent("textResponse", "hello from server");
        });
        getServer().addEventListener("clientTextResponse", String.class, (client, data, ackRequest) -> {
            clientReceived.set(data);
        });

        try {
            runJsTest(version, transport, "text");
            assertTrue(received.get(), "Server should have received testText event");
            assertEquals("hello from server", clientReceived.get(), "Server verified: JS client received exact text response");
        } finally {
            getServer().removeAllListeners("testText");
            getServer().removeAllListeners("clientTextResponse");
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Client Event Text ACK")
    @MethodSource("clientTransports")
    public void testJsEventAck(String version, String transport) throws Exception {
        AtomicBoolean received = new AtomicBoolean(false);
        AtomicReference<String> clientAckData = new AtomicReference<>();
        getServer().addEventListener("testAck", String.class, (client, data, ackRequest) -> {
            received.set(true);
            ackRequest.sendAckData("ack_reply_" + data);
        });
        getServer().addEventListener("clientAckResponse", String.class, (client, data, ackRequest) -> {
            clientAckData.set(data);
        });

        try {
            runJsTest(version, transport, "ack");
            assertTrue(received.get(), "Server should have received testAck event");
            assertEquals("ack_reply_ping_ack_data", clientAckData.get(), "Server verified: JS client received expected ACK data");
        } finally {
            getServer().removeAllListeners("testAck");
            getServer().removeAllListeners("clientAckResponse");
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Client Event Binary ACK")
    @MethodSource("clientTransports")
    public void testJsEventAckBinary(String version, String transport) throws Exception {
        AtomicBoolean received = new AtomicBoolean(false);
        AtomicReference<byte[]> clientAckData = new AtomicReference<>();
        getServer().addEventListener("testAckBinary", String.class, (client, data, ackRequest) -> {
            received.set(true);
            ackRequest.sendAckData(new byte[] { 50, 51, 52 });
        });
        getServer().addEventListener("clientAckBinaryResponse", byte[].class, (client, data, ackRequest) -> {
            clientAckData.set(data);
        });

        try {
            runJsTest(version, transport, "ack_binary");
            assertTrue(received.get(), "Server should have received testAckBinary event");
            assertArrayEquals(new byte[] { 50, 51, 52 }, clientAckData.get(), "Server verified: JS client received expected binary ACK");
        } finally {
            getServer().removeAllListeners("testAckBinary");
            getServer().removeAllListeners("clientAckBinaryResponse");
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Server-Initiated Text ACK Callback")
    @MethodSource("clientTransports")
    public void testJsServerInitiatedAckText(String version, String transport) throws Exception {
        AtomicReference<String> ackReply = new AtomicReference<>();
        CountDownLatch ackLatch = new CountDownLatch(1);

        com.socketio4j.socketio.listener.ConnectListener listener = client -> {
            client.sendEvent("serverReqAckText", new com.socketio4j.socketio.AckCallback<String>(String.class, 5) {
                @Override
                public void onSuccess(String result) {
                    ackReply.set(result);
                    ackLatch.countDown();
                }
            }, "hello_from_server");
        };

        getServer().addConnectListener(listener);
        try {
            runJsTest(version, transport, "server_ack_text");
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for text ACK reply from JS client callback");
            assertEquals("js_ack_text_reply", ackReply.get(), "Server should receive text ACK reply from JS client callback");
        } finally {
            getServer().removeConnectListener(listener);
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Server-Initiated Binary ACK Callback")
    @MethodSource("clientTransports")
    public void testJsServerInitiatedAckBinary(String version, String transport) throws Exception {
        AtomicReference<byte[]> ackReply = new AtomicReference<>();
        CountDownLatch ackLatch = new CountDownLatch(1);

        com.socketio4j.socketio.listener.ConnectListener listener = client -> {
            client.sendEvent("serverReqAckBinary", new com.socketio4j.socketio.AckCallback<byte[]>(byte[].class, 5) {
                @Override
                public void onSuccess(byte[] result) {
                    ackReply.set(result);
                    ackLatch.countDown();
                }
            }, "hello_for_binary_ack");
        };

        getServer().addConnectListener(listener);
        try {
            runJsTest(version, transport, "server_ack_binary");
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for binary ACK reply from JS client callback");
            assertArrayEquals(new byte[] { 55, 66, 77 }, ackReply.get(), "Server should receive binary ACK reply from JS client callback");
        } finally {
            getServer().removeConnectListener(listener);
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Server-Initiated Void ACK Callback")
    @MethodSource("clientTransports")
    public void testJsServerInitiatedVoidAck(String version, String transport) throws Exception {
        AtomicBoolean voidAckReceived = new AtomicBoolean(false);
        CountDownLatch ackLatch = new CountDownLatch(1);

        com.socketio4j.socketio.listener.ConnectListener listener = client -> {
            client.sendEvent("serverReqVoidAck", new com.socketio4j.socketio.VoidAckCallback(5) {
                @Override
                protected void onSuccess() {
                    voidAckReceived.set(true);
                    ackLatch.countDown();
                }
            }, "hello_void");
        };

        getServer().addConnectListener(listener);
        try {
            runJsTest(version, transport, "server_ack_void");
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for Void ACK callback from JS client");
            assertTrue(voidAckReceived.get(), "Server should receive Void ACK callback from JS client");
        } finally {
            getServer().removeConnectListener(listener);
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Server-Initiated MultiType ACK Callback")
    @MethodSource("clientTransports")
    public void testJsServerInitiatedMultiTypeAck(String version, String transport) throws Exception {
        AtomicReference<String> stringReply = new AtomicReference<>();
        AtomicReference<byte[]> binaryReply = new AtomicReference<>();
        CountDownLatch ackLatch = new CountDownLatch(1);

        com.socketio4j.socketio.listener.ConnectListener listener = client -> {
            client.sendEvent("serverReqMultiAck", new com.socketio4j.socketio.MultiTypeAckCallback(String.class, byte[].class) {
                @Override
                public void onSuccess(com.socketio4j.socketio.MultiTypeArgs res) {
                    stringReply.set(res.get(0));
                    binaryReply.set(res.get(1));
                    ackLatch.countDown();
                }
            }, "hello_multi");
        };

        getServer().addConnectListener(listener);
        try {
            runJsTest(version, transport, "server_ack_multi");
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for MultiType ACK callback from JS client");
            assertEquals("reply_string", stringReply.get(), "Server should receive first MultiType ACK arg");
            assertArrayEquals(new byte[] { 88, 99 }, binaryReply.get(), "Server should receive second MultiType ACK arg");
        } finally {
            getServer().removeConnectListener(listener);
        }
    }
    @ParameterizedTest(name = "Client v{0} over {1} - Server Batch Text/Binary/Text")
    @MethodSource("clientPollingTransports")
    public void testJsServerBatchTextBinaryText(String version, String transport) throws Exception {
        java.util.List<String> clientSequence = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        getServer().addEventListener("clientBatchDone", String.class, (client, sequence, ackSender) -> {
            clientSequence.addAll(java.util.Arrays.asList(sequence.split(",")));
        });

        com.socketio4j.socketio.listener.ConnectListener connectListener = client -> {
            client.sendEvent("batchText1", "TEXT1");
            client.sendEvent("batchBinary", new byte[] {1, 2, 3, 4, 5});
            client.sendEvent("batchText2", "TEXT2");
        };

        getServer().addConnectListener(connectListener);
        try {
            runJsTest(version, transport, "server_batch_text_binary_text");
            assertEquals(java.util.Arrays.asList("TEXT1", "BIN", "TEXT2"), clientSequence,
                    "Server verified: JS client received batched packets in strict order [TEXT1, BIN, TEXT2]");
        } finally {
            getServer().removeConnectListener(connectListener);
            getServer().removeAllListeners("clientBatchDone");
        }
    }
    @ParameterizedTest(name = "Client v{0} over {1} - Binary Payload (byte[])")
    @MethodSource("clientTransports")
    public void testJsBinaryPayload(String version, String transport) throws Exception {
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<byte[]> clientReceivedData = new AtomicReference<>();

        getServer().addEventListener("testBinary", byte[].class, (client, data, ackRequest) -> {
            receivedData.set(data);
            client.sendEvent("binaryResponse", new byte[] { 100, 101, 102 });
        });

        getServer().addEventListener("clientBinaryResponse", byte[].class, (client, data, ackRequest) -> {
            clientReceivedData.set(data);
        });

        try {
            runJsTest(version, transport, "binary");
            assertArrayEquals(new byte[] { 10, 20, 30, 40, 50 }, receivedData.get(),
                    "Server should receive intact binary payload");
            assertArrayEquals(new byte[] { 100, 101, 102 }, clientReceivedData.get(),
                    "Server verified: JS client received intact binary response");
        } finally {
            getServer().removeAllListeners("testBinary");
            getServer().removeAllListeners("clientBinaryResponse");
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Multiple Binary Attachments")
    @MethodSource("clientTransports")
    public void testJsMultiBinaryAttachments(String version, String transport) throws Exception {
        AtomicReference<byte[]> attachment1 = new AtomicReference<>();
        AtomicReference<byte[]> attachment2 = new AtomicReference<>();
        AtomicReference<byte[]> clientReceivedData = new AtomicReference<>();

        getServer().addMultiTypeEventListener("testMultiBinary", (client, data, ackRequest) -> {
            byte[] buf1 = data.get(0);
            byte[] buf2 = data.get(1);
            attachment1.set(buf1);
            attachment2.set(buf2);
            client.sendEvent("binaryResponse", new byte[] { 100, 101, 102 });
        }, byte[].class, byte[].class);

        getServer().addEventListener("clientBinaryResponse", byte[].class, (client, data, ackRequest) -> {
            clientReceivedData.set(data);
        });

        try {
            runJsTest(version, transport, "multi_binary");
            assertArrayEquals(new byte[] { 1, 2, 3 }, attachment1.get(),
                    "Server should receive first binary attachment intact");
            assertArrayEquals(new byte[] { 4, 5, 6 }, attachment2.get(),
                    "Server should receive second binary attachment intact");
            assertArrayEquals(new byte[] { 100, 101, 102 }, clientReceivedData.get(),
                    "Server verified: JS client received binary response");
        } finally {
            getServer().removeAllListeners("testMultiBinary");
            getServer().removeAllListeners("clientBinaryResponse");
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Map/Generic Object")
    @MethodSource("clientTransports")
    @SuppressWarnings("unchecked")
    public void testJsMapObject(String version, String transport) throws Exception {
        AtomicReference<String> receivedName = new AtomicReference<>();
        AtomicReference<Integer> receivedValue = new AtomicReference<>();
        AtomicReference<java.util.Map<String, Object>> clientReceivedObj = new AtomicReference<>();

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

        getServer().addEventListener("clientObjectResponse", Object.class, (client, data, ackRequest) -> {
            clientReceivedObj.set((java.util.Map<String, Object>) data);
        });

        try {
            runJsTest(version, transport, "object");
            assertEquals("hello", receivedName.get(), "Server should receive the name field from JS object");
            assertEquals(42, receivedValue.get(), "Server should receive the value field from JS object");
            assertNotNull(clientReceivedObj.get(), "JS client must emit object response back to server");
            assertEquals("hello", clientReceivedObj.get().get("echo"), "Server verified: JS client received echo");
            assertEquals(84, ((Number) clientReceivedObj.get().get("doubled")).intValue(), "Server verified: JS client received doubled");
        } finally {
            getServer().removeAllListeners("testObject");
            getServer().removeAllListeners("clientObjectResponse");
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Custom Typed Java POJO Object")
    @MethodSource("clientTransports")
    public void testJsCustomPojo(String version, String transport) throws Exception {
        AtomicReference<Payload> receivedPayload = new AtomicReference<>();
        AtomicReference<ObjectResponse> clientReceivedPojo = new AtomicReference<>();

        getServer().addEventListener("testPojo", Payload.class, (client, data, ackRequest) -> {
            receivedPayload.set(data);
            ObjectResponse response = new ObjectResponse(data.getName(), data.getValue() * 2);
            client.sendEvent("pojoResponse", response);
        });

        getServer().addEventListener("clientPojoResponse", ObjectResponse.class, (client, data, ackRequest) -> {
            clientReceivedPojo.set(data);
        });

        try {
            runJsTest(version, transport, "pojo");
            assertNotNull(receivedPayload.get(), "Server should deserialize into custom POJO");
            assertEquals("hello", receivedPayload.get().getName(), "Server should deserialize name getter");
            assertEquals(42, receivedPayload.get().getValue(), "Server should deserialize value getter");
            assertNotNull(clientReceivedPojo.get(), "JS client must emit POJO response back to server");
            assertEquals("hello", clientReceivedPojo.get().getEcho(), "Server verified: JS client received POJO echo");
            assertEquals(84, clientReceivedPojo.get().getDoubled(), "Server verified: JS client received POJO doubled");
        } finally {
            getServer().removeAllListeners("testPojo");
            getServer().removeAllListeners("clientPojoResponse");
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Mixed String + Binary Args")
    @MethodSource("clientTransports")
    public void testJsMixedArgs(String version, String transport) throws Exception {
        AtomicReference<String> receivedText = new AtomicReference<>();
        AtomicReference<byte[]> receivedBytes = new AtomicReference<>();
        AtomicReference<String> clientText = new AtomicReference<>();
        AtomicReference<byte[]> clientBytes = new AtomicReference<>();

        getServer().addMultiTypeEventListener("testMixed", (client, data, ackRequest) -> {
            String text = data.get(0);
            byte[] bytes = data.get(1);
            receivedText.set(text);
            receivedBytes.set(bytes);
            client.sendEvent("mixedResponse", text + "_reply", bytes);
        }, String.class, byte[].class);

        getServer().addMultiTypeEventListener("clientMixedResponse", (client, data, ackRequest) -> {
            clientText.set(data.get(0));
            clientBytes.set(data.get(1));
        }, String.class, byte[].class);

        try {
            runJsTest(version, transport, "mixed");
            assertEquals("hello_text", receivedText.get(), "Server should receive the String argument");
            assertArrayEquals(new byte[] { 7, 8, 9 }, receivedBytes.get(),
                    "Server should receive the binary argument intact");
            assertEquals("hello_text_reply", clientText.get(), "Server verified: JS client received mixed text response");
            assertArrayEquals(new byte[] { 7, 8, 9 }, clientBytes.get(), "Server verified: JS client received mixed binary response");
        } finally {
            getServer().removeAllListeners("testMixed");
            getServer().removeAllListeners("clientMixedResponse");
        }
    }

    @ParameterizedTest(name = "Client v{0} over {1} - Real-Life Multi-Level Complex POJO")
    @MethodSource("clientTransports")
    public void testJsComplexCustomPojo(String version, String transport) throws Exception {
        AtomicReference<OrderPayload> receivedOrder = new AtomicReference<>();
        AtomicReference<OrderResponse> clientReceivedOrder = new AtomicReference<>();

        getServer().addEventListener("testComplexPojo", OrderPayload.class, (client, data, ackRequest) -> {
            receivedOrder.set(data);
            OrderResponse response = new OrderResponse(
                    data.getOrderId(),
                    "PROCESSED",
                    data.getItems() != null ? data.getItems().size() : 0,
                    data.getCustomer() != null ? data.getCustomer().getEmail() : null
            );
            client.sendEvent("complexPojoResponse", response);
        });

        getServer().addEventListener("clientComplexPojoResponse", OrderResponse.class, (client, data, ackRequest) -> {
            clientReceivedOrder.set(data);
        });

        try {
            runJsTest(version, transport, "complex_pojo");

            OrderPayload order = receivedOrder.get();
            assertNotNull(order, "Server should deserialize multi-level complex order payload");
            assertEquals("ORD-98765", order.getOrderId());
            assertEquals(149.98, order.getTotalAmount(), 0.001);

            assertNotNull(order.getCustomer(), "Order customer should be deserialized");
            assertEquals("CUST-001", order.getCustomer().getCustomerId());
            assertEquals("alice@example.com", order.getCustomer().getEmail());
            assertTrue(order.getCustomer().isVipStatus());

            assertNotNull(order.getItems(), "Order items list should be deserialized");
            assertEquals(2, order.getItems().size());
            assertEquals("ITEM-A", order.getItems().get(0).getSku());
            assertEquals(2, order.getItems().get(0).getQuantity());
            assertEquals(49.99, order.getItems().get(0).getUnitPrice(), 0.001);

            assertNotNull(order.getMetadata(), "Order metadata map should be deserialized");
            assertEquals("mobile_app", order.getMetadata().get("source"));

            OrderResponse clientResp = clientReceivedOrder.get();
            assertNotNull(clientResp, "Server verified: JS client received complex POJO response");
            assertEquals("ORD-98765", clientResp.getOrderId());
            assertEquals("PROCESSED", clientResp.getStatus());
            assertEquals(2, clientResp.getProcessedItemCount());
            assertEquals("alice@example.com", clientResp.getCustomerEmail());
        } finally {
            getServer().removeAllListeners("testComplexPojo");
            getServer().removeAllListeners("clientComplexPojoResponse");
        }
    }

    // ---------------------------------------------------------------------------
    // Top-level Payload and ObjectResponse classes are used for Jackson JPMS compatibility


    public static class OrderPayload {
        @JsonProperty("orderId")
        private String orderId;
        @JsonProperty("totalAmount")
        private double totalAmount;
        @JsonProperty("customer")
        private Customer customer;
        @JsonProperty("items")
        private java.util.List<OrderItem> items;
        @JsonProperty("metadata")
        private java.util.Map<String, String> metadata;

        public OrderPayload() {}
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public double getTotalAmount() { return totalAmount; }
        public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }
        public Customer getCustomer() { return customer; }
        public void setCustomer(Customer customer) { this.customer = customer; }
        public java.util.List<OrderItem> getItems() { return items; }
        public void setItems(java.util.List<OrderItem> items) { this.items = items; }
        public java.util.Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(java.util.Map<String, String> metadata) { this.metadata = metadata; }
    }

    public static class Customer {
        @JsonProperty("customerId")
        private String customerId;
        @JsonProperty("email")
        private String email;
        @JsonProperty("vipStatus")
        private boolean vipStatus;

        public Customer() {}
        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public boolean isVipStatus() { return vipStatus; }
        public void setVipStatus(boolean vipStatus) { this.vipStatus = vipStatus; }
    }

    public static class OrderItem {
        @JsonProperty("sku")
        private String sku;
        @JsonProperty("quantity")
        private int quantity;
        @JsonProperty("unitPrice")
        private double unitPrice;

        public OrderItem() {}
        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public double getUnitPrice() { return unitPrice; }
        public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
    }

    public static class OrderResponse {
        @JsonProperty("orderId")
        private String orderId;
        @JsonProperty("status")
        private String status;
        @JsonProperty("processedItemCount")
        private int processedItemCount;
        @JsonProperty("customerEmail")
        private String customerEmail;

        public OrderResponse() {}
        public OrderResponse(String orderId, String status, int processedItemCount, String customerEmail) {
            this.orderId = orderId;
            this.status = status;
            this.processedItemCount = processedItemCount;
            this.customerEmail = customerEmail;
        }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getProcessedItemCount() { return processedItemCount; }
        public void setProcessedItemCount(int processedItemCount) { this.processedItemCount = processedItemCount; }
        public String getCustomerEmail() { return customerEmail; }
        public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    }

    @ParameterizedTest(name = "[ROOM-001] Client v{0} over {1} - Join Single Room")
    @MethodSource("clientTransports")
    void testJoinSingleRoom(String version, String transport) throws Exception {

        AtomicBoolean joined = new AtomicBoolean(false);

        getServer().addEventListener("joinRoom", String.class,
                (client, room, ackSender) -> {

                    client.joinRoom(room);
                    joined.set(true);

                    getServer()
                            .getRoomOperations(room)
                            .sendEvent("roomMessage", "hello room");
                });

        runJsTest(version, transport, "join_room");

        assertTrue(joined.get());
    }
    @ParameterizedTest(name = "[ROOM-002] Client v{0} over {1} - Leave Room")
    @MethodSource("clientTransports")
    void testLeaveRoom(String version, String transport) throws Exception {

        AtomicBoolean joined = new AtomicBoolean(false);
        AtomicBoolean left = new AtomicBoolean(false);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        getServer().addEventListener("joinLeaveRoom", String.class,
                (client, room, ackSender) -> {

                    client.joinRoom(room);
                    joined.set(true);

                    client.leaveRoom(room);
                    left.set(true);

                    // This should NOT reach the client.
                    getServer()
                            .getRoomOperations(room)
                            .sendEvent("roomMessage", "should_not_receive");

                    // Give the client time to receive (or not receive) the room broadcast.


                    scheduler.schedule(() -> {
                        client.sendEvent("done");
                    }, 500, TimeUnit.MILLISECONDS);
                });

        try {
            runJsTest(version, transport, "leave_room");

            assertTrue(joined.get());
            assertTrue(left.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @ParameterizedTest(name = "[ROOM-003] Client v{0} over {1} - Join Same Room Twice")
    @MethodSource("clientTransports")
    void testJoinSameRoomTwice(String version, String transport) throws Exception {

        AtomicInteger joinCount = new AtomicInteger();

        getServer().addEventListener("joinSameRoomTwice", String.class,
                (client, room, ackSender) -> {

                    client.joinRoom(room);
                    joinCount.incrementAndGet();

                    // Join again
                    client.joinRoom(room);
                    joinCount.incrementAndGet();

                    getServer()
                            .getRoomOperations(room)
                            .sendEvent("roomMessage", "hello room");
                });

        runJsTest(version, transport, "join_same_room_twice");

        assertEquals(2, joinCount.get(),
                "Server should execute both joinRoom() calls");
    }
    @ParameterizedTest(name = "[ROOM-004] Client v{0} over {1} - Leave Room Not Joined")
    @MethodSource("clientTransports")
    void testLeaveRoomNotJoined(String version, String transport) throws Exception {

        AtomicBoolean handlerInvoked = new AtomicBoolean();

        getServer().addEventListener("leaveUnknownRoom", String.class,
                (client, room, ackSender) -> {

                    // Join only roomA
                    client.joinRoom("roomA");

                    // Attempt to leave roomB (never joined)
                    client.leaveRoom("roomB");

                    handlerInvoked.set(true);

                    // Client should still be in roomA
                    getServer()
                            .getRoomOperations("roomA")
                            .sendEvent("roomMessage", "hello_roomA");
                });

        runJsTest(version, transport, "leave_unknown_room");

        assertTrue(handlerInvoked.get(),
                "Server handler should have been invoked");
    }

    @ParameterizedTest(name = "[ROOM-005] Client v{0} over {1} - Join Multiple Rooms")
    @MethodSource("clientTransports")
    void testJoinMultipleRooms(String version, String transport) throws Exception {

        AtomicBoolean joinedRoomA = new AtomicBoolean();
        AtomicBoolean joinedRoomB = new AtomicBoolean();
        AtomicReference<Set<String>> rooms = new AtomicReference<>();
        getServer().addEventListener("joinMultipleRooms", String.class,
                (client, ignored, ackSender) -> {

                    client.joinRoom("roomA");
                    if (client.getAllRooms().contains("roomA")) {
                        joinedRoomA.set(true);
                    }

                    client.joinRoom("roomB");
                    if (client.getAllRooms().contains("roomB")) {
                        joinedRoomB.set(true);
                    }

                    rooms.set(new HashSet<>(client.getAllRooms()));
                    getServer()
                            .getRoomOperations("roomA")
                            .sendEvent("roomAMessage", "hello_roomA");

                    getServer()
                            .getRoomOperations("roomB")
                            .sendEvent("roomBMessage", "hello_roomB");
                });

        runJsTest(version, transport, "join_multiple_rooms");

        assertTrue(joinedRoomA.get(), "Client should join roomA");
        assertTrue(joinedRoomB.get(), "Client should join roomB");
        assertNotNull(rooms.get());
        assertTrue(rooms.get().contains("roomA"), "Client should be in roomA");
        assertTrue(rooms.get().contains("roomB"), "Client should be in roomB");
    }
    @ParameterizedTest(name = "[ROOM-006] Client v{0} over {1} - Leave One of Multiple Rooms")
    @MethodSource("clientTransports")
    void testLeaveOneOfMultipleRooms(String version, String transport) throws Exception {

        AtomicReference<Set<String>> rooms = new AtomicReference<>();

        getServer().addEventListener("leaveOneRoom", String.class,
                (client, ignored, ackSender) -> {

                    client.joinRoom("roomA");
                    client.joinRoom("roomB");

                    client.leaveRoom("roomA");

                    rooms.set(new HashSet<>(client.getAllRooms()));

                    getServer()
                            .getRoomOperations("roomA")
                            .sendEvent("roomAMessage", "should_not_receive");

                    getServer()
                            .getRoomOperations("roomB")
                            .sendEvent("roomBMessage", "hello_roomB");
                });

        runJsTest(version, transport, "leave_one_room");

        assertNotNull(rooms.get());

        assertFalse(rooms.get().contains("roomA"),
                "Client should have left roomA");

        assertTrue(rooms.get().contains("roomB"),
                "Client should still be in roomB");

    }

    @ParameterizedTest(name = "[ROOM-007] Client v{0} over {1} - Leave All Rooms")
    @MethodSource("clientTransports")
    void testLeaveAllRooms(String version, String transport) throws Exception {

        AtomicReference<Set<String>> rooms = new AtomicReference<>();

        getServer().addEventListener("leaveAllRooms", String.class,
                (client, ignored, ackSender) -> {

                    client.joinRoom("roomA");
                    client.joinRoom("roomB");
                    client.joinRoom("roomC");

                    client.leaveRoom("roomA");
                    client.leaveRoom("roomB");
                    client.leaveRoom("roomC");

                    rooms.set(new HashSet<>(client.getAllRooms()));

                    getServer().getRoomOperations("roomA")
                            .sendEvent("roomAMessage", "A");

                    getServer().getRoomOperations("roomB")
                            .sendEvent("roomBMessage", "B");

                    getServer().getRoomOperations("roomC")
                            .sendEvent("roomCMessage", "C");
                });

        runJsTest(version, transport, "leave_all_rooms");

        assertNotNull(rooms.get());

        assertFalse(rooms.get().contains("roomA"));
        assertFalse(rooms.get().contains("roomB"));
        assertFalse(rooms.get().contains("roomC"));
    }
    @ParameterizedTest(name = "[ROOM-008] Client v{0} over {1} - Auto Remove From Rooms On Disconnect")
    @MethodSource("clientTransports")
    void testAutoRemoveRoomsOnDisconnect(String version, String transport) throws Exception {

        AtomicReference<Set<String>> roomsBeforeDisconnect = new AtomicReference<>();
        AtomicBoolean disconnectListenerInvoked = new AtomicBoolean();

        getServer().addEventListener("joinAndDisconnect", String.class,
                (client, ignored, ackSender) -> {

                    client.joinRoom("roomA");
                    client.joinRoom("roomB");

                    roomsBeforeDisconnect.set(new HashSet<>(client.getAllRooms()));

                    // Ask JS client to disconnect.
                    client.sendEvent("disconnectNow");
                });

        getServer().addDisconnectListener(client -> {
            disconnectListenerInvoked.set(true);

            // Broadcast after disconnect.
            // Client must not receive these.
            getServer().getRoomOperations("roomA")
                    .sendEvent("roomAMessage", "A");

            getServer().getRoomOperations("roomB")
                    .sendEvent("roomBMessage", "B");
        });

        runJsTest(version, transport, "disconnect_rooms");

        assertNotNull(roomsBeforeDisconnect.get());

        assertTrue(roomsBeforeDisconnect.get().contains("roomA"));
        assertTrue(roomsBeforeDisconnect.get().contains("roomB"));

        assertTrue(disconnectListenerInvoked.get(),
                "DisconnectListener should have been invoked");
    }

}
