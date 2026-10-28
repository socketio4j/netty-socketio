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
import java.io.UnsupportedEncodingException;
import java.util.LinkedList;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.AckCallback;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.annotation.Internal;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.namespace.Namespace;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;

@Internal
public class PacketDecoder {

    private static final Logger log = LoggerFactory.getLogger(PacketDecoder.class);
    private final UTF8CharsScanner utf8scanner = new UTF8CharsScanner();

    private final ByteBuf quotes = Unpooled.copiedBuffer("\"", CharsetUtil.UTF_8);

    private final JsonSupport jsonSupport;
    private final AckManager ackManager;

    public PacketDecoder(JsonSupport jsonSupport, AckManager ackManager) {
        this.jsonSupport = jsonSupport;
        this.ackManager = ackManager;
    }

    private boolean isStringPacket(ByteBuf content) {
        return content.getByte(content.readerIndex()) == 0x0;
    }

    /**
     * Engine.IO v2/v3 encodes a polling payload containing binary as a series
     * of frames: {@code <0 = string | 1 = binary><byte-valued length><0xFF><data>}.
     * The length digits are bytes in the 0..9 range, not ASCII characters.
     */
    private boolean hasLegacyBinaryPayloadHeader(ByteBuf buffer) {
        if (buffer.readableBytes() < 3) {
            return false;
        }

        int readerIndex = buffer.readerIndex();
        byte marker = buffer.getByte(readerIndex);
        if (marker != 0 && marker != 1) {
            return false;
        }

        int maxHeaderLength = Math.min(buffer.readableBytes(), 12);
        int separatorIndex = buffer.bytesBefore(maxHeaderLength, (byte) -1);
        if (separatorIndex <= 1) {
            return false;
        }

        for (int i = 1; i < separatorIndex; i++) {
            byte digit = buffer.getByte(readerIndex + i);
            if ((digit < 0 || digit > 9) && (digit < '0' || digit > '9')) {
                return false;
            }
        }
        return true;
    }

    /**
     * True zero-copy optimized version of preprocessJson that works directly with ByteBuf
     * without string conversion and without creating new ByteBuf instances.
     * 
     * @param jsonIndex JSONP index, if null then no JSONP processing is needed
     * @param content the input ByteBuf containing the packet data
     * @return processed ByteBuf with true zero-copy optimization
     * @throws UnsupportedEncodingException if UTF-8 encoding is not supported
     */
    public ByteBuf preprocessJson(Integer jsonIndex, ByteBuf content) throws UnsupportedEncodingException {
        // The caller must ensure the buffer stays alive (retain if needed).
        // We mutate in-place to avoid creating derived buffers with tricky refCnt ownership.
        urlDecodeInPlace(content);

        if (jsonIndex != null) {
            replaceEscapedNewlinesInPlace(content);

            // Skip "d=" prefix (2 bytes) by adjusting reader index
            if (content.readableBytes() >= 2) {
                int ri = content.readerIndex();
                if (content.getByte(ri) == (byte) 'd' && content.getByte(ri + 1) == (byte) '=') {
                    content.readerIndex(ri + 2);
                } else {
                    throw new IllegalArgumentException(
                            "Invalid JSONP format: missing 'd=' prefix, got bytes: " +
                                     String.format("0x%02X 0x%02X", content.getByte(ri) & 0xFF, content.getByte(ri + 1) & 0xFF));
                }
            }
        }

        return content;
    }
    
    /**
     * URL decode a ByteBuf in-place without creating new ByteBuf
     */
    private void urlDecodeInPlace(ByteBuf buffer) throws UnsupportedEncodingException {
        int readerIndex = buffer.readerIndex();
        int writerIndex = buffer.writerIndex();
        int readPos = readerIndex;
        int writePos = readerIndex;
        
        while (readPos < writerIndex) {
            byte b = buffer.getByte(readPos);
            
            if (b == '%' && readPos + 2 < writerIndex) {
                // Handle URL encoded characters
                byte hex1 = buffer.getByte(readPos + 1);
                byte hex2 = buffer.getByte(readPos + 2);
                
                if (isHexDigit(hex1) && isHexDigit(hex2)) {
                    int decoded = (hexToInt(hex1) << 4) | hexToInt(hex2);
                    buffer.setByte(writePos, (byte) decoded);
                    writePos++;
                    readPos += 3; // Skip the next two bytes
                } else {
                    buffer.setByte(writePos, b);
                    writePos++;
                    readPos++;
                }
            } else if (b == '+') {
                // Handle space encoding
                buffer.setByte(writePos, (byte) ' ');
                writePos++;
                readPos++;
            } else {
                buffer.setByte(writePos, b);
                writePos++;
                readPos++;
            }
        }
        
        // Adjust writer index to reflect the new length
        buffer.writerIndex(writePos);
    }
    
    /**
     * Replace escaped newlines "\\n" with "\n" in-place
     * Note: This reduces the buffer size from 3 bytes("\\n") to 2 byte("\n") per replacement
     * because unescaping of new lines can be done safely on server-side(c) socket.io.js
     * @see https://github.com/Automattic/socket.io-client/blob/1.3.3/socket.io.js#L2682
     */
    private void replaceEscapedNewlinesInPlace(ByteBuf buffer) {
        int readerIndex = buffer.readerIndex();
        int writerIndex = buffer.writerIndex();
        int readPos = readerIndex;
        int writePos = readerIndex;
        
        while (readPos < writerIndex) {
            byte b = buffer.getByte(readPos);
            
            // Check for "\\\\n" pattern (real 3 bytes: "\\n")
            if (b == '\\' && readPos + 2 < writerIndex) {
                byte b1 = buffer.getByte(readPos + 1);
                byte b2 = buffer.getByte(readPos + 2);

                if (b1 == '\\' && b2 == 'n') {
                    buffer.setByte(writePos, (byte) '\\');
                    writePos++;
                    buffer.setByte(writePos, (byte) 'n');
                    writePos++;
                    readPos += 3; // Skip both bytes
                } else {
                    buffer.setByte(writePos, b);
                    writePos++;
                    readPos++;
                }
            } else {
                buffer.setByte(writePos, b);
                writePos++;
                readPos++;
            }
        }
        
        // Adjust writer index to reflect the new length
        buffer.writerIndex(writePos);
    }
    
    /**
     * Check if a byte represents a hexadecimal digit
     */
    private boolean isHexDigit(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'F') || (b >= 'a' && b <= 'f');
    }
    
    /**
     * Convert a hexadecimal digit byte to its integer value
     */
    private int hexToInt(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        } else if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        } else if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        throw new IllegalArgumentException("Invalid hex digit: " + (char) b);
    }

    // fastest way to parse chars to int
    private long readLong(ByteBuf chars, int length) {
        if (length < 0 || length > chars.readableBytes()) {
            throw new IllegalArgumentException("Invalid numeric field length: " + length);
        }
        long result = 0;
        for (int i = chars.readerIndex(); i < chars.readerIndex() + length; i++) {
            byte value = chars.getByte(i);
            if (value < '0' || value > '9') {
                throw new IllegalArgumentException("Non-decimal byte in numeric packet field: " + (char) value);
            }
            int digit = value - '0';
            if (result > (Long.MAX_VALUE - digit) / 10) {
                throw new IllegalArgumentException("Numeric packet field overflow");
            }
            result = result * 10 + digit;
        }
        chars.readerIndex(chars.readerIndex() + length);
        return result;
    }

    /**
     * Engine.IO v2/v3's XHR2 binary wrapper encodes its length as either
     * byte-valued digits (0..9) or ASCII digits.  This representation is
     * specific to that wrapper; all text packet headers use {@link #readLong}
     * and must contain ASCII decimal characters.
     */
    private long readLegacyBinaryLength(ByteBuf chars, int length) {
        if (length < 0 || length > chars.readableBytes()) {
            throw new IllegalArgumentException("Invalid legacy binary length: " + length);
        }
        long result = 0;
        for (int i = chars.readerIndex(); i < chars.readerIndex() + length; i++) {
            byte value = chars.getByte(i);
            int digit;
            if (value >= 0 && value <= 9) {
                digit = value;
            } else if (value >= '0' && value <= '9') {
                digit = value - '0';
            } else {
                throw new IllegalArgumentException("Non-decimal byte in legacy binary length: " + value);
            }
            if (result > (Long.MAX_VALUE - digit) / 10) {
                throw new IllegalArgumentException("Legacy binary length overflow");
            }
            result = result * 10 + digit;
        }
        chars.readerIndex(chars.readerIndex() + length);
        return result;
    }

    private PacketType readType(ByteBuf buffer) {
        byte value = buffer.readByte();
        if (value < '0' || value > '6') {
            throw new IllegalArgumentException("Invalid Engine.IO packet type: " + (char) value);
        }
        int typeId = value - '0';
        return PacketType.valueOf(typeId);
    }

    private PacketType readInnerType(ByteBuf buffer) {
        byte value = buffer.readByte();
        if (value < '0' || value > '6') {
            throw new IllegalArgumentException("Invalid Socket.IO packet type: " + (char) value);
        }
        int typeId = value - '0';
        return PacketType.valueOfInner(typeId);
    }

    private boolean hasLengthHeader(ByteBuf buffer) {
        for (int i = 0; i < Math.min(buffer.readableBytes(), 10); i++) {
            byte b = buffer.getByte(buffer.readerIndex() + i);
            if (b == (byte) ':' && i > 0) {
                return true;
            }
            if (b > 57 || b < 48) {
                return false;
            }
        }
        return false;
    }

    public Packet decodePackets(ByteBuf buffer, ClientHead client) throws IOException {
        return decodePackets(buffer, client, client.getCurrentTransport());
    }

    public @Nullable Packet decodePackets(ByteBuf buffer,
                                          ClientHead client,
                                          Transport transport) throws IOException {

        if (transport == Transport.POLLING && hasLegacyBinaryPayloadHeader(buffer)) {
            return decodeLegacyBinaryPayload(buffer, client, transport);
        }

        Packet pending = client.getLastBinaryPacket();

        if (pending != null
                && pending.hasAttachments()
                && !pending.isAttachmentsLoaded()) {

            if (transport == Transport.WEBSOCKET) {
                return decode(client, buffer, transport);
            }
        }

        if (isStringPacket(buffer)) {
            return decodeWithStringHeader(buffer, client, transport);
        }

        if (hasLengthHeader(buffer)) {
            return decodeWithLengthHeader(buffer, client, transport);
        }

        return decode(client, buffer, transport);
    }

    private Packet decodeLegacyBinaryPayload(ByteBuf buffer,
                                             ClientHead client,
                                             Transport transport) throws IOException {
        byte marker = buffer.readByte();
        int maxHeaderLength = Math.min(buffer.readableBytes(), 11);
        int lengthHeaderSize = buffer.bytesBefore(maxHeaderLength, (byte) -1);
        if (lengthHeaderSize <= 0) {
            throw new IOException("Malformed legacy polling payload: missing length separator");
        }

        long rawLength = readLegacyBinaryLength(buffer, lengthHeaderSize);
        if (rawLength < 0 || rawLength > Integer.MAX_VALUE) {
            throw new IOException("Malformed legacy polling payload: length overflow " + rawLength);
        }
        if (!buffer.isReadable() || buffer.readByte() != (byte) -1) {
            throw new IOException("Malformed legacy polling payload: missing 0xFF separator");
        }

        int length = (int) rawLength;
        if (length > buffer.readableBytes()) {
            throw new IOException("Malformed legacy polling payload: length " + length
                    + " exceeds remaining bytes " + buffer.readableBytes());
        }
        ByteBuf payload = buffer.readSlice(length);

        if (marker == 0) {
            Packet pending = client.getLastBinaryPacket();
            if (pending != null && pending.hasAttachments() && !pending.isAttachmentsLoaded()
                    && payload.isReadable() && payload.getByte(payload.readerIndex()) == 'b') {
                return addAttachment(client, payload, pending, transport);
            }
            return decode(client, payload, transport);
        }

        Packet pending = client.getLastBinaryPacket();
        if (pending == null || !pending.hasAttachments() || pending.isAttachmentsLoaded()) {
            throw new IOException("Unexpected binary Engine.IO polling payload without a pending attachment packet");
        }
        return addLegacyPollingBinaryAttachment(client, payload, pending);
    }

    /**
     * Decode packet with string header format
     * Handles packets that start with 0x0 byte
     */
    private Packet decodeWithStringHeader(ByteBuf buffer, ClientHead client, Transport transport) throws IOException {
        int maxLength = Math.min(buffer.readableBytes(), 10);
        int headEndIndex = buffer.bytesBefore(maxLength, (byte) -1);
        if (headEndIndex == -1) {
            headEndIndex = buffer.bytesBefore(maxLength, (byte) 0x3f);
        }
        int len = (int) readLong(buffer, headEndIndex);
        return decodeFrame(buffer, client, len, transport);
    }

    /**
     * Decode packet with length header format
     * Handles packets with format "length:data"
     */
    private Packet decodeWithLengthHeader(ByteBuf buffer, ClientHead client, Transport transport) throws IOException {
        int lengthEndIndex = buffer.bytesBefore((byte) ':');
        int lenHeader = (int) readLong(buffer, lengthEndIndex);
        int len = utf8scanner.getActualLength(buffer, lenHeader);
        return decodeFrame(buffer, client, len, transport);
    }

    /**
     * Common frame decoding logic
     * Extracts frame data and advances buffer position
     */
    private Packet decodeFrame(ByteBuf buffer, ClientHead client, int len, Transport transport) throws IOException {
        ByteBuf frame = buffer.slice(buffer.readerIndex() + 1, len);
        buffer.readerIndex(buffer.readerIndex() + 1 + len);
        return decode(client, frame, transport);
    }

    private String readString(ByteBuf frame) {
        return readString(frame, frame.readableBytes());
    }

    private String readString(ByteBuf frame, int size) {
        byte[] bytes = new byte[size];
        frame.readBytes(bytes);
        return new String(bytes, CharsetUtil.UTF_8);
    }

    private @Nullable Packet decode(ClientHead head, ByteBuf frame, Transport transport) throws IOException {

        Packet lastPacket = head.getLastBinaryPacket();
        // Assume attachments follow.
        if (
                lastPacket != null
                && lastPacket.hasAttachments()
                && !lastPacket.isAttachmentsLoaded()
        ) {
            return addAttachment(head, frame, lastPacket, transport);
        }

        // Skip any leading 0x1E record separators (e.g. payload starting with 0x1e or consecutive 0x1e delimiters)
        while (frame.readableBytes() > 0 && frame.getByte(frame.readerIndex()) == 0x1E) {
            frame.skipBytes(1);
        }
        if (!frame.isReadable()) {
            return null;
        }

        final int separatorPos = frame.bytesBefore((byte) 0x1E);
        final ByteBuf packetBuf;
        if (separatorPos >= 0) {
            packetBuf = frame.readSlice(separatorPos);
            frame.skipBytes(1); // skip 0x1E separator
        } else {
            packetBuf = frame;
        }

        if (!packetBuf.isReadable()) {
            return null;
        }

        PacketType type = readType(packetBuf);
        Packet packet = new Packet(type);

        if (type == PacketType.PING || type == PacketType.PONG) {
            packet.setData(readString(packetBuf));
            return packet;
        }

        if (!packetBuf.isReadable()) {
            return packet;
        }

        PacketType innerType = readInnerType(packetBuf);
        packet.setSubType(innerType);

        parseHeader(packetBuf, packet, innerType);
        parseBody(head, packetBuf, packet);
        return packet;
    }

    private void parseHeader(ByteBuf frame, Packet packet, PacketType innerType) {
        int endIndex = frame.bytesBefore((byte) '[');
        if (endIndex <= 0) {
            return;
        }

        int attachmentsDividerIndex = frame.bytesBefore(endIndex, (byte) '-');
        boolean hasAttachments = attachmentsDividerIndex != -1;
        if (hasAttachments && (PacketType.BINARY_EVENT.equals(innerType)
                || PacketType.BINARY_ACK.equals(innerType))) {
            int attachments = (int) readLong(frame, attachmentsDividerIndex);
            packet.initAttachments(attachments);
            frame.readerIndex(frame.readerIndex() + 1);

            endIndex -= attachmentsDividerIndex + 1;
        }
        if (endIndex == 0) {
            return;
        }

        // TODO optimize: directly work with ByteBuf without string conversion
        boolean hasNsp = frame.bytesBefore(endIndex, (byte) ',') != -1;
        if (hasNsp) {
            String nspAckId = readString(frame, endIndex);
            String[] parts = nspAckId.split(",");
            String nsp = parts[0];
            packet.setNsp(nsp);
            if (parts.length > 1) {
                String ackId = parts[1];
                packet.setAckId(Long.valueOf(ackId));
            }
        } else {
            long ackId = readLong(frame, endIndex);
            packet.setAckId(ackId);
        }
    }

    /**
     * Decodes and appends an incoming binary attachment to the given packet.
     * <p>
     * Depending on the negotiated Engine.IO version and transport, the incoming buffer
     * has different frame layouts:
     * </p>
     * 
     * <h3>Engine.IO v3 (Socket.IO 2.x and older)</h3>
     * <ul>
     *   <li>
     *     <b>WebSocket (Raw Binary Frame):</b>
     *     <pre>
     *     +---------------+---------------------------------+
     *     | Byte 0        | Bytes 1..N                      |
     *     +---------------+---------------------------------+
     *     | Type (0x04)   | Raw binary payload              |
     *     +---------------+---------------------------------+
     *     </pre>
     *     The leading byte value 4 (Engine.IO MESSAGE packet type) is stripped, and the 
     *     remainder is base64-encoded and appended as an attachment.
     *   </li>
     *   <li>
     *     <b>WebSocket/Polling (Base64 Text Frame):</b>
     *     <pre>
     *     +-----------------+-------------------------------+
     *     | Bytes 0..1      | Bytes 2..N                    |
     *     +-----------------+-------------------------------+
     *     | Prefix ("b4")   | Base64 string payload         |
     *     +-----------------+-------------------------------+
     *     </pre>
     *     The leading ASCII prefix "b4" is stripped, and the remaining base64 payload is 
     *     appended directly without double-encoding.
     *     <br>
     *     Ref: <a href="https://github.com/socketio/engine.io-protocol/tree/v3#packet-string-encoding">Engine.IO v3 Packet String Encoding Spec</a>
     *     <blockquote>
     *     "Sometimes, it is not possible to send binary data over the transport [...]. In that case, 
     *     the packet is encoded as a string, and prepended with a 'b' character. For example: a packet 
     *     of type message containing the buffer &lt;01 02 03&gt; is encoded as 'b4AQID'"
     *     </blockquote>
     *   </li>
     *   <li>
     *     <b>Polling (Raw Binary Wrapper):</b>
     *     <pre>
     *     +--------+---------------+--------+---------------+--------------------+
     *     | Byte 0 | Bytes 1..K    | Byte K | Byte K+1      | Bytes K+2..N       |
     *     +--------+---------------+--------+---------------+--------------------+
     *     | 0x01   | Length (ASCII) | 0xFF   | Type (0x04)   | Raw binary payload |
     *     +--------+---------------+--------+---------------+--------------------+
     *     </pre>
     *     The binary envelope is stripped to retrieve the inner packet, which is then 
     *     processed normally (stripping the type prefix as described above).
     *     <br>
     *     Ref: <a href="https://github.com/socketio/engine.io-protocol/tree/v3#payload">Engine.IO v3 Payload Spec</a>
     *     <blockquote>
     *     "If the payload contains at least one binary packet, the payload is encoded as a binary buffer:
     *      - a binary indicator: 1 (representing a binary packet) or 0 (representing a string packet)
     *      - the length of the packet (as a series of characters)
     *      - a separator: 255
     *      - the packet itself"
     *     </blockquote>
     *   </li>
     * </ul>
     * 
     * <h3>Engine.IO v4 (Socket.IO 3.x and newer)</h3>
     * <ul>
     *   <li>
     *     <b>WebSocket/Polling (Raw Binary Frame):</b>
     *     <pre>
     *     +-------------------------------------------------+
     *     | Bytes 0..N                                      |
     *     +-------------------------------------------------+
     *     | Raw binary payload                              |
     *     +-------------------------------------------------+
     *     </pre>
     *     Engine.IO v4 does not prepend any packet types or metadata to binary attachments. 
     *     The entire buffer is base64-encoded as-is and stored.
     *     <br>
     *     Ref: <a href="https://socket.io/docs/v4/engine-io-protocol/">Engine.IO v4 Protocol Spec</a>
     *     <blockquote>
     *     "Binary packets are sent as-is without any modifications."
     *     </blockquote>
     *   </li>
     * </ul>
     *
     * @param head         the client connection head
     * @param frame        the incoming byte buffer frame
     * @param binaryPacket the packet being assembled
     * @return the packet if fully assembled (all attachments loaded), or an empty MESSAGE packet
     * @throws IOException if a decoding error occurs
     */
    private Packet addAttachment(ClientHead head, ByteBuf frame, Packet binaryPacket, Transport transport) throws IOException {
        EngineIOVersion version = head.getEngineIOVersion();
        if (version == null) {
            log.warn("addAttachment called with null engineIOVersion for session {}, treating as V4",
                    head.getSessionId());
            version = EngineIOVersion.V4;
        }

        int ri = frame.readerIndex();
        if (transport == Transport.POLLING) {
            boolean wrapperFound = false;

            // 1. EIOv2/v3 Polling binary payload wrapper: 0x01 + length + 0xFF + 0x04 + payload
            if (frame.readableBytes() > 0 && frame.getByte(ri) == 1) {
                frame.readByte(); // skip 0x01
                int maxLength = Math.min(frame.readableBytes(), 10);
                int headEndIndex = frame.bytesBefore(maxLength, (byte) -1);
                if (headEndIndex > 0) {
                    for (int i = 0; i < headEndIndex; i++) {
                        byte b = frame.getByte(frame.readerIndex() + i);
                        if ((b < 0 || b > 9) && (b < '0' || b > '9')) {
                            throw new IOException("Malformed polling wrapper: non-digit character in length header");
                        }
                    }
                    long rawLen = readLegacyBinaryLength(frame, headEndIndex);
                    if (rawLen < 0 || rawLen > Integer.MAX_VALUE) {
                        throw new IOException("Malformed polling wrapper: length overflow " + rawLen);
                    }
                    int len = (int) rawLen;
                    int payloadStart = frame.readerIndex() + 1; // skip 0xFF separator
                    if (payloadStart + len > frame.writerIndex()) {
                        throw new IOException("Malformed polling wrapper: length " + len
                                + " exceeds remaining frame bytes " + (frame.writerIndex() - payloadStart));
                    }
                    ByteBuf payload = frame.slice(payloadStart, len);
                    frame.readerIndex(payloadStart + len);
                    wrapperFound = true;

                    // Strip leading 0x04 type prefix if present
                    int payloadRi = payload.readerIndex();
                    if (payload.readableBytes() >= 1 && payload.getByte(payloadRi) == 4) {
                        payload.readerIndex(payloadRi + 1);
                    }
                    ByteBuf attachBuf = Base64.encode(payload);
                    binaryPacket.addAttachment(Unpooled.copiedBuffer(attachBuf));
                    attachBuf.release();
                } else {
                    throw new IOException("Malformed polling wrapper: missing or invalid 0xFF separator");
                }
            }  else if (frame.readableBytes() >= 1 && frame.getByte(ri) == 'b') {
                // 2. Polling Base64 text attachment: 'b4' (EIOv3) or 'b' (EIOv4)
                // In EIOv4 multi-packet polling, attachments in the POST body are separated by 0x1e.
                // Slice out the current attachment frame up to 0x1e so remaining attachments remain readable.
                int sepPos = frame.bytesBefore((byte) 0x1E);
                ByteBuf attachFrame;
                if (sepPos >= 0) {
                    attachFrame = frame.readSlice(sepPos);
                    frame.skipBytes(1); // skip 0x1e record separator
                    wrapperFound = true; // reader index already advanced to next packet
                } else {
                    attachFrame = frame;
                }

                int attachRi = attachFrame.readerIndex();
                if ((version == EngineIOVersion.V2 || version == EngineIOVersion.V3)
                        && attachFrame.readableBytes() >= 2
                        && attachFrame.getByte(attachRi) == 'b'
                        && attachFrame.getByte(attachRi + 1) == '4') {
                    attachFrame.readerIndex(attachRi + 2); // skip 'b4' (EIOv2/v3)
                } else if (attachFrame.readableBytes() >= 1 && attachFrame.getByte(attachRi) == 'b') {
                    attachFrame.readerIndex(attachRi + 1); // skip 'b' (EIOv4)
                }
                // Already base64-encoded text payload
                binaryPacket.addAttachment(Unpooled.copiedBuffer(attachFrame));
                if (!wrapperFound) {
                    attachFrame.skipBytes(attachFrame.readableBytes());
                }
            } else {
                // 3. Fallback polling binary payload
                ByteBuf attachBuf = Base64.encode(frame);
                binaryPacket.addAttachment(Unpooled.copiedBuffer(attachBuf));
                attachBuf.release();
                frame.skipBytes(frame.readableBytes());
            }

            if (!wrapperFound && frame.readableBytes() > 0) {
                frame.skipBytes(frame.readableBytes());
            }

        } else {
            // WebSocket transport
            boolean isV3orV2WebSocket = (version == EngineIOVersion.V3 || version == EngineIOVersion.V2);
            if (isV3orV2WebSocket
                    && frame.readableBytes() >= 1
                    && frame.getByte(ri) == 4) {
                frame.readerIndex(ri + 1); // skip 0x04 type prefix for V2/V3
            }
            ByteBuf attachBuf = Base64.encode(frame);
            binaryPacket.addAttachment(Unpooled.copiedBuffer(attachBuf));
            attachBuf.release();
            frame.skipBytes(frame.readableBytes());
        }

        return completeAttachment(head, binaryPacket);
    }

    private Packet addLegacyPollingBinaryAttachment(ClientHead head,
                                                    ByteBuf payload,
                                                    Packet binaryPacket) throws IOException {
        if (payload.isReadable() && payload.getByte(payload.readerIndex()) == 4) {
            payload.skipBytes(1);
        }
        ByteBuf attachment = Base64.encode(payload);
        try {
            binaryPacket.addAttachment(Unpooled.copiedBuffer(attachment));
        } finally {
            attachment.release();
        }
        return completeAttachment(head, binaryPacket);
    }

    private Packet completeAttachment(ClientHead head, Packet binaryPacket) throws IOException {
        if (!binaryPacket.isAttachmentsLoaded()) {
            return new Packet(PacketType.MESSAGE);
        }

        LinkedList<ByteBuf> slices = new LinkedList<>();
        ByteBuf source = head.getLastBinaryPacketSource();
        for (int i = 0; i < binaryPacket.getAttachments().size(); i++) {
            ByteBuf attachment = binaryPacket.getAttachments().get(i);
            ByteBuf scanValue = Unpooled.copiedBuffer("{\"_placeholder\":true,\"num\":" + i + "}", CharsetUtil.UTF_8);
            int pos = PacketEncoder.find(source, scanValue);
            if (pos == -1) {
                scanValue = Unpooled.copiedBuffer("{\"num\":" + i + ",\"_placeholder\":true}", CharsetUtil.UTF_8);
                pos = PacketEncoder.find(source, scanValue);
                if (pos == -1) {
                    throw new IllegalStateException("Can't find attachment by index: " + i + " in packet source");
                }
            }

            ByteBuf prefixBuf = source.slice(source.readerIndex(), pos - source.readerIndex());
            slices.add(prefixBuf);
            slices.add(quotes);
            slices.add(attachment);
            slices.add(quotes);

            source.readerIndex(pos + scanValue.readableBytes());
        }
        slices.add(source.slice());

        ByteBuf compositeBuf = Unpooled.wrappedBuffer(slices.toArray(new ByteBuf[0]));
        try {
            parseBody(head, compositeBuf, binaryPacket);
        } finally {
            head.clearPendingBinaryPacket();
        }
        return binaryPacket;
    }

    private void parseBody(ClientHead head, ByteBuf frame, Packet packet) throws IOException {
        // Early return for non-MESSAGE packets
        if (packet.getType() != PacketType.MESSAGE) {
            return;
        }

        if (packet.hasAttachments() && !packet.isAttachmentsLoaded()) {
            handleBinaryAttachments(head, frame, packet);
            return;
        }

        PacketType subType = packet.getSubType();
        
        // Handle different packet subtypes
        switch (subType) {
            case CONNECT:
            case DISCONNECT:
                parseConnectDisconnectBody(frame, packet);
                break;
                
            case ACK:
            case BINARY_ACK:
                parseAckBody(head, frame, packet);
                break;
                
            case EVENT:
            case BINARY_EVENT:
                parseEventBody(frame, packet);
                break;
                
            case ERROR:
                parseErrorBody(frame, packet);
                break;

            default:
                // Handle binary attachments for other packet types
                handleBinaryAttachments(head, frame, packet);
                break;
        }
    }

    /**
     * Parse ERROR packet bodies
     */
    private void parseErrorBody(ByteBuf frame, Packet packet) throws IOException {
        String nsp = readNamespace(frame, false);
        if (nsp != null && !nsp.isEmpty()) {
            packet.setNsp(nsp);
        }

        if (frame.readableBytes() > 0) {
            try {
                frame.markReaderIndex();
                try (ByteBufInputStream in = new ByteBufInputStream(frame)) {
                    Object errorData = jsonSupport.readValue(packet.getNsp(), in, Object.class);
                    packet.setData(errorData);
                }
            } catch (Exception e) {
                frame.resetReaderIndex();
                packet.setData(readString(frame));
            }
        }
    }
    /**
     * Parse CONNECT and DISCONNECT packet bodies
     */
    private void parseConnectDisconnectBody(ByteBuf frame, Packet packet) throws IOException {
        packet.setNsp(readNamespace(frame, false));
        
        // Only CONNECT packets can have auth data
        if (packet.getSubType() == PacketType.CONNECT && frame.readableBytes() > 0) {
            Object authArgs = jsonSupport.readValue(packet.getNsp(), new ByteBufInputStream(frame), Map.class);
            packet.setData(authArgs);
        }
    }

    /**
     * Parse ACK packet bodies
     */
    private void parseAckBody(ClientHead head, ByteBuf frame, Packet packet) throws IOException {
        AckCallback<?> callback = ackManager.getCallback(head.getSessionId(), packet.getAckId());
        
        if (callback != null) {
            ByteBufInputStream in = new ByteBufInputStream(frame);
            AckArgs args = jsonSupport.readAckArgs(in, callback);
            packet.setData(args.getArgs());
        } else {
            frame.clear();
        }
    }

    /**
     * Parse EVENT packet bodies
     */
    private void parseEventBody(ByteBuf frame, Packet packet) throws IOException {
        ByteBufInputStream in = new ByteBufInputStream(frame);
        Event event = jsonSupport.readValue(packet.getNsp(), in, Event.class);
        packet.setName(event.getName());
        packet.setData(event.getArgs());
    }

    /**
     * Handle binary attachments for packets that support them
     */
    private void handleBinaryAttachments(ClientHead head, ByteBuf frame, Packet packet) {
        if (packet.hasAttachments() && !packet.isAttachmentsLoaded()) {
            head.setPendingBinaryPacket(packet, Unpooled.copiedBuffer(frame));
            frame.skipBytes(frame.readableBytes());
        }
    }

    private String readNamespace(ByteBuf frame, final boolean defaultToAll) {

        /**
         * namespace post request with url queryString, like
         *  /message (v1)
         *  /message?a=1, (v2)
         *  /message, (v3,v4)
         */
        ByteBuf buffer = frame.slice();

        boolean withSpecialChar = false;

        int namespaceFieldEndIndex = buffer.bytesBefore((byte) ',');
        if (namespaceFieldEndIndex > 0) {
            withSpecialChar = true;
        } else {
            namespaceFieldEndIndex = buffer.readableBytes();
        }

        int namespaceEndIndex = buffer.bytesBefore((byte) '?');
        if (namespaceEndIndex > 0) {
            withSpecialChar = true;
        } else {
            namespaceEndIndex = namespaceFieldEndIndex;
        }

        String namespace = readString(buffer, namespaceEndIndex);
        if (namespace.startsWith("/")) {
            if (withSpecialChar) {
                frame.skipBytes(namespaceFieldEndIndex + 1);
            } else {
                frame.skipBytes(namespaceFieldEndIndex);
            }
            return namespace;
        }

        if (defaultToAll) {
            // skip this frame
            frame.skipBytes(frame.readableBytes());
            return readString(buffer);
        }
        return Namespace.DEFAULT_NAME;
    }

}
