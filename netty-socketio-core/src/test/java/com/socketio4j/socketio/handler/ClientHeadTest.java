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
package com.socketio4j.socketio.handler;

import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.DisconnectableHub;
import com.socketio4j.socketio.HandshakeData;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.scheduler.CancelableScheduler;
import com.socketio4j.socketio.store.StoreFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientHeadTest {

    private ClientHead clientHead;
    private AckManager ackManager;
    private DisconnectableHub disconnectableHub;
    private StoreFactory storeFactory;
    private HandshakeData handshakeData;
    private ClientsBox clientsBox;
    private CancelableScheduler scheduler;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        ackManager = mock(AckManager.class);
        disconnectableHub = mock(DisconnectableHub.class);
        storeFactory = mock(StoreFactory.class);
        handshakeData = mock(HandshakeData.class);
        clientsBox = mock(ClientsBox.class);
        scheduler = mock(CancelableScheduler.class);
        configuration = new Configuration();

        when(handshakeData.getHttpHeaders()).thenReturn(new io.netty.handler.codec.http.DefaultHttpHeaders());

        clientHead = new ClientHead(
                UUID.randomUUID(),
                ackManager,
                disconnectableHub,
                storeFactory,
                handshakeData,
                clientsBox,
                Transport.WEBSOCKET,
                scheduler,
                configuration,
                new HashMap<>()
        );
    }

    @Test
    void testPendingBinaryPacketReleasedOnClear() {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.BINARY_EVENT);
        ByteBuf buf = Unpooled.copiedBuffer("451-[\"upload\",{\"_placeholder\":true,\"num\":0}]", CharsetUtil.UTF_8);
        assertEquals(1, buf.refCnt());

        clientHead.setPendingBinaryPacket(packet, buf);
        assertEquals(packet, clientHead.getLastBinaryPacket());
        assertEquals(buf, clientHead.getLastBinaryPacketSource());

        clientHead.clearPendingBinaryPacket();

        assertNull(clientHead.getLastBinaryPacket());
        assertNull(clientHead.getLastBinaryPacketSource());
        assertEquals(0, buf.refCnt());
    }

    @Test
    void testPendingBinaryPacketReleasedOnChannelDisconnect() {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.BINARY_EVENT);
        ByteBuf buf = Unpooled.copiedBuffer("451-[\"upload\",{\"_placeholder\":true,\"num\":0}]", CharsetUtil.UTF_8);
        assertEquals(1, buf.refCnt());

        clientHead.setPendingBinaryPacket(packet, buf);
        assertEquals(packet, clientHead.getLastBinaryPacket());

        clientHead.onChannelDisconnect();

        assertNull(clientHead.getLastBinaryPacket());
        assertNull(clientHead.getLastBinaryPacketSource());
        assertEquals(0, buf.refCnt());
    }

    @Test
    void testSetPendingBinaryPacketReplacesAndReleasesPreviousSource() {
        Packet packet1 = new Packet(PacketType.MESSAGE);
        packet1.setSubType(PacketType.BINARY_EVENT);
        ByteBuf buf1 = Unpooled.copiedBuffer("packet1_source", CharsetUtil.UTF_8);

        Packet packet2 = new Packet(PacketType.MESSAGE);
        packet2.setSubType(PacketType.BINARY_EVENT);
        ByteBuf buf2 = Unpooled.copiedBuffer("packet2_source", CharsetUtil.UTF_8);

        clientHead.setPendingBinaryPacket(packet1, buf1);
        assertEquals(1, buf1.refCnt());

        // Setting packet2 should release buf1
        clientHead.setPendingBinaryPacket(packet2, buf2);

        assertEquals(0, buf1.refCnt());
        assertEquals(1, buf2.refCnt());
        assertEquals(packet2, clientHead.getLastBinaryPacket());
        assertEquals(buf2, clientHead.getLastBinaryPacketSource());

        clientHead.clearPendingBinaryPacket();
        assertEquals(0, buf2.refCnt());
    }
}
