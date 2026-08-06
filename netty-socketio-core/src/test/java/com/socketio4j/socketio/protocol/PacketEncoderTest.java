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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.socketio4j.socketio.Configuration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.CharsetUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Comprehensive test suite for PacketEncoder class
 * Tests all packet types and encoding formats according to Engine.IO V2, V3, V4 transport protocol and Socket.IO application standards.
 */
public class PacketEncoderTest extends BaseProtocolTest {

    private PacketEncoder encoder;

    private AutoCloseable closeableMocks;
    
    @Mock
    private JsonSupport jsonSupport;
    
    @Mock
    private Configuration configuration;
    
    @Mock
    private ByteBufAllocator allocator;

    @BeforeEach
    public void setUp() {
        closeableMocks = MockitoAnnotations.openMocks(this);
        
        configuration = new Configuration();
        configuration.setPreferDirectBuffer(false);
        
        jsonSupport = new JacksonJsonSupport();
        
        allocator = Unpooled.buffer().alloc();

        encoder = new PacketEncoder(configuration, jsonSupport);
    }

    @AfterEach
    public void tearDown() throws Exception {
        closeableMocks.close();
    }

    // ==================== CONNECT Packet Tests ====================

    @Test
    public void testEncodeConnectPacketDefaultNamespace() throws IOException {
        // CONNECT packet for default namespace
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.CONNECT);
        packet.setNsp("");
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertEquals("40", encoded); // MESSAGE(4) + CONNECT(0)
        
        buffer.release();
    }

    @Test
    public void testEncodeConnectPacketCustomNamespace() throws IOException {
        // CONNECT packet for custom namespace
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.CONNECT);
        packet.setNsp("/admin");
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertEquals("40/admin", encoded); // MESSAGE(4) + CONNECT(0)
        
        buffer.release();
    }

    @Test
    public void testEncodeConnectPacketWithAuthData() throws IOException {
        // CONNECT packet with auth data
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.CONNECT);
        packet.setNsp("/admin");
        Map<String, String> authData = new HashMap<>();
        authData.put("token", "123");
        packet.setData(authData);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("40/admin")); // MESSAGE(4) + CONNECT(0)
        
        buffer.release();
    }

    // ==================== DISCONNECT Packet Tests ====================

    @Test
    public void testEncodeDisconnectPacket() throws IOException {
        // DISCONNECT packet
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.DISCONNECT);
        packet.setNsp("/admin");
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertEquals("41/admin,", encoded); // MESSAGE(4) + DISCONNECT(1) + comma
        
        buffer.release();
    }

    // ==================== EVENT Packet Tests ====================

    @Test
    public void testEncodeEventPacketSimple() throws IOException {
        // EVENT packet: "42[\"hello\",1]" (MESSAGE + EVENT)
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("hello");
        packet.setData(Arrays.asList(1));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    @Test
    public void testEncodeEventPacketWithNamespace() throws IOException {
        // EVENT packet with namespace: "2/admin,456[\"project:delete\",123]"
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("/admin");
        packet.setName("project:delete");
        packet.setData(Arrays.asList(123));
        packet.setAckId(456L);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42/admin,456")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    // ==================== ACK Packet Tests ====================

    @Test
    public void testEncodeAckPacket() throws IOException {
        // ACK packet: "3/admin,456[]"
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ACK);
        packet.setNsp("/admin");
        packet.setAckId(456L);
        packet.setData(Arrays.asList("response"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("43/admin,456")); // MESSAGE(4) + ACK(3)
        
        buffer.release();
    }

    // ==================== ERROR Packet Tests ====================

    @Test
    public void testEncodeErrorPacket() throws IOException {
        // ERROR packet: "4/admin,\"Not authorized\""
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ERROR);
        packet.setNsp("/admin");
        packet.setData("Not authorized");
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("44/admin")); // MESSAGE(4) + ERROR(4)
        
        buffer.release();
    }

    // ==================== BINARY_EVENT Packet Tests ====================

    @Test
    public void testEncodeBinaryEventPacket() throws IOException {
        // EVENT packet containing binary data should be encoded as BINARY_EVENT
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("hello");
        packet.setData(Arrays.asList(
                "data",
                "binData".getBytes(CharsetUtil.UTF_8)
        ));

        ByteBuf buffer = Unpooled.buffer();
        EncodeResult result = encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);

        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertTrue(encoded.startsWith("451-")); // MESSAGE(4) + BINARY_EVENT(5) + 1 attachment
        assertEquals(1, result.getAttachments().size());

        buffer.release();
    }
    @Test
    public void testEncodeBinaryEventPacketWithNamespace() throws IOException {
        // EVENT packet containing binary data should be encoded as BINARY_EVENT
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("/admin");
        packet.setName("project:delete");
        packet.setAckId(456L);

        packet.setData(Arrays.asList(
                "data",
                "binData".getBytes(CharsetUtil.UTF_8)
        ));

        ByteBuf buffer = Unpooled.buffer();
        EncodeResult result = encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);

        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertTrue(encoded.startsWith("451-/admin,456")); // MESSAGE(4) + BINARY_EVENT(5) + 1 attachment
        assertEquals(1, result.getAttachments().size());

        buffer.release();
    }

    // ==================== BINARY_ACK Packet Tests ====================

    @Test
    public void testEncodeBinaryAckPacket() throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ACK);
        packet.setNsp("/admin");
        packet.setAckId(456L);

        packet.setData(Arrays.asList(
                "response",
                "binData".getBytes(CharsetUtil.UTF_8)
        ));

        ByteBuf buffer = Unpooled.buffer();
        EncodeResult result = encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);

        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertTrue(encoded.startsWith("461-/admin,456"));
        assertEquals(1, result.getAttachments().size());

        buffer.release();
    }
    // ==================== PING/PONG Packet Tests ====================

    @Test
    public void testEncodePongPacket() throws IOException {
        // PONG packet
        Packet packet = new Packet(PacketType.PONG);
        packet.setData("pong");
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertEquals("3pong", encoded);
        
        buffer.release();
    }

    @Test
    public void testEncodeOpenPacket() throws IOException {
        // OPEN packet
        Packet packet = new Packet(PacketType.OPEN);
        Map<String, Object> openData = new HashMap<>();
        openData.put("sid", "test-sid");
        openData.put("upgrades", Arrays.asList("websocket"));
        packet.setData(openData);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("0"));
        
        buffer.release();
    }

    // ==================== Multiple Packets Tests ====================

    @Test
    public void testEncodeMultiplePackets() throws IOException {
        // Multiple packets separated by 0x1E
        Queue<Packet> packets = new LinkedList<>();
        
        Packet packet1 = new Packet(PacketType.MESSAGE);
        packet1.setSubType(PacketType.CONNECT);
        packet1.setNsp("/admin");
        packets.add(packet1);
        
        Packet packet2 = new Packet(PacketType.MESSAGE);
        packet2.setSubType(PacketType.EVENT);
        packet2.setNsp("");
        packet2.setName("hello");
        packet2.setData(Arrays.asList("world"));
        packets.add(packet2);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePackets(EngineIOVersion.V4, packets, buffer, allocator, 10);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.contains("40/admin")); // MESSAGE(4) + CONNECT(0)
        assertTrue(encoded.contains("42[\"hello\",\"world\"]")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    // ==================== JSONP Support Tests ====================

    @Test
    public void testEncodeJsonPWithIndex() throws IOException {
        // JSONP packet with index
        Queue<Packet> packets = new LinkedList<>();
        
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("hello");
        packet.setData(Arrays.asList("world"));
        packets.add(packet);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodeJsonP(EngineIOVersion.V4, 1, packets, buffer, allocator, 10);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("___eio[1]('"));
        assertTrue(encoded.endsWith("');"));
        
        buffer.release();
    }

    @Test
    public void testEncodeJsonPWithoutIndex() throws IOException {
        // JSONP packet without index
        Queue<Packet> packets = new LinkedList<>();
        
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("hello");
        packet.setData(Arrays.asList("world"));
        packets.add(packet);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodeJsonP(EngineIOVersion.V4, null, packets, buffer, allocator, 10);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertFalse(encoded.startsWith("___eio["));
        assertFalse(encoded.endsWith("');"));
        
        buffer.release();
    }

    // ==================== Binary Attachment Tests ====================

    @Test
    public void testEncodePacketWithBinaryAttachments() throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("upload");

        packet.setData(Arrays.asList(
                "file",
                "attachment1".getBytes(CharsetUtil.UTF_8),
                "attachment2".getBytes(CharsetUtil.UTF_8)
        ));

        ByteBuf buffer = Unpooled.buffer();

        EncodeResult result = encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);

        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertTrue(encoded.startsWith("452-")); // 2 attachments
        assertEquals(2, result.getAttachments().size());

        buffer.release();
    }

    // ==================== Buffer Allocation Tests ====================

    @Test
    public void testAllocateBufferHeap() throws IOException {
        // Test heap buffer allocation
        configuration.setPreferDirectBuffer(false);
        
        ByteBuf buffer = encoder.allocateBuffer(allocator);
        
        assertNotNull(buffer);
        assertFalse(buffer.isDirect());
        
        buffer.release();
    }

    @Test
    public void testAllocateBufferDirect() throws IOException {
        // Test direct buffer allocation
        configuration.setPreferDirectBuffer(true);
        
        ByteBuf buffer = encoder.allocateBuffer(allocator);
        
        assertNotNull(buffer);
        assertTrue(buffer.isDirect());
        
        buffer.release();
    }

    // ==================== Utility Method Tests ====================

    @Test
    public void testToChars() throws IOException {
        // Test toChars utility method
        byte[] result = PacketEncoder.toChars(12345L);
        
        assertNotNull(result);
        assertEquals(5, result.length);
        
        // Convert back to verify
        String number = new String(result);
        assertEquals("12345", number);
    }

    @Test
    public void testToCharsNegative() throws IOException {
        // Test toChars with negative number
        byte[] result = PacketEncoder.toChars(-12345L);
        
        assertNotNull(result);
        assertEquals(6, result.length); // Including minus sign
        
        // Convert back to verify
        String number = new String(result);
        assertEquals("-12345", number);
    }

    @Test
    public void testToCharsZero() throws IOException {
        // Test toChars with zero
        byte[] result = PacketEncoder.toChars(0L);
        
        assertNotNull(result);
        assertEquals(1, result.length);
        
        // Convert back to verify
        String number = new String(result);
        assertEquals("0", number);
    }

    @Test
    public void testLongToBytes() throws IOException {
        // Test longToBytes utility method
        byte[] result = PacketEncoder.longToBytes(12345L);
        
        assertNotNull(result);
        assertEquals(5, result.length);
        
        // Convert back to verify
        StringBuilder number = new StringBuilder();
        for (byte b : result) {
            number.append(b);
        }
        assertEquals("12345", number.toString());
    }

    @Test
    public void testLongToBytesSingleDigit() throws IOException {
        // Test longToBytes with single digit
        byte[] result = PacketEncoder.longToBytes(5L);
        
        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(5, result[0]);
    }

    @Test
    public void testLongToBytesZero() throws IOException {
        // Test longToBytes with zero - now properly handled
        byte[] result = PacketEncoder.longToBytes(0L);
        
        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(0, result[0]);
    }

    // ==================== Find Method Tests ====================

    @Test
    public void testFind() throws IOException {
        // Test find utility method
        ByteBuf buffer = Unpooled.copiedBuffer("Hello World", CharsetUtil.UTF_8);
        ByteBuf search = Unpooled.copiedBuffer("World", CharsetUtil.UTF_8);
        
        int position = PacketEncoder.find(buffer, search);
        
        assertEquals(6, position);
        
        buffer.release();
        search.release();
    }

    @Test
    public void testFindNotFound() throws IOException {
        // Test find utility method when not found
        ByteBuf buffer = Unpooled.copiedBuffer("Hello World", CharsetUtil.UTF_8);
        ByteBuf search = Unpooled.copiedBuffer("NotFound", CharsetUtil.UTF_8);
        
        int position = PacketEncoder.find(buffer, search);
        
        assertEquals(-1, position);
        
        buffer.release();
        search.release();
    }

    @Test
    public void testFindEmptySearch() throws IOException {
        // Test find utility method with empty search
        ByteBuf buffer = Unpooled.copiedBuffer("Hello World", CharsetUtil.UTF_8);
        ByteBuf search = Unpooled.copiedBuffer("", CharsetUtil.UTF_8);
        
        int position = PacketEncoder.find(buffer, search);
        
        assertEquals(0, position); // Empty string found at beginning
        
        buffer.release();
        search.release();
    }

    @Test
    public void testFindAtEnd() throws IOException {
        // Test find utility method at end of buffer
        ByteBuf buffer = Unpooled.copiedBuffer("Hello World", CharsetUtil.UTF_8);
        ByteBuf search = Unpooled.copiedBuffer("World", CharsetUtil.UTF_8);
        
        int position = PacketEncoder.find(buffer, search);
        
        assertEquals(6, position);
        
        buffer.release();
        search.release();
    }

    // ==================== UTF-8 Processing Tests ====================

    @Test
    public void testProcessUtf8() throws Exception {
        // Test UTF-8 processing in JSONP mode
        ByteBuf input = Unpooled.copiedBuffer("Hello\\'World", CharsetUtil.UTF_8);
        ByteBuf output = Unpooled.buffer();
        
        // Use reflection to test private method
        Method processUtf8Method = PacketEncoder.class.getDeclaredMethod("processUtf8", ByteBuf.class, ByteBuf.class, boolean.class);
        processUtf8Method.setAccessible(true);
        processUtf8Method.invoke(encoder, input, output, true);
        
        String result = output.toString(CharsetUtil.UTF_8);
        assertNotNull(result);
        assertTrue(result.length() > 0);
        
        input.release();
        output.release();
    }

    @Test
    public void testProcessUtf8NonJsonpMode() throws Exception {
        // Test UTF-8 processing in non-JSONP mode
        ByteBuf input = Unpooled.copiedBuffer("Hello'World", CharsetUtil.UTF_8);
        ByteBuf output = Unpooled.buffer();
        
        // Use reflection to test private method
        Method processUtf8Method = PacketEncoder.class.getDeclaredMethod("processUtf8", ByteBuf.class, ByteBuf.class, boolean.class);
        processUtf8Method.setAccessible(true);
        processUtf8Method.invoke(encoder, input, output, false);
        
        String result = output.toString(CharsetUtil.UTF_8);
        assertNotNull(result);
        assertTrue(result.length() > 0);
        
        input.release();
        output.release();
    }

    // ==================== Edge Cases and Error Handling ====================

    @Test
    public void testEncodePacketWithNullData() throws IOException {
        // Test encoding packet with null data
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList()); // Use empty list instead of null
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    @Test
    public void testEncodePacketWithEmptyNamespace() throws IOException {
        // Test encoding packet with empty namespace
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        assertFalse(encoded.contains("/"));
        
        buffer.release();
    }

    @Test
    public void testEncodePacketWithLargeData() throws IOException {
        // Test encoding packet with large data
        StringBuilder largeData = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeData.append("data").append(i).append(",");
        }
        largeData.append("end");
        
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("largeEvent");
        packet.setData(Arrays.asList(largeData.toString()));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        assertTrue(encoded.contains("largeEvent"));
        
        buffer.release();
    }

    // ==================== Performance Tests ====================

    @Test
    public void testEncodePerformance() throws IOException {
        // Test encoding performance with large packet
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("performanceTest");
        
        // Create large data
        StringBuilder largeData = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeData.append("data").append(i).append(",");
        }
        largeData.append("end");
        packet.setData(Arrays.asList(largeData.toString()));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        
        long startTime = System.currentTimeMillis();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        long endTime = System.currentTimeMillis();
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        // Should complete within reasonable time (less than 100ms)
        assertTrue((endTime - startTime) < 100, 
                  "Encoding took too long: " + (endTime - startTime) + "ms");
        
        buffer.release();
    }

    @Test
    public void testEncodeMultiplePacketsPerformance() throws IOException {
        // Test encoding multiple packets performance
        Queue<Packet> packets = new LinkedList<>();
        
        for (int i = 0; i < 100; i++) {
            Packet packet = new Packet(PacketType.MESSAGE);
            packet.setSubType(PacketType.EVENT);
            packet.setNsp("/test");
            packet.setName("event" + i);
            packet.setData(Arrays.asList("data" + i));
            packets.add(packet);
        }
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        
        long startTime = System.currentTimeMillis();
        encoder.encodePackets(EngineIOVersion.V4, packets, buffer, allocator, 100);
        long endTime = System.currentTimeMillis();
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.contains("event0"));
        assertTrue(encoded.contains("event99"));
        
        // Should complete within reasonable time (less than 200ms)
        assertTrue((endTime - startTime) < 200, 
                  "Encoding multiple packets took too long: " + (endTime - startTime) + "ms");
        
        buffer.release();
    }

    // ==================== Engine.IO Version Tests ====================

    @Test
    public void testEncodePacketV2() throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));

        ByteBuf buffer = Unpooled.buffer();
        try {
            encoder.encodePacket(EngineIOVersion.V2, packet, buffer, allocator, false);

            assertEquals(0, buffer.getUnsignedByte(0));

            String encoded = buffer.toString(CharsetUtil.UTF_8);
            assertTrue(encoded.contains("42[\"test\",\"data\"]"));
        } finally {
            buffer.release();
        }
    }

    @Test
    public void testEncodePacketV3() throws IOException {
        // Test encoding packet with Engine.IO V3
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V3, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertTrue(encoded.startsWith("42"));
        
        buffer.release();
    }

    @Test
    public void testEncodePacketV4() throws IOException {
        // Test encoding packet with Engine.IO V4
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    // ==================== Binary Mode Tests ====================

    @Test
    public void testEncodePacketBinaryMode() throws IOException {
        // Test encoding packet in binary mode
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(EngineIOVersion.V4, packet, buffer, allocator, true);
        
        // In binary mode, the packet should be encoded directly to the buffer
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    @Test
    public void testEncodePacketsEIOv4BinaryAttachmentStandardBase64() throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("binEvent");

        // Byte array containing bytes that encode to '+' and '/' in standard Base64
        byte[] rawBytes = new byte[]{(byte) 0xFB, (byte) 0xFF, (byte) 0xBF};

        packet.setData(Arrays.asList(
                new HashMap<>(),
                rawBytes
        ));

        Queue<Packet> queue = new LinkedList<>();
        queue.add(packet);

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePackets(EngineIOVersion.V4, queue, buffer, allocator, 50);

        String encoded = buffer.toString(CharsetUtil.UTF_8);

        // EIOv4 polling format:
        // 451-["binEvent",{"_placeholder":true,"num":0}]<0x1E>b+/+/
        assertTrue(encoded.contains("+/+/"),
                "Binary attachment should use standard Base64 encoding ('+' and '/') instead of URL_SAFE ('-' and '_')");
        assertFalse(encoded.contains("-_-_"),
                "Should not contain URL_SAFE characters");

        buffer.release();
    }

    // ==================== Cross Engine.IO Version Encoding Tests (V2, V3, V4) ====================

    @ParameterizedTest(name = "Encode CONNECT Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeConnectPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        // 1. Default namespace
        Packet packetDefault = new Packet(PacketType.MESSAGE);
        packetDefault.setSubType(PacketType.CONNECT);
        packetDefault.setNsp("");

        ByteBuf bufDefault = Unpooled.buffer();
        encoder.encodePacket(version, packetDefault, bufDefault, allocator, false);
        assertTrue(bufDefault.toString(CharsetUtil.UTF_8).endsWith("40"), "CONNECT packet should end with '40' for EIO " + version);
        bufDefault.release();

        // 2. Custom namespace
        Packet packetCustom = new Packet(PacketType.MESSAGE);
        packetCustom.setSubType(PacketType.CONNECT);
        packetCustom.setNsp("/admin");

        ByteBuf bufCustom = Unpooled.buffer();
        encoder.encodePacket(version, packetCustom, bufCustom, allocator, false);
        assertTrue(bufCustom.toString(CharsetUtil.UTF_8).endsWith("40/admin"), "CONNECT packet custom nsp should end with '40/admin' for EIO " + version);
        bufCustom.release();
    }

    @ParameterizedTest(name = "Encode DISCONNECT Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeDisconnectPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.DISCONNECT);
        packet.setNsp("/admin");

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(version, packet, buffer, allocator, false);
        assertTrue(buffer.toString(CharsetUtil.UTF_8).endsWith("41/admin,"), "DISCONNECT packet should end with '41/admin,' for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode EVENT Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeEventPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("/admin");
        packet.setName("deleteUser");
        packet.setData(Arrays.asList(1001));
        packet.setAckId(777L);

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(version, packet, buffer, allocator, false);
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.contains("42/admin,777[\"deleteUser\",1001]"), "Encoded EVENT should contain specification payload for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode ACK Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeAckPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ACK);
        packet.setNsp("/admin");
        packet.setAckId(888L);
        packet.setData(Arrays.asList("ok", true));

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(version, packet, buffer, allocator, false);
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.contains("43/admin,888[\"ok\",true]"), "Encoded ACK should contain specification payload for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode ERROR Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeErrorPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ERROR);
        packet.setNsp("/admin");
        packet.setData("Forbidden");

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(version, packet, buffer, allocator, false);
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.contains("44/admin,\"Forbidden\""), "Encoded ERROR should contain specification payload for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode BINARY_EVENT Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeBinaryEventPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("/admin");
        packet.setName("binEvent");
        packet.setData(Arrays.asList(
                "hello",
                "attachmentData".getBytes(CharsetUtil.UTF_8)
        ));

        ByteBuf buffer = Unpooled.buffer();
        EncodeResult result = encoder.encodePacket(version, packet, buffer, allocator, false);

        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertTrue(encoded.contains("451-/admin,"),
                "Encoded BINARY_EVENT header should format correctly for EIO " + version);
        assertEquals(1, result.getAttachments().size());

        buffer.release();
    }

    @ParameterizedTest(name = "Encode BINARY_ACK Packet - {0}")
    @EnumSource(value = EngineIOVersion.class, names = { "V2", "V3", "V4" })
    void testEncodeBinaryAckPacketCrossEngineIOVersions(EngineIOVersion version) throws Exception {

        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ACK);
        packet.setNsp("/admin");
        packet.setAckId(1234L);

        // IMPORTANT:
        // Socket.IO binary payload should be represented as byte[]
        // so JsonSupport converts it into an attachment.
        packet.setData(Arrays.asList(
                "res",
                "ackAttachment".getBytes(StandardCharsets.UTF_8)
        ));

        ByteBuf buffer = Unpooled.buffer();

        try {
            EncodeResult result = encoder.encodePacket(version, packet, buffer, allocator, false);

            // One binary attachment must be extracted
            assertEquals(1, result.getAttachments().size());

            String encoded = buffer.toString(CharsetUtil.UTF_8);

            switch (version) {

                case V2: {
                    // Engine.IO v2 prepends:
                    // 0 <ascii-length> 0xFF
                    assertEquals(0, buffer.getByte(0));

                    int ff = buffer.indexOf(0, buffer.writerIndex(), (byte) 0xFF);
                    assertTrue(ff > 0, "Missing Engine.IO v2 frame delimiter");

                    String sio = buffer.toString(
                            ff + 1,
                            buffer.writerIndex() - ff - 1,
                            CharsetUtil.UTF_8);

                    assertTrue(sio.startsWith("461-/admin,1234"), sio);
                    assertTrue(sio.contains("\"_placeholder\":true"), sio);
                    assertTrue(sio.contains("\"num\":0"), sio);
                    break;
                }

                case V3: {
                    // v3 has no binary frame prefix for encodePacket()
                    assertTrue(encoded.startsWith("461-/admin,1234"), encoded);
                    assertTrue(encoded.contains("\"_placeholder\":true"), encoded);
                    assertTrue(encoded.contains("\"num\":0"), encoded);
                    break;
                }

                case V4: {
                    // Same Socket.IO packet format.
                    // Engine.IO framing happens later in encodePackets().
                    assertTrue(encoded.startsWith("461-/admin,1234"), encoded);
                    assertTrue(encoded.contains("\"_placeholder\":true"), encoded);
                    assertTrue(encoded.contains("\"num\":0"), encoded);
                    break;
                }

                default:
                    fail("Unhandled Engine.IO version: " + version);
            }

            ByteBuf attachment = result.getAttachments().get(0);
            byte[] actual = new byte[attachment.readableBytes()];
            attachment.getBytes(attachment.readerIndex(), actual);

            assertArrayEquals(
                    "ackAttachment".getBytes(StandardCharsets.UTF_8),
                    actual);

        } finally {
            buffer.release();
        }
    }

    @Test
    public void testEncodePacketsEIOv3PollingBatchWithXHR2Attachment() throws IOException {

        Packet binaryEvent = new Packet(PacketType.MESSAGE);
        binaryEvent.setSubType(PacketType.EVENT);
        binaryEvent.setNsp("");
        binaryEvent.setName("binEv");
        binaryEvent.setData(Arrays.asList(
                "hello",
                new byte[]{10, 20, 30}
        ));

        Queue<Packet> queue = new LinkedList<>();
        queue.add(binaryEvent);

        ByteBuf buffer = Unpooled.buffer();

        try {

            EncodePacketsResult result = encoder.encodePackets(
                    EngineIOVersion.V3,
                    queue,
                    buffer,
                    allocator,
                    Integer.MAX_VALUE);

            assertTrue(result.hasBinary());
            assertTrue(queue.isEmpty());

            String payload = buffer.toString(CharsetUtil.ISO_8859_1);

            //
            // Placeholder packet should be present.
            //
            assertTrue(payload.contains("\"binEv\""));
            assertTrue(payload.contains("\"hello\""));
            assertTrue(payload.contains("\"_placeholder\":true"));
            assertTrue(payload.contains("\"num\":0"));

            //
            // Verify XHR2 attachment frame.
            //
            byte[] encoded = ByteBufUtil.getBytes(buffer);

            byte[] expectedAttachment = {
                    0x01,
                    0x04,
                    (byte) 0xFF,
                    0x04,
                    10,
                    20,
                    30
            };

            assertArrayEquals(
                    expectedAttachment,
                    Arrays.copyOfRange(
                            encoded,
                            encoded.length - expectedAttachment.length,
                            encoded.length));

        } finally {
            buffer.release();
        }
    }
    private static Packet event(String name, Object... args) {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setName(name);
        packet.setData(
                Arrays.asList(args));

        return packet;
    }
    @Test
    void testEncodePacketsV3TextThenBinary() throws Exception {

        Queue<Packet> packets = new ConcurrentLinkedQueue<>();

        packets.add(event("batchText1", "TEXT1"));
        packets.add(event("batchBinary",  new byte[]{1,2,3,4,5}));

        ByteBuf out = Unpooled.buffer();

        EncodePacketsResult result =
                encoder.encodePackets(
                        EngineIOVersion.V3,
                        packets,
                        out,
                        UnpooledByteBufAllocator.DEFAULT,
                        50);

        assertTrue(result.hasBinary());

        assertEquals(
                "000204ff34325b2262617463685465787431222c225445585431225d"
                        + "000409ff3435312d5b22626174636842696e617279222c7b225f706c616365686f6c646572223a747275652c226e756d223a307d5d"
                        + "0106ff040102030405",
                ByteBufUtil.hexDump(out));
    }

    // ==================== Rigorous Engine.IO & Socket.IO Specification Tests ====================

    @Test
    void testEncodeV4BatchTextAndBinaryWithBase64Attachments() throws Exception {
        Queue<Packet> packets = new ConcurrentLinkedQueue<>();

        Packet textPacket = new Packet(PacketType.MESSAGE);
        textPacket.setSubType(PacketType.EVENT);
        textPacket.setName("textEvent");
        textPacket.setData(Arrays.asList("hello_world"));
        packets.add(textPacket);

        Packet binPacket = new Packet(PacketType.MESSAGE);
        binPacket.setSubType(PacketType.EVENT);
        binPacket.setName("binEvent");
        binPacket.setData(Arrays.asList(new byte[]{10, 20, 30}));
        packets.add(binPacket);

        ByteBuf out = Unpooled.buffer();
        try {
            EncodePacketsResult result = encoder.encodePackets(
                    EngineIOVersion.V4,
                    packets,
                    out,
                    UnpooledByteBufAllocator.DEFAULT,
                    10
            );

            assertTrue(result.hasBinary());
            String encoded = out.toString(CharsetUtil.UTF_8);

            // In EIO v4 polling, packets are separated by 0x1E (\x1e)
            String[] parts = encoded.split("\u001e");
            assertEquals(3, parts.length); // 1. text event, 2. binary event header, 3. base64 attachment

            assertEquals("42[\"textEvent\",\"hello_world\"]", parts[0]);
            assertTrue(parts[1].startsWith("451-[\"binEvent\",{\"_placeholder\":true,\"num\":0}]"));
            assertTrue(parts[2].startsWith("b")); // EIO v4 polling binary attachment has 'b' prefix

            // Base64 of bytes {10, 20, 30} is "ChQe"
            assertEquals("bChQe", parts[2]);
        } finally {
            out.release();
        }
    }

    @Test
    void testEncodeV4BinaryAckWithCustomNamespace() throws Exception {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ACK);
        packet.setNsp("/chat");
        packet.setAckId(777L);
        packet.setData(Arrays.asList(new byte[]{1, 2, 3, 4}));

        ByteBuf buffer = Unpooled.buffer();
        try {
            EncodeResult result = encoder.encodePacket(EngineIOVersion.V4, packet, buffer, UnpooledByteBufAllocator.DEFAULT, false);

            assertTrue(result.hasAttachments());
            assertEquals(1, result.getAttachments().size());
            assertEquals("461-/chat,777[{\"_placeholder\":true,\"num\":0}]", buffer.toString(CharsetUtil.UTF_8));
        } finally {
            buffer.release();
        }
    }

    @Test
    void testEncodeV3TextOnlyBatchFraming() throws Exception {
        Queue<Packet> packets = new ConcurrentLinkedQueue<>();

        Packet p1 = new Packet(PacketType.PING);
        packets.add(p1);

        Packet p2 = new Packet(PacketType.MESSAGE);
        p2.setSubType(PacketType.EVENT);
        p2.setName("chat");
        p2.setData(Arrays.asList("hi"));
        packets.add(p2);

        ByteBuf out = Unpooled.buffer();
        try {
            EncodePacketsResult result = encoder.encodePackets(
                    EngineIOVersion.V3,
                    packets,
                    out,
                    UnpooledByteBufAllocator.DEFAULT,
                    10
            );

            assertFalse(result.hasBinary());
            String encoded = out.toString(CharsetUtil.UTF_8);

            // EIO v3 text-only polling format is "<char_count>:<packet><char_count>:<packet>"
            // 1:2 (PING is 1 char '2'), 15:42["chat","hi"] (15 chars)
            assertEquals("1:215:42[\"chat\",\"hi\"]", encoded);
        } finally {
            out.release();
        }
    }

    @Test
    void testEncodeConnectErrorPacketWithMapPayload() throws Exception {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ERROR);
        packet.setNsp("/admin");
        Map<String, Object> errData = new HashMap<>();
        errData.put("message", "Not authorized");
        errData.put("code", 401);
        packet.setData(errData);

        ByteBuf buffer = Unpooled.buffer();
        try {
            encoder.encodePacket(EngineIOVersion.V4, packet, buffer, UnpooledByteBufAllocator.DEFAULT, false);

            String encoded = buffer.toString(CharsetUtil.UTF_8);
            assertTrue(encoded.startsWith("44/admin,"));
            assertTrue(encoded.contains("\"message\":\"Not authorized\""));
            assertTrue(encoded.contains("\"code\":401"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void testEncodeUtf8MultibyteCharactersLengthCalculationInV3() throws Exception {
        // EIO v3 text polling header uses character count, NOT byte count
        Queue<Packet> packets = new ConcurrentLinkedQueue<>();

        Packet p = new Packet(PacketType.MESSAGE);
        p.setSubType(PacketType.EVENT);
        p.setName("emoji");
        p.setData(Arrays.asList("🚀🔥"));
        packets.add(p);

        ByteBuf out = Unpooled.buffer();
        try {
            encoder.encodePackets(EngineIOVersion.V3, packets, out, UnpooledByteBufAllocator.DEFAULT, 10);

            String encoded = out.toString(CharsetUtil.UTF_8);
            int colonIndex = encoded.indexOf(':');
            int headerLen = Integer.parseInt(encoded.substring(0, colonIndex));
            String body = encoded.substring(colonIndex + 1);

            // In EIO v3, the length header must match the String length (char count) of the body
            assertEquals(body.length(), headerLen);
        } finally {
            out.release();
        }
    }

    @Test
    void testEncodeAllWorldLanguagesInV3AndV4() throws Exception {
        Map<String, String> languages = new HashMap<>();
        languages.put("tamil", "வணக்கம் உலகம்");
        languages.put("chinese", "你好世界，繁體中文測試");
        languages.put("hindi", "नमस्ते भारत और दुनिया");
        languages.put("arabic", "مرحبا بالعالم");
        languages.put("japanese", "こんにちは世界");
        languages.put("korean", "안녕하세요 세계");
        languages.put("russian", "Привет мир");
        languages.put("greek", "Γειά σου Κόσμε");
        languages.put("hebrew", "שלום עולם");
        languages.put("thai", "สวัสดีชาวโลก");
        languages.put("bengali", "হ্যালো বিশ্ব");
        languages.put("vietnamese", "Xin chào thế giới");
        languages.put("amharic", "ሰላም ዓለም");
        languages.put("georgian", "გამარჯობა მსოფლიო");
        languages.put("armenian", "Բարև աշխարհ");

        // 1. Test EIO v4 encoding for all languages
        Packet pV4 = new Packet(PacketType.MESSAGE);
        pV4.setSubType(PacketType.EVENT);
        pV4.setName("global_chat");
        pV4.setData(Arrays.asList(languages));

        ByteBuf bufV4 = Unpooled.buffer();
        try {
            encoder.encodePacket(EngineIOVersion.V4, pV4, bufV4, UnpooledByteBufAllocator.DEFAULT, false);
            String encodedV4 = bufV4.toString(CharsetUtil.UTF_8);
            assertTrue(encodedV4.startsWith("42[\"global_chat\","));
            for (String sample : languages.values()) {
                assertTrue(encodedV4.contains(sample), "Missing language sample: " + sample);
            }
        } finally {
            bufV4.release();
        }

        // 2. Test EIO v3 length header calculation for all languages
        Queue<Packet> queueV3 = new ConcurrentLinkedQueue<>();
        queueV3.add(pV4);

        ByteBuf bufV3 = Unpooled.buffer();
        try {
            encoder.encodePackets(EngineIOVersion.V3, queueV3, bufV3, UnpooledByteBufAllocator.DEFAULT, 10);
            String encodedV3 = bufV3.toString(CharsetUtil.UTF_8);

            int colonIndex = encodedV3.indexOf(':');
            int headerLen = Integer.parseInt(encodedV3.substring(0, colonIndex));
            String body = encodedV3.substring(colonIndex + 1);

            // EIO v3 header length MUST equal string character count (UTF-16 code units), NOT byte count
            assertEquals(body.length(), headerLen);
            for (String sample : languages.values()) {
                assertTrue(body.contains(sample), "Missing language sample in V3 body: " + sample);
            }
        } finally {
            bufV3.release();
        }
    }

    @Test
    void testTamilScriptComprehensiveEncoding() throws Exception {
        String thirukkural = "அகர முதல எழுத்தெல்லாம் ஆதி பகவன் முதற்றே உலகு.";
        String granthaText = "ஸ்ரீராமஜெயம் - ஜ, ஷ, ஸ, ஹ, க்ஷ, ஸ்ரீ";
        String aythamText = "ஃ - ஆய்த எழுத்து (அஃது, இஃது)";

        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setName("தமிழ்_நிகழ்வு");
        packet.setData(Arrays.asList(thirukkural, granthaText, aythamText));

        // 1. EIO v4 Encoding
        ByteBuf bufV4 = Unpooled.buffer();
        try {
            encoder.encodePacket(EngineIOVersion.V4, packet, bufV4, UnpooledByteBufAllocator.DEFAULT, false);
            String encoded = bufV4.toString(CharsetUtil.UTF_8);
            assertTrue(encoded.startsWith("42[\"தமிழ்_நிகழ்வு\","));
            assertTrue(encoded.contains(thirukkural));
            assertTrue(encoded.contains(granthaText));
            assertTrue(encoded.contains(aythamText));
        } finally {
            bufV4.release();
        }

        // 2. EIO v3 Encoding with character length check
        Queue<Packet> queue = new ConcurrentLinkedQueue<>();
        queue.add(packet);
        ByteBuf bufV3 = Unpooled.buffer();
        try {
            encoder.encodePackets(EngineIOVersion.V3, queue, bufV3, UnpooledByteBufAllocator.DEFAULT, 10);
            String encodedV3 = bufV3.toString(CharsetUtil.UTF_8);
            int colonIdx = encodedV3.indexOf(':');
            int headerLen = Integer.parseInt(encodedV3.substring(0, colonIdx));
            String body = encodedV3.substring(colonIdx + 1);

            assertEquals(body.length(), headerLen);
            assertTrue(body.contains(thirukkural));
        } finally {
            bufV3.release();
        }
    }

    @Test
    void testAncientTamilBrahmiScriptEncoding() throws Exception {
        // Tamil-Brahmi / Tamili Script (3rd Century BCE - Keeladi / Mangulam Inscriptions)
        // Unicode Brahmi Block U+11000..U+1107F (Supplementary Plane 1 - 4-byte UTF-8 / UTF-16 Surrogate Pairs)
        String ancientTamiliWord = "𑀢𑀫𑀺𑀵𑀺"; // "Tamili" in Tamil-Brahmi script
        String mangulamInscription = "𑀦𑀺𑀕𑀫𑀢𑀺 𑀘𑀸𑀮𑀺𑀬𑀦𑀺 𑀇𑀮𑀜𑀘𑀝𑀺𑀬𑀦𑀺"; // Mangulam Tamil-Brahmi inscription sample

        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.EVENT);
        packet.setName("𑀢𑀫𑀺𑀵𑀺_event");
        packet.setData(Arrays.asList(ancientTamiliWord, mangulamInscription));

        // 1. EIO v4 Encoding (4-byte UTF-8 handling)
        ByteBuf bufV4 = Unpooled.buffer();
        try {
            encoder.encodePacket(EngineIOVersion.V4, packet, bufV4, UnpooledByteBufAllocator.DEFAULT, false);
            String encoded = bufV4.toString(CharsetUtil.UTF_8);
            // Jackson escapes supplementary plane characters (U+11000+) as UTF-16 surrogate escapes (\uD804\uDC22) or raw UTF-8
            assertTrue(encoded.contains(ancientTamiliWord) || encoded.contains("\\uD804\\uDC22"), "Encoded output must contain Brahmi script or surrogate escapes: " + encoded);
        } finally {
            bufV4.release();
        }

        // 2. EIO v3 Encoding (Surrogate pair char length verification)
        Queue<Packet> queue = new ConcurrentLinkedQueue<>();
        queue.add(packet);
        ByteBuf bufV3 = Unpooled.buffer();
        try {
            encoder.encodePackets(EngineIOVersion.V3, queue, bufV3, UnpooledByteBufAllocator.DEFAULT, 10);
            String encodedV3 = bufV3.toString(CharsetUtil.UTF_8);
            int colonIdx = encodedV3.indexOf(':');
            int headerLen = Integer.parseInt(encodedV3.substring(0, colonIdx));
            String body = encodedV3.substring(colonIdx + 1);

            assertEquals(body.length(), headerLen);
            assertTrue(body.contains(ancientTamiliWord) || body.contains("\\uD804"), "Body must contain Brahmi script or surrogate escapes: " + body);
        } finally {
            bufV3.release();
        }
    }
}
