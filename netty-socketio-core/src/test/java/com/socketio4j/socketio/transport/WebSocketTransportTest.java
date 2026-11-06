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
/*
 * @(#)WebSocketTransportTest.java 2018. 5. 23.
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

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.handler.ClientsBox;
import com.socketio4j.socketio.protocol.EngineIOVersion;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.Collections;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;

/**
 * @author hangsu.cho@navercorp.com
 *
 */

public class WebSocketTransportTest {

  /**
   * Test method for {@link com.socketio4j.socketio.transport.WebSocketTransport#channelRead()}.
   */
  @Test
  public void testCloseFrame() {
    EmbeddedChannel channel = createChannel();

    channel.writeInbound(new CloseWebSocketFrame());
    Object msg = channel.readOutbound();

    // https://tools.ietf.org/html/rfc6455#section-5.5.1
    // If an endpoint receives a Close frame and did not previously send a Close frame, the endpoint
    // MUST send a Close frame in response.
    assertTrue(msg instanceof CloseWebSocketFrame);
  }

  @Test
  public void testBinaryWebSocketFrameHandling() {
    EmbeddedChannel channel = createChannel();
    byte[] largePayload = new byte[65536]; // 64KB binary attachment
    largePayload[0] = 4; // MESSAGE
    largePayload[1] = 5; // BINARY_EVENT

    io.netty.buffer.ByteBuf buf = Unpooled.copiedBuffer(largePayload);
    BinaryWebSocketFrame frame = new BinaryWebSocketFrame(buf);

    channel.writeInbound(frame);
    assertTrue(channel.isOpen(), "Channel should stay open after receiving binary WebSocket frame");
    frame.release();
    assertEquals(0, buf.refCnt(), "ByteBuf reference count should be 0 after releasing frame");
  }

  @Test
  public void shouldCloseOnlyTheSecondWebSocketForASession() {
    UUID sessionId = UUID.randomUUID();
    ClientsBox clientsBox = mock(ClientsBox.class);
    ClientHead clientHead = mock(ClientHead.class);
    EmbeddedChannel secondChannel = new EmbeddedChannel();

    when(clientsBox.get(sessionId)).thenReturn(clientHead);
    when(clientHead.tryBindWebSocketChannel(secondChannel)).thenReturn(false);

    WebSocketTransport transport = new WebSocketTransport(false, null, null, null, clientsBox);

    transport.connectClient(secondChannel, sessionId);

    assertTrue(!secondChannel.isOpen(), "The newly opened duplicate WebSocket must be closed");
    verify(clientHead, never()).disconnect();
    verify(clientsBox, never()).removeClient(eq(sessionId), any(ClientHead.class));
  }

  @Test
  public void shouldPreserveReplacementWhenClosingOldClient() throws Exception {
    UUID sessionId = UUID.randomUUID();
    ClientsBox clientsBox = new ClientsBox();
    ClientHead oldClient = mock(ClientHead.class);
    ClientHead replacement = mock(ClientHead.class);
    when(oldClient.getSessionId()).thenReturn(sessionId);
    when(replacement.getSessionId()).thenReturn(sessionId);
    clientsBox.addClient(oldClient);
    doAnswer(invocation -> {
      clientsBox.removeClient(sessionId);
      clientsBox.addClient(replacement);
      return Collections.emptySet();
    }).when(oldClient).getNamespaces();
    EmbeddedChannel channel = new EmbeddedChannel();
    WebSocketTransport transport = new WebSocketTransport(false, null, null, null, clientsBox);

    java.lang.reflect.Method close = WebSocketTransport.class.getDeclaredMethod("closeClient", UUID.class, io.netty.channel.Channel.class);
    close.setAccessible(true);
    close.invoke(transport, sessionId, channel);

    assertTrue(!channel.isOpen());
    verify(oldClient).disconnect();
    assertSame(replacement, clientsBox.get(sessionId));
    assertEquals(1, clientsBox.getClientsCount());
  }

  @Test
  public void shouldKeepClientWithNamespacesWhenClosingChannel() throws Exception {
    UUID sessionId = UUID.randomUUID();
    ClientsBox clientsBox = new ClientsBox();
    ClientHead client = mock(ClientHead.class);
    when(client.getSessionId()).thenReturn(sessionId);
    when(client.getNamespaces()).thenReturn(Collections.singleton(mock(com.socketio4j.socketio.namespace.Namespace.class)));
    clientsBox.addClient(client);
    EmbeddedChannel channel = new EmbeddedChannel();
    WebSocketTransport transport = new WebSocketTransport(false, null, null, null, clientsBox);

    java.lang.reflect.Method close = WebSocketTransport.class.getDeclaredMethod("closeClient", UUID.class, io.netty.channel.Channel.class);
    close.setAccessible(true);
    close.invoke(transport, sessionId, channel);

    assertTrue(!channel.isOpen());
    verify(client, never()).disconnect();
    assertSame(client, clientsBox.get(sessionId));
    assertEquals(1, clientsBox.getClientsCount());
  }

  @Test
  public void testChannelWritabilityChangedFlushesClient() throws Exception {
    ClientsBox clientsBox = mock(ClientsBox.class);
    ClientHead clientHead = mock(ClientHead.class);
    WebSocketTransport transport = new WebSocketTransport(false, null, null, null, clientsBox);

    EmbeddedChannel channel = new EmbeddedChannel(transport);
    when(clientsBox.get(channel)).thenReturn(clientHead);
    when(clientHead.isTransportChannel(channel, Transport.WEBSOCKET)).thenReturn(true);

    transport.channelWritabilityChanged(channel.pipeline().firstContext());

    verify(clientHead).flush(Transport.WEBSOCKET);
  }

  @Test
  public void testChannelInactiveDoesNotQueueDisconnectPacketOnDeadSession() throws Exception {
    ClientsBox clientsBox = mock(ClientsBox.class);
    ClientHead clientHead = mock(ClientHead.class);
    WebSocketTransport transport = new WebSocketTransport(false, null, null, null, clientsBox);

    EmbeddedChannel channel = new EmbeddedChannel(transport);
    when(clientsBox.get(channel)).thenReturn(clientHead);
    when(clientHead.isTransportChannel(channel, Transport.WEBSOCKET)).thenReturn(true);

    channel.close();

    verify(clientHead).onChannelDisconnect();
    verify(clientHead, never()).send(any());
  }

  private EmbeddedChannel createChannel() {
    ClientsBox clientsBox = mock(ClientsBox.class);
    ClientHead clientHead = mock(ClientHead.class);
    when(clientsBox.get(any(io.netty.channel.Channel.class))).thenReturn(clientHead);
    when(clientHead.getEngineIOVersion()).thenReturn(EngineIOVersion.V4);

    return new EmbeddedChannel(new WebSocketTransport(false, null, null, null, clientsBox) {
      @Override
      public void channelInactive(ChannelHandlerContext ctx) throws Exception {}
    });
  }

}
