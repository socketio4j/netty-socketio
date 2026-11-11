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
package com.socketio4j.socketio.protocol;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.socketio4j.socketio.AckCallback;
import com.socketio4j.socketio.MultiTypeAckCallback;
import com.socketio4j.socketio.MultiTypeArgs;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real JSON serialization, event mapping and failure cleanup contracts
 */
public class JsonSupportTest extends BaseProtocolTest {

    private final JacksonJsonSupport jsonSupport = new JacksonJsonSupport();

    private <T> T read(String namespace, String json, Class<T> type) throws IOException {
        ByteBuf buffer = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
        try (ByteBufInputStream in = new ByteBufInputStream(buffer)) {
            return jsonSupport.readValue(namespace, in, type);
        } finally {
            buffer.release();
        }
    }

    private String write(Object value) throws IOException {
        ByteBuf buffer = Unpooled.buffer();
        try (ByteBufOutputStream out = new ByteBufOutputStream(buffer)) {
            jsonSupport.writeValue(out, value);
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void testReadAckArgs() throws IOException {
        ByteBuf buf = Unpooled.copiedBuffer("[\"arg1\",\"arg2\"]", StandardCharsets.UTF_8);
        try (ByteBufInputStream inputStream = new ByteBufInputStream(buf)) {
            AckCallback<String> ackCallback = new AckCallback<String>(String.class) {
                @Override
                public void onSuccess(String result) {
                }
            };
            AckArgs result = jsonSupport.readAckArgs(inputStream, ackCallback);
            assertNotNull(result);
            assertEquals(Arrays.asList("arg1", "arg2"), result.getArgs());
        } finally {
            buf.release();
        }
    }

    @Test
    public void testReadValue() throws IOException {
        ByteBuf buf = Unpooled.copiedBuffer("\"testValue\"", StandardCharsets.UTF_8);
        try (ByteBufInputStream inputStream = new ByteBufInputStream(buf)) {
            String result = jsonSupport.readValue("testNamespace", inputStream, String.class);
            assertEquals("testValue", result);
        } finally {
            buf.release();
        }
    }

    @Test
    public void testWriteValue() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try (ByteBufOutputStream outputStream = new ByteBufOutputStream(buf)) {
            jsonSupport.writeValue(outputStream, "testValue");
            assertEquals("\"testValue\"", buf.toString(StandardCharsets.UTF_8));
        } finally {
            buf.release();
        }
    }

    @Test
    public void testAddEventMapping() throws IOException {
        String namespaceName = "/testNamespace";
        String eventName = "testEvent";
        jsonSupport.addEventMapping(namespaceName, eventName, String.class);
        Event event = read(namespaceName, "[\"testEvent\",\"payloadData\"]", Event.class);
        assertNotNull(event);
        assertEquals(eventName, event.getName());
        assertEquals(Arrays.asList("payloadData"), event.getArgs());
    }

    @Test
    public void testAddEventMappingWithMultipleClasses() throws IOException {
        String namespaceName = "/testNamespace";
        String eventName = "testEvent";
        jsonSupport.addEventMapping(namespaceName, eventName, String.class, Integer.class);
        Event event = read(namespaceName, "[\"testEvent\",\"payloadData\",123]", Event.class);
        assertNotNull(event);
        assertEquals(eventName, event.getName());
        assertEquals(Arrays.asList("payloadData", 123), event.getArgs());
    }

    @Test
    public void testRemoveEventMapping() throws IOException {
        String namespaceName = "/testNamespace";
        String eventName = "testEvent";
        jsonSupport.addEventMapping(namespaceName, eventName, Integer.class);
        Event before = read(namespaceName, "[\"testEvent\",42]", Event.class);
        assertEquals(Arrays.asList(42), before.getArgs());

        jsonSupport.removeEventMapping(namespaceName, eventName);
        Event after = read(namespaceName, "[\"testEvent\",\"genericValue\"]", Event.class);
        assertEquals(eventName, after.getName());
        assertEquals(Arrays.asList("genericValue"), after.getArgs());
    }

    @Test
    public void testGetArrays() throws IOException {
        byte[] array1 = "array1".getBytes(StandardCharsets.UTF_8);
        byte[] array2 = "array2".getBytes(StandardCharsets.UTF_8);
        write(Arrays.asList(array1, array2));

        List<byte[]> result = jsonSupport.getArrays();
        assertNotNull(result);
        assertEquals(2, result.size());
        assertArrayEquals(array1, result.get(0));
        assertArrayEquals(array2, result.get(1));
    }

    @Test
    public void testGetArraysReturnsEmptyList() throws IOException {
        write("nonBinaryPayload");
        List<byte[]> result = jsonSupport.getArrays();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testReadValueWithDifferentTypes() throws IOException {
        String str = read("ns", "\"stringValue\"", String.class);
        assertEquals("stringValue", str);

        Integer num = read("ns", "42", Integer.class);
        assertEquals(Integer.valueOf(42), num);

        Boolean bool = read("ns", "true", Boolean.class);
        assertEquals(Boolean.TRUE, bool);

        Map<?, ?> map = read("ns", "{\"key\":\"val\"}", Map.class);
        assertNotNull(map);
        assertEquals("val", map.get("key"));
    }

    @Test
    public void testWriteValueWithDifferentTypes() throws IOException {
        assertEquals("\"stringValue\"", write("stringValue"));
        assertEquals("42", write(42));
        assertEquals("true", write(true));
        assertEquals("[\"a\",\"b\"]", write(Arrays.asList("a", "b")));
    }

    @Test
    public void testAddEventMappingWithNullParameters() {
        jsonSupport.addEventMapping(null, null, (Class<?>) null);
        jsonSupport.addEventMapping(null, "eventWithNullNs", String.class);
        jsonSupport.addEventMapping("/ns", null, Integer.class);
    }

    @Test
    public void testRemoveEventMappingWithNullParameters() {
        jsonSupport.removeEventMapping(null, null);
        jsonSupport.removeEventMapping(null, "event");
        jsonSupport.removeEventMapping("/ns", null);
    }

    @Test
    public void testReadValueWithNullNamespace() throws IOException {
        String result = read(null, "\"testValue\"", String.class);
        assertEquals("testValue", result);
    }

    @Test
    public void testWriteValueWithNullValue() throws IOException {
        assertEquals("null", write(null));
    }

    @Test
    public void testGetArraysReturnsNull() {
        assertNotNull(jsonSupport.getArrays(), "JacksonJsonSupport returns non-null list");
        JsonSupport customJson = Mockito.mock(JsonSupport.class);
        Mockito.when(customJson.getArrays()).thenReturn(null);
        assertNull(customJson.getArrays(), "Custom JsonSupport contract permits returning null");
    }

    @Test
    public void testMultipleEventMappings() throws IOException {
        String namespace1 = "/ns1";
        String namespace2 = "/ns2";
        String event1 = "event1";
        String event2 = "event2";

        jsonSupport.addEventMapping(namespace1, event1, String.class);
        jsonSupport.addEventMapping(namespace2, event2, Integer.class);

        Event e1 = read(namespace1, "[\"event1\",\"hello\"]", Event.class);
        assertEquals(Arrays.asList("hello"), e1.getArgs());

        Event e2 = read(namespace2, "[\"event2\",42]", Event.class);
        assertEquals(Arrays.asList(42), e2.getArgs());
    }

    @Test
    public void testMultipleEventRemovals() throws IOException {
        String namespace1 = "/ns1";
        String namespace2 = "/ns2";
        String event1 = "event1";
        String event2 = "event2";

        jsonSupport.addEventMapping(namespace1, event1, String.class);
        jsonSupport.addEventMapping(namespace2, event2, Integer.class);

        jsonSupport.removeEventMapping(namespace1, event1);
        jsonSupport.removeEventMapping(namespace2, event2);

        Event e1 = read(namespace1, "[\"event1\",\"untyped1\"]", Event.class);
        assertEquals(Arrays.asList("untyped1"), e1.getArgs());

        Event e2 = read(namespace2, "[\"event2\",\"untyped2\"]", Event.class);
        assertEquals(Arrays.asList("untyped2"), e2.getArgs());
    }

    @Test
    void scalarAndNullValuesUseRealJsonEncoding() throws IOException {
        assertEquals("\"hello\\nதமிழ்\"", write("hello\nதமிழ்"));
        assertEquals("42", write(42));
        assertEquals("true", write(true));
        assertEquals("null", write(null));
        assertEquals("hello\nதமிழ்", read(null, "\"hello\\nதமிழ்\"", String.class));
        assertEquals(Integer.valueOf(42), read("/chat", "42", Integer.class));
        assertEquals(Boolean.TRUE, read("/chat", "true", Boolean.class));
        assertNull(read("/chat", "null", String.class));
    }

    @Test
    void mappingsAreScopedByNamespaceAndRemovalRestoresUntypedDecoding() throws IOException {
        jsonSupport.addEventMapping("/a", "value", Integer.class);
        jsonSupport.addEventMapping("/b", "value", String.class);
        assertEquals(Arrays.asList(42), read("/a", "[\"value\",42]", Event.class).getArgs());
        assertEquals(Arrays.asList("text"), read("/b", "[\"value\",\"text\"]", Event.class).getArgs());
        jsonSupport.removeEventMapping("/a", "value");
        assertEquals(Arrays.asList("now untyped"),
                read("/a", "[\"value\",\"now untyped\"]", Event.class).getArgs());
        assertEquals(Arrays.asList("still scoped"),
                read("/b", "[\"value\",\"still scoped\"]", Event.class).getArgs());
    }

    @Test
    void mapsMultipleArgumentsAndRejectsWrongMappedShape() throws IOException {
        jsonSupport.addEventMapping("/chat", "pair", String.class, Integer.class);
        Event event = read("/chat", "[\"pair\",\"one\",2]", Event.class);
        assertEquals("pair", event.getName());
        assertEquals(Arrays.asList("one", 2), event.getArgs());
        assertThrows(IOException.class,
                () -> read("/chat", "[\"pair\",\"one\",{\"bad\":true}]", Event.class));
        assertEquals(Arrays.asList("valid", 3),
                read("/chat", "[\"pair\",\"valid\",3]", Event.class).getArgs());
    }

    @Test
    void binarySerializationExtractsExactBytesAndResetsBetweenWrites() throws IOException {
        byte[] bytes = new byte[] {0, 1, -1, 4};
        String encoded = write(Arrays.asList("binary", bytes));
        assertEquals("[\"binary\",{\"_placeholder\":true,\"num\":0}]", encoded);
        assertEquals(1, jsonSupport.getArrays().size());
        assertArrayEquals(bytes, jsonSupport.getArrays().get(0));
        assertEquals("\"next\"", write("next"));
        assertTrue(jsonSupport.getArrays().isEmpty());
    }

    private static class InspectableJacksonJsonSupport extends JacksonJsonSupport {
        String getNamespaceThreadLocal() {
            return namespaceClass.get();
        }

        AckCallback<?> getAckCallbackThreadLocal() {
            return currentAckClass.get();
        }
    }

    @Test
    public void testJacksonMultiTypeAckCallbackWithExtraClientArguments() throws IOException {
        InspectableJacksonJsonSupport realSupport = new InspectableJacksonJsonSupport();
        MultiTypeAckCallback callback = new MultiTypeAckCallback(new Class<?>[] {String.class, Integer.class}) {
            @Override
            public void onSuccess(MultiTypeArgs result) {
                // no-op
            }
        };

        ByteBuf buf = Unpooled.copiedBuffer("[\"hello\",42,\"extraArg\",999,true]", StandardCharsets.UTF_8);
        try (ByteBufInputStream in = new ByteBufInputStream(buf)) {
            AckArgs ackArgs = realSupport.readAckArgs(in, callback);
            assertNotNull(ackArgs);
            assertEquals(2, ackArgs.getArgs().size());
            assertEquals("hello", ackArgs.getArgs().get(0));
            assertEquals(Integer.valueOf(42), ackArgs.getArgs().get(1));
            assertNull(realSupport.getAckCallbackThreadLocal(),
                    "currentAckClass ThreadLocal must be cleared after readAckArgs");
        } finally {
            buf.release();
        }
    }

    @Test
    public void testJacksonAckArgsDeserializerWithNonArrayPayloadReturnsEmptyArgs() throws IOException {
        InspectableJacksonJsonSupport realSupport = new InspectableJacksonJsonSupport();
        AckCallback<String> callback = new AckCallback<String>(String.class) {
            @Override
            public void onSuccess(String result) {
                // no-op
            }
        };

        ByteBuf objBuf = Unpooled.copiedBuffer("{\"not\":\"an_array\"}", StandardCharsets.UTF_8);
        try (ByteBufInputStream in = new ByteBufInputStream(objBuf)) {
            AckArgs ackArgs = realSupport.readAckArgs(in, callback);
            assertNotNull(ackArgs);
            assertTrue(ackArgs.getArgs().isEmpty());
            assertNull(realSupport.getAckCallbackThreadLocal());
        } finally {
            objBuf.release();
        }
    }

    @Test
    public void testJacksonEventDeserializerRejectsNullAndNonStringEventNames() {
        InspectableJacksonJsonSupport realSupport = new InspectableJacksonJsonSupport();
        realSupport.addEventMapping("/chat", "msg", String.class);

        String[] invalidPayloads = new String[] {
            "[null,\"hello\"]",
            "[123,\"hello\"]",
            "[]",
            "{\"event\":\"msg\"}"
        };

        for (String payload : invalidPayloads) {
            ByteBuf buf = Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8);
            try (ByteBufInputStream in = new ByteBufInputStream(buf)) {
                assertThrows(IOException.class, () -> realSupport.readValue("/chat", in, Event.class));
                assertNull(realSupport.getNamespaceThreadLocal(),
                        "namespaceClass ThreadLocal must be cleared even when Event deserialization fails");
            } catch (IOException closeEx) {
                throw new java.io.UncheckedIOException(closeEx);
            } finally {
                buf.release();
            }
        }
    }

    @Test
    public void testJsonSupportWrapperDoesNotMaskExceptionWhenCallbackNullOrStreamUnmarked() throws Exception {
        Class<?> wrapperClass = Class.forName("com.socketio4j.socketio.JsonSupportWrapper");
        Constructor<?> ctor = wrapperClass.getDeclaredConstructor(JsonSupport.class);
        ctor.setAccessible(true);
        JsonSupport wrapper = (JsonSupport) ctor.newInstance(new JacksonJsonSupport());

        ByteBuf badBuf = Unpooled.copiedBuffer("{malformed_json", StandardCharsets.UTF_8);
        try (ByteBufInputStream in = new ByteBufInputStream(badBuf)) {
            IOException ex = assertThrows(IOException.class, () -> wrapper.readAckArgs(in, null));
            assertNotNull(ex.getCause());
        } finally {
            badBuf.release();
        }
    }
}
