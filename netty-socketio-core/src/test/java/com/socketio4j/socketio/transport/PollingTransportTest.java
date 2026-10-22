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
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.handler.ClientsBox;
import com.socketio4j.socketio.messages.HttpErrorMessage;
import com.socketio4j.socketio.messages.PacketsMessage;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.PacketDecoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PollingTransportTest {

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
