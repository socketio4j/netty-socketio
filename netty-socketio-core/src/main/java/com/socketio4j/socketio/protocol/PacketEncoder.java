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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.annotation.Internal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.base64.Base64Dialect;
import io.netty.util.CharsetUtil;

@Internal
public class PacketEncoder {

    private static final byte[] BINARY_HEADER = "b4".getBytes(CharsetUtil.UTF_8);
    private static final byte[] B64_DELIMITER = new byte[] {':'};
    private static final byte[] JSONP_HEAD = "___eio[".getBytes(CharsetUtil.UTF_8);
    private static final byte[] JSONP_START = "]('".getBytes(CharsetUtil.UTF_8);
    private static final byte[] JSONP_END = "');".getBytes(CharsetUtil.UTF_8);

    private final JsonSupport jsonSupport;
    private final Configuration configuration;

    public PacketEncoder(Configuration configuration, JsonSupport jsonSupport) {
        this.jsonSupport = jsonSupport;
        this.configuration = configuration;
    }

    public JsonSupport getJsonSupport() {
        return jsonSupport;
    }
    
    public ByteBuf allocateBuffer(ByteBufAllocator allocator) {
        if (configuration.isPreferDirectBuffer()) {
            return allocator.ioBuffer();
        }

        return allocator.heapBuffer();
    }

    /**
     * Encodes Engine.IO polling responses using Base64 text encoding.
     *
     * <p>If {@code jsonpIndex != null}, the encoded payload is additionally wrapped
     * in a JSONP callback for legacy clients.</p>
     *
     * <p>Supports the following Engine.IO polling modes:</p>
     * <ul>
     *   <li><b>Base64 polling</b> ({@code b64=1})</li>
     *   <li><b>JSONP polling</b> ({@code j=<index>}), which uses the same Base64
     *       payload encoding wrapped in a JSONP callback.</li>
     * </ul>
     *
     * @param engineIOVersion Engine.IO protocol version.
     * @param jsonpIndex JSONP callback index, or {@code null} for standard Base64
     *                   polling.
     * @param packets packets to encode.
     * @param out destination buffer.
     * @param allocator buffer allocator.
     * @param limit maximum number of packets to encode.
     * @throws IOException if packet encoding fails.
     */
    public void encodeJsonP(EngineIOVersion engineIOVersion,
                            Integer jsonpIndex,
                            Queue<Packet> packets,
                            ByteBuf out,
                            ByteBufAllocator allocator,
                            int limit) throws IOException {

        boolean wrapJsonp = jsonpIndex != null;

        ByteBuf buf = allocateBuffer(allocator);
        try {
            int i = 0;

            while (true) {
                Packet packet = packets.poll();
                if (packet == null || i == limit) {
                    break;
                }

                ByteBuf packetBuf = allocateBuffer(allocator);
                try {
                    EncodeResult encodeResult =
                            encodePacket(engineIOVersion, packet, packetBuf, allocator, true);

                    int packetSize = packetBuf.writerIndex();
                    buf.writeBytes(toChars(packetSize));
                    buf.writeBytes(B64_DELIMITER);
                    buf.writeBytes(packetBuf);

                    for (ByteBuf attachment : encodeResult.getAttachments()) {
                        ByteBuf encodedBuf = Base64.encode(attachment, Base64Dialect.STANDARD);
                        try {
                            buf.writeBytes(toChars(encodedBuf.readableBytes() + 2));
                            buf.writeBytes(B64_DELIMITER);
                            buf.writeBytes(BINARY_HEADER);
                            buf.writeBytes(encodedBuf);
                        } finally {
                            encodedBuf.release();
                        }
                    }
                } finally {
                    packetBuf.release();
                }

                i++;
            }

            if (wrapJsonp) {
                out.writeBytes(JSONP_HEAD);
                out.writeBytes(toChars(jsonpIndex));
                out.writeBytes(JSONP_START);
            }

            processUtf8(buf, out, wrapJsonp);

            if (wrapJsonp) {
                out.writeBytes(JSONP_END);
            }
        } finally {
            buf.release();
        }
    }

    private void processUtf8(ByteBuf in, ByteBuf out, boolean jsonpMode) {
        while (in.isReadable()) {
            short value = (short) (in.readByte() & 0xFF);
            if (value >>> 7 == 0) {
                if (jsonpMode && (value == '\\' || value == '\'')) {
                    out.writeByte('\\');
                }
                out.writeByte(value);
            } else {
                out.writeByte(((value >>> 6) | 0xC0));
                out.writeByte(((value & 0x3F) | 0x80));
            }
        }
    }

    public EncodePacketsResult encodePackets(EngineIOVersion engineIOVersion,
                                             Queue<Packet> packets,
                                             ByteBuf buffer,
                                             ByteBufAllocator allocator,
                                             int limit) throws IOException {

        int count = 0;
        boolean first = true;
        boolean hasBinary = false;

        if (EngineIOVersion.V4.equals(engineIOVersion)) {

            while (count < limit) {
                Packet packet = packets.poll();
                if (packet == null) {
                    break;
                }

                if (!first) {
                    buffer.writeByte(0x1E);
                }

                EncodeResult result =
                        encodePacket(engineIOVersion, packet, buffer, allocator, false);

                hasBinary |= result.hasAttachments();

                for (ByteBuf attachment : result.getAttachments()) {
                    buffer.writeByte(0x1E);
                    buffer.writeByte('b');

                    ByteBuf encoded = Base64.encode(attachment, Base64Dialect.STANDARD);
                    try {
                        buffer.writeBytes(encoded);
                    } finally {
                        encoded.release();
                    }
                }

                first = false;
                count++;
            }

            return new EncodePacketsResult(hasBinary);
        }

        if (EngineIOVersion.V2.equals(engineIOVersion)
                || EngineIOVersion.V3.equals(engineIOVersion)) {

            class EncodedPacket {
                final ByteBuf packet;
                final EncodeResult result;

                EncodedPacket(ByteBuf packet, EncodeResult result) {
                    this.packet = packet;
                    this.result = result;
                }
            }

            List<EncodedPacket> encodedPackets = new ArrayList<>();

            try {

                //
                // First pass - encode everything once
                //
                while (count < limit) {

                    Packet packet = packets.poll();
                    if (packet == null) {
                        break;
                    }

                    ByteBuf packetBuf = allocator.buffer();

                    EncodeResult result =
                            encodePacket(engineIOVersion,
                                    packet,
                                    packetBuf,
                                    allocator,
                                    false);

                    hasBinary |= result.hasAttachments();

                    encodedPackets.add(new EncodedPacket(packetBuf, result));

                    count++;
                }

                //
                // Second pass - write using the chosen framing
                //
                for (EncodedPacket encoded : encodedPackets) {

                    if (hasBinary) {

                        // Binary Engine.IO payload
                        buffer.writeByte(0);
                        buffer.writeBytes(longToBytes(encoded.packet.readableBytes()));
                        buffer.writeByte(0xFF);

                    } else {

                        // Text Engine.IO payload
                        int chars =
                                encoded.packet.toString(CharsetUtil.UTF_8).length();

                        buffer.writeCharSequence(
                                Integer.toString(chars),
                                CharsetUtil.US_ASCII);

                        buffer.writeByte(':');
                    }

                    buffer.writeBytes(encoded.packet);

                    for (ByteBuf attachment : encoded.result.getAttachments()) {
                        buffer.writeByte(1);
                        buffer.writeBytes(longToBytes(attachment.readableBytes() + 1));
                        buffer.writeByte(0xFF);
                        buffer.writeByte(4);
                        buffer.writeBytes(attachment);
                    }
                }

            } finally {

                for (EncodedPacket encoded : encodedPackets) {
                    encoded.packet.release();
                }
            }

            return new EncodePacketsResult(hasBinary);
        }

        throw new IllegalStateException(
                "Unsupported Engine.IO version: " + engineIOVersion);
    }

    private byte toChar(int number) {
        return (byte) (number ^ 0x30);
    }

    static final char[] DIGIT_TENS = {'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '1', '1', '1', '1',
            '1', '1', '1', '1', '1', '1', '2', '2', '2', '2', '2', '2', '2', '2', '2', '2', '3', '3', '3',
            '3', '3', '3', '3', '3', '3', '3', '4', '4', '4', '4', '4', '4', '4', '4', '4', '4', '5', '5',
            '5', '5', '5', '5', '5', '5', '5', '5', '6', '6', '6', '6', '6', '6', '6', '6', '6', '6', '7',
            '7', '7', '7', '7', '7', '7', '7', '7', '7', '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
            '9', '9', '9', '9', '9', '9', '9', '9', '9', '9'};

    static final char[] DIGIT_ONES = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3',
            '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2',
            '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1',
            '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0',
            '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};

    static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e',
            'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x',
            'y', 'z'};

    static final int[] SIZE_TABLE = {9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999,
            Integer.MAX_VALUE};

    // Requires positive x
    static int stringSize(long x) {
        for (int i = 0;; i++) {
            if (x <= SIZE_TABLE[i]) {
                return i + 1;
            }
        }
    }

    static void getChars(long i, int index, byte[] buf) {
        long q;
        long r;
        int charPos = index;
        byte sign = 0;

        if (i < 0) {
            sign = '-';
            i = -i;
        }

        // Generate two digits per iteration
        while (i >= 65536) {
            q = i / 100;
            // really: r = i - (q * 100);
            r = i - ((q << 6) + (q << 5) + (q << 2));
            i = q;
            buf[--charPos] = (byte) DIGIT_ONES[(int) r];
            buf[--charPos] = (byte) DIGIT_TENS[(int) r];
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i <= 65536, i);
        do {
            q = (i * 52429) >>> (16 + 3);
            r = i - ((q << 3) + (q << 1)); // r = i-(q*10) ...
            buf[--charPos] = (byte) DIGITS[(int) r];
            i = q;
        } while (i != 0);
        if (sign != 0) {
            buf[--charPos] = sign;
        }
    }

    public static byte[] toChars(long i) {
        int size;
        if (i < 0) {
            size = stringSize(-i) + 1;
        } else {
            size = stringSize(i);
        }
        byte[] buf = new byte[size];
        getChars(i, size, buf);
        return buf;
    }

    public static byte[] longToBytes(long number) {
        // Handle zero case
        if (number == 0) {
            return new byte[]{0};
        }
        
        // Calculate length without using Math.log10 for better performance
        int length = 0;
        long temp = number;
        while (temp > 0) {
            temp /= 10;
            length++;
        }
        
        byte[] res = new byte[length];
        int i = length;
        
        // Convert digits
        while (number > 0) {
            res[--i] = (byte) (number % 10);
            number /= 10;
        }
        
        return res;
    }

    public EncodeResult encodePacket(EngineIOVersion version, Packet packet, ByteBuf buffer,
                             ByteBufAllocator allocator,
                             boolean binary) throws IOException {

        ByteBuf buf;
        if (binary) {
            buf = buffer;
        } else {
            buf = allocateBuffer(allocator);
        }
        List<ByteBuf> attachments = Collections.emptyList();
        buf.writeByte(toChar(packet.getType().getValue()));

        try {
            switch (packet.getType()) {

                case PONG:
                    buf.writeBytes(packet.getData().toString().getBytes(CharsetUtil.UTF_8));
                    break;

                case OPEN: {
                    ByteBufOutputStream out = new ByteBufOutputStream(buf);
                    jsonSupport.writeValue(out, packet.getData());
                    break;
                }

                case MESSAGE: {

                    ByteBuf encBuf = null;
                    PacketType subType = packet.getSubType();

                    if (subType == PacketType.ERROR) {
                        encBuf = allocateBuffer(allocator);
                        ByteBufOutputStream out = new ByteBufOutputStream(encBuf);
                        jsonSupport.writeValue(out, packet.getData());
                    }

                    if (subType == PacketType.EVENT || subType == PacketType.ACK) {

                        List<Object> values = new ArrayList<>();
                        if (subType == PacketType.EVENT) {
                            values.add(packet.getName());
                        }

                        values.addAll(packet.getData());

                        encBuf = allocateBuffer(allocator);
                        ByteBufOutputStream out = new ByteBufOutputStream(encBuf);
                        jsonSupport.writeValue(out, values);

                        if (!jsonSupport.getArrays().isEmpty()) {

                            attachments = new ArrayList<>(jsonSupport.getArrays().size());

                            for (byte[] array : jsonSupport.getArrays()) {
                                attachments.add(Unpooled.wrappedBuffer(array));
                            }

                            if (subType == PacketType.ACK) {
                                subType = PacketType.BINARY_ACK;
                            } else {
                                subType = PacketType.BINARY_EVENT;
                            }
                        }
                    }

                    buf.writeByte(toChar(subType.getValue()));

                    if (!attachments.isEmpty()) {
                        buf.writeBytes(toChars(attachments.size()));
                        buf.writeByte('-');
                    }

                    if (subType == PacketType.CONNECT) {

                        if (!packet.getNsp().isEmpty()) {
                            buf.writeBytes(packet.getNsp().getBytes(CharsetUtil.UTF_8));
                        }

                        if (EngineIOVersion.V4.equals(version)
                                && packet.getData() != null) {

                            if (!packet.getNsp().isEmpty()) {
                                buf.writeByte(',');
                            }

                            ByteBufOutputStream out = new ByteBufOutputStream(buf);
                            jsonSupport.writeValue(out, packet.getData());
                        }

                    } else {

                        if (!packet.getNsp().isEmpty()) {
                            buf.writeBytes(packet.getNsp().getBytes(CharsetUtil.UTF_8));
                            buf.writeByte(',');
                        }
                    }

                    if (packet.getAckId() != null) {
                        buf.writeBytes(toChars(packet.getAckId()));
                    }

                    if (encBuf != null) {
                        buf.writeBytes(encBuf);
                        encBuf.release();
                    }

                    break;
                }
            }

        } finally {

            if (!binary) {

                if (EngineIOVersion.V2.equals(version)) {
                    buffer.writeByte(0);
                    buffer.writeBytes(longToBytes(buf.writerIndex()));
                    buffer.writeByte(0xff);
                }

                buffer.writeBytes(buf);
                buf.release();
            }
        }
        return new EncodeResult(buffer, attachments);
    }

    public static int find(ByteBuf buffer, ByteBuf searchValue) {
        for (int i = buffer.readerIndex(); i < buffer.readerIndex() + buffer.readableBytes(); i++) {
            if (isValueFound(buffer, i, searchValue)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isValueFound(ByteBuf buffer, int index, ByteBuf search) {
        for (int i = 0; i < search.readableBytes(); i++) {
            if (buffer.getByte(index + i) != search.getByte(i)) {
                return false;
            }
        }
        return true;
    }

}
