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
package com.socketio4j.socketio.transport;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.DisconnectableHub;
import com.socketio4j.socketio.HandshakeData;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.handler.AuthorizeHandler;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.handler.ClientsBox;
import com.socketio4j.socketio.handler.EncoderHandler;
import com.socketio4j.socketio.messages.HttpErrorMessage;
import com.socketio4j.socketio.messages.PacketsMessage;
import com.socketio4j.socketio.messages.XHRPostMessage;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.JacksonJsonSupport;
import com.socketio4j.socketio.protocol.PacketDecoder;
import com.socketio4j.socketio.protocol.PacketEncoder;
import com.socketio4j.socketio.scheduler.CancelableScheduler;
import com.socketio4j.socketio.store.StoreFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PollingTransportTest {

    @ParameterizedTest
    @EnumSource(EngineIOVersion.class)
    void shouldRejectOverlappingPostForEveryVersion(EngineIOVersion version) {
        UUID sessionId = UUID.randomUUID();
        ClientsBox clientsBox = mock(ClientsBox.class);
        ClientHead client = mock(ClientHead.class);
        when(clientsBox.get(sessionId)).thenReturn(client);
        when(client.getEngineIOVersion()).thenReturn(version);
        when(client.getCurrentTransport()).thenReturn(Transport.POLLING);
        when(client.tryAcquirePollingPost()).thenReturn(false);
        EmbeddedChannel channel = new EmbeddedChannel(new PollingTransport(null, null, clientsBox));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                    "/socket.io/?transport=polling&sid=" + sessionId,
                    Unpooled.copiedBuffer(version == EngineIOVersion.V4 ? "2" : "1:2", StandardCharsets.UTF_8)));

            assertThat((Object) channel.readOutbound()).isInstanceOf(HttpErrorMessage.class);
            assertThat((Object) channel.readInbound()).isNull();
            verify(client).tryAcquirePollingPost();
            verify(client, never()).releasePollingPost();
            if (version == EngineIOVersion.V4) {
                verify(client).onChannelDisconnect();
            } else {
                verify(client, never()).onChannelDisconnect();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @EnumSource(EngineIOVersion.class)
    void shouldReleasePostLockOnlyWhenWriteCompletes(EngineIOVersion version) {
        for (boolean success : new boolean[] { true, false }) {
            UUID sessionId = UUID.randomUUID();
            ClientsBox clientsBox = mock(ClientsBox.class);
            ClientHead client = mock(ClientHead.class);
            when(clientsBox.get(sessionId)).thenReturn(client);
            when(client.getEngineIOVersion()).thenReturn(version);
            when(client.getCurrentTransport()).thenReturn(Transport.POLLING);
            when(client.tryAcquirePollingPost()).thenReturn(true);
            AtomicReference<ChannelPromise> response = new AtomicReference<>();
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    assertThat(msg).isInstanceOf(XHRPostMessage.class);
                    response.set(promise);
                }
            }, new PollingTransport(null, null, clientsBox));
            try {
                channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                        "/socket.io/?transport=polling&sid=" + sessionId,
                        Unpooled.copiedBuffer(version == EngineIOVersion.V4 ? "2" : "1:2", StandardCharsets.UTF_8)));

                PacketsMessage packets = channel.readInbound();
                assertThat(packets.getTransport()).isEqualTo(Transport.POLLING);
                packets.getContent().release();
                verify(client).tryAcquirePollingPost();
                verify(client, never()).releasePollingPost();

                if (success) {
                    response.get().setSuccess();
                } else {
                    response.get().setFailure(new IllegalStateException("write failed"));
                }
                channel.runPendingTasks();
                verify(client).releasePollingPost();
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void shouldCloseOutstandingV4PollWhenAnotherGetIsRejected() throws Exception {
        UUID sessionId = UUID.randomUUID();
        ClientsBox clientsBox = new ClientsBox();
        DisconnectableHub hub = mock(DisconnectableHub.class);
        Configuration configuration = new Configuration();
        HandshakeData handshake = mock(HandshakeData.class);
        when(handshake.getHttpHeaders()).thenReturn(new DefaultHttpHeaders());
        ClientHead client = new ClientHead(sessionId, mock(AckManager.class), hub, mock(StoreFactory.class),
                handshake, clientsBox, Transport.POLLING, mock(CancelableScheduler.class),
                configuration, new HashMap<>());
        clientsBox.addClient(client);
        EncoderHandler encoder = new EncoderHandler(configuration,
                new PacketEncoder(configuration, new JacksonJsonSupport()));
        PollingTransport transport = new PollingTransport(null, mock(AuthorizeHandler.class), clientsBox);
        EmbeddedChannel outstanding = new EmbeddedChannel(encoder, transport);
        EmbeddedChannel rejected = new EmbeddedChannel(encoder, transport);
        try {
            String uri = "/socket.io/?EIO=4&transport=polling&sid=" + sessionId;
            outstanding.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));
            assertThat((Object) outstanding.readOutbound()).isNull();

            rejected.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));

            HttpResponse error = rejected.readOutbound();
            assertThat(error.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
            HttpResponse close = outstanding.readOutbound();
            assertThat(close.status()).isEqualTo(HttpResponseStatus.OK);
            HttpContent content = outstanding.readOutbound();
            assertThat(content.content().toString(StandardCharsets.UTF_8)).isEqualTo("1");
            content.release();
            assertThat(client.isConnected()).isFalse();
            assertThat(outstanding.isActive()).isFalse();
            verify(hub).onDisconnect(client);
        } finally {
            outstanding.finishAndReleaseAll();
            rejected.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @EnumSource(EngineIOVersion.class)
    void shouldPreserveOtherGetRejections(EngineIOVersion version) {
        UUID sessionId = UUID.randomUUID();
        ClientsBox clientsBox = mock(ClientsBox.class);
        ClientHead client = mock(ClientHead.class);
        when(clientsBox.get(sessionId)).thenReturn(client);
        when(client.getEngineIOVersion()).thenReturn(version);
        when(client.getCurrentTransport()).thenReturn(Transport.POLLING);
        // Legacy sessions remain live; v4 binding fails for an already closed session.
        when(client.isConnected()).thenReturn(version != EngineIOVersion.V4);
        when(client.tryBindPollingChannel(any())).thenReturn(false);
        EmbeddedChannel channel = new EmbeddedChannel(new PollingTransport(null, null, clientsBox));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/socket.io/?transport=polling&sid=" + sessionId));

            assertThat((Object) channel.readOutbound()).isInstanceOf(HttpErrorMessage.class);
            verify(client, never()).disconnectWithProtocolClose();
            verify(client, never()).onChannelDisconnect();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldDecodeEngineIOV3JsonpPostWithoutB64() throws Exception {
        UUID sessionId = UUID.randomUUID();
        PacketDecoder decoder = mock(PacketDecoder.class);
        ClientsBox clientsBox = mock(ClientsBox.class);
        ClientHead clientHead = mock(ClientHead.class);
        when(clientsBox.get(sessionId)).thenReturn(clientHead);
        when(clientHead.getEngineIOVersion()).thenReturn(EngineIOVersion.V3);
        when(clientHead.getCurrentTransport()).thenReturn(com.socketio4j.socketio.Transport.POLLING);
        when(clientHead.tryAcquirePollingPost()).thenReturn(true);
        when(decoder.preprocessJson(eq(1), any(ByteBuf.class)))
                .thenAnswer(invocation -> {
                    ByteBuf content = invocation.getArgument(1);
                    content.skipBytes(2); // the JSONP d= form is unwrapped in-place
                    return content;
                });

        EmbeddedChannel channel = new EmbeddedChannel(new PollingTransport(decoder, null, clientsBox));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/socket.io/?EIO=3&transport=polling&sid=" + sessionId + "&j=1",
                Unpooled.copiedBuffer("d=2:40", StandardCharsets.UTF_8));

        channel.writeInbound(request);

        verify(decoder).preprocessJson(eq(1), any(ByteBuf.class));
        PacketsMessage packets = channel.readInbound();
        assertThat(packets.getTransport()).isEqualTo(com.socketio4j.socketio.Transport.POLLING);
        assertThat(packets.getContent().toString(StandardCharsets.UTF_8)).isEqualTo("2:40");
        packets.getContent().release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldPreserveBase64PlusInEngineIOV3B64Post() throws Exception {
        UUID sessionId = UUID.randomUUID();
        PacketDecoder decoder = mock(PacketDecoder.class);
        ClientsBox clientsBox = mock(ClientsBox.class);
        ClientHead clientHead = mock(ClientHead.class);
        when(clientsBox.get(sessionId)).thenReturn(clientHead);
        when(clientHead.getEngineIOVersion()).thenReturn(EngineIOVersion.V3);
        when(clientHead.getCurrentTransport()).thenReturn(com.socketio4j.socketio.Transport.POLLING);
        when(clientHead.tryAcquirePollingPost()).thenReturn(true);

        EmbeddedChannel channel = new EmbeddedChannel(new PollingTransport(decoder, null, clientsBox));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/socket.io/?EIO=3&transport=polling&sid=" + sessionId + "&b64=1",
                Unpooled.copiedBuffer("9:b4AQ+ID==", StandardCharsets.UTF_8));

        channel.writeInbound(request);

        verify(decoder, never()).preprocessJson(any(), any(ByteBuf.class));
        PacketsMessage packets = channel.readInbound();
        assertThat(packets.getContent().toString(StandardCharsets.UTF_8)).isEqualTo("9:b4AQ+ID==");
        packets.getContent().release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectPollingRequestAfterWebSocketUpgrade() {
        UUID sessionId = UUID.randomUUID();
        PacketDecoder decoder = mock(PacketDecoder.class);
        ClientsBox clientsBox = mock(ClientsBox.class);
        ClientHead clientHead = mock(ClientHead.class);
        when(clientsBox.get(sessionId)).thenReturn(clientHead);
        when(clientHead.getCurrentTransport()).thenReturn(com.socketio4j.socketio.Transport.WEBSOCKET);

        EmbeddedChannel channel = new EmbeddedChannel(new PollingTransport(decoder, null, clientsBox));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/socket.io/?EIO=4&transport=polling&sid=" + sessionId);

        channel.writeInbound(request);

        Object response = channel.readOutbound();
        assertThat(response).isInstanceOf(HttpErrorMessage.class);
        verify(clientHead, never()).tryBindPollingChannel(any());
        channel.finishAndReleaseAll();
    }
}
