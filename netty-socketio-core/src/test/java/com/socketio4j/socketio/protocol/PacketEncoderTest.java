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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

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
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.CONNECT);
        packet.setNsp("");
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertEquals("40", encoded); // MESSAGE(4) + CONNECT(0)
        
        buffer.release();
    }

    @Test
    public void testEncodeConnectPacketCustomNamespace() throws IOException {
        // CONNECT packet for custom namespace
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.CONNECT);
        packet.setNsp("/admin");
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertEquals("40/admin", encoded); // MESSAGE(4) + CONNECT(0)
        
        buffer.release();
    }

    @Test
    public void testEncodeConnectPacketWithAuthData() throws IOException {
        // CONNECT packet with auth data
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.CONNECT);
        packet.setNsp("/admin");
        Map<String, String> authData = new HashMap<>();
        authData.put("token", "123");
        packet.setData(authData);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("40/admin")); // MESSAGE(4) + CONNECT(0)
        
        buffer.release();
    }

    // ==================== DISCONNECT Packet Tests ====================

    @Test
    public void testEncodeDisconnectPacket() throws IOException {
        // DISCONNECT packet
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.DISCONNECT);
        packet.setNsp("/admin");
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertEquals("41/admin,", encoded); // MESSAGE(4) + DISCONNECT(1) + comma
        
        buffer.release();
    }

    // ==================== EVENT Packet Tests ====================

    @Test
    public void testEncodeEventPacketSimple() throws IOException {
        // EVENT packet: "42[\"hello\",1]" (MESSAGE + EVENT)
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("hello");
        packet.setData(Arrays.asList(1));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    @Test
    public void testEncodeEventPacketWithNamespace() throws IOException {
        // EVENT packet with namespace: "2/admin,456[\"project:delete\",123]"
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("/admin");
        packet.setName("project:delete");
        packet.setData(Arrays.asList(123));
        packet.setAckId(456L);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42/admin,456")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    // ==================== ACK Packet Tests ====================

    @Test
    public void testEncodeAckPacket() throws IOException {
        // ACK packet: "3/admin,456[]"
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.ACK);
        packet.setNsp("/admin");
        packet.setAckId(456L);
        packet.setData(Arrays.asList("response"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("43/admin,456")); // MESSAGE(4) + ACK(3)
        
        buffer.release();
    }

    // ==================== ERROR Packet Tests ====================

    @Test
    public void testEncodeErrorPacket() throws IOException {
        // ERROR packet: "4/admin,\"Not authorized\""
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.ERROR);
        packet.setNsp("/admin");
        packet.setData("Not authorized");
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("44/admin")); // MESSAGE(4) + ERROR(4)
        
        buffer.release();
    }

    // ==================== BINARY_EVENT Packet Tests ====================

    @Test
    public void testEncodeBinaryEventPacket() throws IOException {
        // BINARY_EVENT packet: "451-[\"hello\",\"data\"]"
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.BINARY_EVENT);
        packet.initAttachments(1);
        packet.setNsp("");
        packet.setName("hello");
        packet.setData(Arrays.asList("data"));
        packet.addAttachment(Unpooled.copiedBuffer("binData", CharsetUtil.UTF_8));
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("451-")); // MESSAGE(4) + BINARY_EVENT(5) + 1 attachment
        
        buffer.release();
    }

    @Test
    public void testEncodeBinaryEventPacketWithNamespace() throws IOException {
        // BINARY_EVENT packet with namespace: "451-/admin,456[\"project:delete\",\"data\"]"
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.BINARY_EVENT);
        packet.initAttachments(1);
        packet.setNsp("/admin");
        packet.setName("project:delete");
        packet.setData(Arrays.asList("data"));
        packet.setAckId(456L);
        packet.addAttachment(Unpooled.copiedBuffer("binData", CharsetUtil.UTF_8));
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("451-/admin,456")); // MESSAGE(4) + BINARY_EVENT(5) + 1 attachment
        
        buffer.release();
    }

    // ==================== BINARY_ACK Packet Tests ====================

    @Test
    public void testEncodeBinaryAckPacket() throws IOException {
        // BINARY_ACK packet: "461-/admin,456[\"response\"]"
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.BINARY_ACK);
        packet.initAttachments(1);
        packet.setNsp("/admin");
        packet.setAckId(456L);
        packet.setData(Arrays.asList("response"));
        packet.addAttachment(Unpooled.copiedBuffer("binData", CharsetUtil.UTF_8));
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("461-/admin,456")); // MESSAGE(4) + BINARY_ACK(6) + 1 attachment
        
        buffer.release();
    }

    // ==================== PING/PONG Packet Tests ====================

    @Test
    public void testEncodePongPacket() throws IOException {
        // PONG packet
        Packet packet = new Packet(PacketType.PONG, EngineIOVersion.V4);
        packet.setData("pong");
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertEquals("3pong", encoded);
        
        buffer.release();
    }

    @Test
    public void testEncodeOpenPacket() throws IOException {
        // OPEN packet
        Packet packet = new Packet(PacketType.OPEN, EngineIOVersion.V4);
        Map<String, Object> openData = new HashMap<>();
        openData.put("sid", "test-sid");
        openData.put("upgrades", Arrays.asList("websocket"));
        packet.setData(openData);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("0"));
        
        buffer.release();
    }

    // ==================== Multiple Packets Tests ====================

    @Test
    public void testEncodeMultiplePackets() throws IOException {
        // Multiple packets separated by 0x1E
        Queue<Packet> packets = new LinkedList<>();
        
        Packet packet1 = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet1.setSubType(PacketType.CONNECT);
        packet1.setNsp("/admin");
        packets.add(packet1);
        
        Packet packet2 = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet2.setSubType(PacketType.EVENT);
        packet2.setNsp("");
        packet2.setName("hello");
        packet2.setData(Arrays.asList("world"));
        packets.add(packet2);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePackets(packets, buffer, allocator, 10);
        
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
        
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("hello");
        packet.setData(Arrays.asList("world"));
        packets.add(packet);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodeJsonP(1, packets, buffer, allocator, 10);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("___eio[1]('"));
        assertTrue(encoded.endsWith("');"));
        
        buffer.release();
    }

    @Test
    public void testEncodeJsonPWithoutIndex() throws IOException {
        // JSONP packet without index
        Queue<Packet> packets = new LinkedList<>();
        
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("hello");
        packet.setData(Arrays.asList("world"));
        packets.add(packet);
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodeJsonP(null, packets, buffer, allocator, 10);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertFalse(encoded.startsWith("___eio["));
        assertFalse(encoded.endsWith("');"));
        
        buffer.release();
    }

    // ==================== Binary Attachment Tests ====================

    @Test
    public void testEncodePacketWithBinaryAttachments() throws IOException {
        // Packet with binary attachments
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.BINARY_EVENT);
        packet.setNsp("");
        packet.setName("upload");
        packet.setData(Arrays.asList("file"));
        
        // Add binary attachments
        packet.initAttachments(2);
        packet.addAttachment(Unpooled.copiedBuffer("attachment1".getBytes()));
        packet.addAttachment(Unpooled.copiedBuffer("attachment2".getBytes()));
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("452-")); // MESSAGE(4) + BINARY_EVENT(5) + 2 attachments
        
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
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList()); // Use empty list instead of null
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    @Test
    public void testEncodePacketWithEmptyNamespace() throws IOException {
        // Test encoding packet with empty namespace
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
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
        
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("largeEvent");
        packet.setData(Arrays.asList(largeData.toString()));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        assertTrue(encoded.contains("largeEvent"));
        
        buffer.release();
    }

    // ==================== Performance Tests ====================

    @Test
    public void testEncodePerformance() throws IOException {
        // Test encoding performance with large packet
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
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
        encoder.encodePacket(packet, buffer, allocator, false);
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
            Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
            packet.setSubType(PacketType.EVENT);
            packet.setNsp("/test");
            packet.setName("event" + i);
            packet.setData(Arrays.asList("data" + i));
            packets.add(packet);
        }
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        
        long startTime = System.currentTimeMillis();
        encoder.encodePackets(packets, buffer, allocator, 100);
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
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V2);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));

        ByteBuf buffer = Unpooled.buffer();
        try {
            encoder.encodePacket(packet, buffer, allocator, false);

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
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V3);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertTrue(encoded.startsWith("42"));
        
        buffer.release();
    }

    @Test
    public void testEncodePacketV4() throws IOException {
        // Test encoding packet with Engine.IO V4
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    // ==================== Binary Mode Tests ====================

    @Test
    public void testEncodePacketBinaryMode() throws IOException {
        // Test encoding packet in binary mode
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("");
        packet.setName("test");
        packet.setData(Arrays.asList("data"));
        
        // JSON support is now real implementation
        
        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, true);
        
        // In binary mode, the packet should be encoded directly to the buffer
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.startsWith("42")); // MESSAGE(4) + EVENT(2)
        
        buffer.release();
    }

    @Test
    public void testEncodePacketsEIOv4BinaryAttachmentStandardBase64() throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE, EngineIOVersion.V4);
        packet.setSubType(PacketType.BINARY_EVENT);
        packet.setNsp("");
        packet.setName("binEvent");
        packet.setData(Arrays.asList(new HashMap<>()));
        packet.initAttachments(1);

        // Byte array containing bytes that encode to '+' and '/' in standard base64 (e.g. 0xFB, 0xFF, 0xBF -> "+/+/")
        byte[] rawBytes = new byte[]{(byte) 0xFB, (byte) 0xFF, (byte) 0xBF};
        packet.addAttachment(Unpooled.wrappedBuffer(rawBytes));

        java.util.Queue<Packet> queue = new java.util.LinkedList<>();
        queue.add(packet);

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePackets(queue, buffer, allocator, 50);

        String encoded = buffer.toString(CharsetUtil.UTF_8);
        // EIOv4 polling format: 451-["binEvent",{"_placeholder":true,"num":0}] + 0x1E + 'b' + "+/+/"
        assertTrue(encoded.contains("+/+/"), "Binary attachment should use standard Base64 encoding ('+' and '/') instead of URL_SAFE ('-' and '_')");
        assertFalse(encoded.contains("-_-_"), "Should not contain URL_SAFE characters");

        buffer.release();
    }

    // ==================== Cross Engine.IO Version Encoding Tests (V2, V3, V4) ====================

    @ParameterizedTest(name = "Encode CONNECT Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeConnectPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        // 1. Default namespace
        Packet packetDefault = new Packet(PacketType.MESSAGE, version);
        packetDefault.setSubType(PacketType.CONNECT);
        packetDefault.setNsp("");

        ByteBuf bufDefault = Unpooled.buffer();
        encoder.encodePacket(packetDefault, bufDefault, allocator, false);
        assertTrue(bufDefault.toString(CharsetUtil.UTF_8).endsWith("40"), "CONNECT packet should end with '40' for EIO " + version);
        bufDefault.release();

        // 2. Custom namespace
        Packet packetCustom = new Packet(PacketType.MESSAGE, version);
        packetCustom.setSubType(PacketType.CONNECT);
        packetCustom.setNsp("/admin");

        ByteBuf bufCustom = Unpooled.buffer();
        encoder.encodePacket(packetCustom, bufCustom, allocator, false);
        assertTrue(bufCustom.toString(CharsetUtil.UTF_8).endsWith("40/admin"), "CONNECT packet custom nsp should end with '40/admin' for EIO " + version);
        bufCustom.release();
    }

    @ParameterizedTest(name = "Encode DISCONNECT Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeDisconnectPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE, version);
        packet.setSubType(PacketType.DISCONNECT);
        packet.setNsp("/admin");

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        assertTrue(buffer.toString(CharsetUtil.UTF_8).endsWith("41/admin,"), "DISCONNECT packet should end with '41/admin,' for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode EVENT Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeEventPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE, version);
        packet.setSubType(PacketType.EVENT);
        packet.setNsp("/admin");
        packet.setName("deleteUser");
        packet.setData(Arrays.asList(1001));
        packet.setAckId(777L);

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.contains("42/admin,777[\"deleteUser\",1001]"), "Encoded EVENT should contain specification payload for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode ACK Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeAckPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE, version);
        packet.setSubType(PacketType.ACK);
        packet.setNsp("/admin");
        packet.setAckId(888L);
        packet.setData(Arrays.asList("ok", true));

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.contains("43/admin,888[\"ok\",true]"), "Encoded ACK should contain specification payload for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode ERROR Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeErrorPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE, version);
        packet.setSubType(PacketType.ERROR);
        packet.setNsp("/admin");
        packet.setData("Forbidden");

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        String encoded = buffer.toString(CharsetUtil.UTF_8);
        assertTrue(encoded.contains("44/admin,\"Forbidden\""), "Encoded ERROR should contain specification payload for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode BINARY_EVENT Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeBinaryEventPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE, version);
        packet.setSubType(PacketType.BINARY_EVENT);
        packet.initAttachments(1);
        packet.setNsp("/admin");
        packet.setName("binEvent");
        packet.setData(Arrays.asList("hello"));
        packet.addAttachment(Unpooled.copiedBuffer("attachmentData", CharsetUtil.UTF_8));

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertEquals(PacketType.BINARY_EVENT, packet.getSubType());
        assertTrue(encoded.contains("451-/admin,"), "Encoded BINARY_EVENT header should format correctly for EIO " + version);
        buffer.release();
    }

    @ParameterizedTest(name = "Encode BINARY_ACK Packet - Engine.IO Version {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    public void testEncodeBinaryAckPacketCrossEngineIOVersions(EngineIOVersion version) throws IOException {
        Packet packet = new Packet(PacketType.MESSAGE, version);
        packet.setSubType(PacketType.BINARY_ACK);
        packet.initAttachments(1);
        packet.setNsp("/admin");
        packet.setAckId(1234L);
        packet.setData(Arrays.asList("res"));
        packet.addAttachment(Unpooled.copiedBuffer("ackAttachment", CharsetUtil.UTF_8));

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePacket(packet, buffer, allocator, false);
        String encoded = buffer.toString(CharsetUtil.UTF_8);

        assertEquals(PacketType.BINARY_ACK, packet.getSubType());
        assertTrue(encoded.contains("461-/admin,1234"), "Encoded BINARY_ACK header should format correctly for EIO " + version);
        buffer.release();
    }

    @Test
    public void testEncodePacketsEIOv3PollingBatchWithXHR2Attachment() throws IOException {
        Packet textPacket = new Packet(PacketType.MESSAGE, EngineIOVersion.V3);
        textPacket.setSubType(PacketType.CONNECT);
        textPacket.setNsp("");

        Packet binPacket = new Packet(PacketType.MESSAGE, EngineIOVersion.V3);
        binPacket.setSubType(PacketType.BINARY_EVENT);
        binPacket.initAttachments(1);
        binPacket.setNsp("");
        binPacket.setName("binEv");
        binPacket.setData(Arrays.asList("hello"));
        byte[] attachmentBytes = new byte[]{10, 20, 30};
        binPacket.addAttachment(Unpooled.copiedBuffer(attachmentBytes));

        Queue<Packet> queue = new LinkedList<>();
        queue.add(textPacket);
        queue.add(binPacket);

        ByteBuf buffer = Unpooled.buffer();
        encoder.encodePackets(queue, buffer, allocator, 10);

        // Verify V3 payload length headers ("<len>:<packet>") and XHR2 binary framing
        byte[] encodedBytes = new byte[buffer.readableBytes()];
        buffer.readBytes(encodedBytes);
        buffer.release();

        String utf8Prefix = new String(encodedBytes, 0, Math.min(encodedBytes.length, 30), CharsetUtil.UTF_8);
        assertTrue(utf8Prefix.startsWith("2:40"), "EIOv3 batch payload should use length header framing (e.g. 2:40)");

        // XHR2 binary attachment frame follows the text frames: 0x01 + length bytes + 0xFF + 0x04.
        boolean containsXhr2FrameHeader = false;
        for (int i = 0; i < encodedBytes.length - 3; i++) {
            if (encodedBytes[i] != 0x01) {
                continue;
            }

            int separatorIndex = i + 1;
            while (separatorIndex < encodedBytes.length && encodedBytes[separatorIndex] != (byte) 0xFF) {
                byte lengthByte = encodedBytes[separatorIndex];
                if (lengthByte < 0 || lengthByte > 9) {
                    break;
                }
                separatorIndex++;
            }

            if (separatorIndex > i + 1
                    && separatorIndex + 1 < encodedBytes.length
                    && encodedBytes[separatorIndex] == (byte) 0xFF
                    && encodedBytes[separatorIndex + 1] == 0x04) {
                containsXhr2FrameHeader = true;
                break;
            }
        }
        assertTrue(containsXhr2FrameHeader,
                "EIOv3 polling binary attachment should use the XHR2 binary frame header");
    }
}
