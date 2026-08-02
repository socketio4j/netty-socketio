package com.socketio4j.socketio.protocol;

import io.netty.buffer.ByteBuf;
import java.util.Collections;
import java.util.List;

/**
 * @author https://github.com/sanjomo
 * @date 02/08/26 2:36 am
 */

public final class EncodeResult {

    private final ByteBuf encodedPacket;
    private final List<ByteBuf> attachments;

    public EncodeResult(ByteBuf encodedPacket, List<ByteBuf> attachments) {
        this.encodedPacket = encodedPacket;
        this.attachments = attachments != null
                ? attachments
                : Collections.emptyList();
    }

    public ByteBuf getEncodedPacket() {
        return encodedPacket;
    }

    public List<ByteBuf> getAttachments() {
        return attachments;
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    public int getAttachmentsCount() {
        return attachments.size();
    }

    @Override
    public String toString() {
        return "EncodeResult{" +
                "encodedPacket=" + encodedPacket +
                ", attachments=" + attachments.size() +
                '}';
    }
}