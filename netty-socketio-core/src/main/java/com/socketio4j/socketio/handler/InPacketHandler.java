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
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.AuthTokenResult;
import com.socketio4j.socketio.listener.ExceptionListener;
import com.socketio4j.socketio.messages.PacketsMessage;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.namespace.NamespacesHub;
import com.socketio4j.socketio.protocol.ConnPacket;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketDecoder;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.transport.NamespaceClient;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.CharsetUtil;

@Sharable
public class InPacketHandler extends SimpleChannelInboundHandler<PacketsMessage> {

    private static final Logger log = LoggerFactory.getLogger(InPacketHandler.class);
    private static final int MAX_LOG_PREVIEW = 64;

    private final PacketListener packetListener;
    private final PacketDecoder decoder;
    private final NamespacesHub namespacesHub;
    private final ExceptionListener exceptionListener;

    public InPacketHandler(PacketListener packetListener, PacketDecoder decoder, NamespacesHub namespacesHub, ExceptionListener exceptionListener) {
        super();
        this.packetListener = packetListener;
        this.decoder = decoder;
        this.namespacesHub = namespacesHub;
        this.exceptionListener = exceptionListener;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, PacketsMessage message)
                throws Exception {
        ByteBuf content = message.getContent();
        ClientHead client = message.getClient();

        if (log.isTraceEnabled()) {
            log.trace("In message: {} sessionId: {}", content.toString(CharsetUtil.UTF_8), client.getSessionId());
        }
        
        int packetsProcessed = 0;
        while (content.isReadable()) {
            try {
                Packet packet = decoder.decodePackets(content, client, message.getTransport());
                if (packet == null) {
                    continue;
                }
                packetsProcessed++;

                if (log.isDebugEnabled()) {
                    log.debug("Decoded packet: type={}, subType={}, namespace={}, client={}, hasAttachments={}", 
                             packet.getType(), packet.getSubType(), packet.getNsp(), 
                             client.getSessionId(), packet.hasAttachments());
                }

                // Engine.IO control packets are connection-level packets: they are not
                // scoped to a Socket.IO namespace. In particular, an Engine.IO v4 client
                // is required to reply to the server PING before it sends its Socket.IO
                // CONNECT packet, so routing them through NamespaceClient would silently
                // drop a perfectly valid PONG from a newly opened connection.
                if (packet.getType() != PacketType.MESSAGE) {
                    packetListener.onTransportPacket(packet, client, message.getTransport());
                    continue;
                }

                Namespace ns = namespacesHub.get(packet.getNsp());
                if (ns == null) {
                    if (packet.getSubType() == PacketType.CONNECT) {
                        if (log.isDebugEnabled()) {
                            log.debug("Sending error response for invalid namespace: {} to client: {}", 
                                     packet.getNsp(), client.getSessionId());
                        }
                        Packet p = new Packet(PacketType.MESSAGE);
                        p.setSubType(PacketType.ERROR);
                        p.setNsp(packet.getNsp());
                        p.setData(toConnectErrorPayload(client, "Invalid namespace"));
                        client.send(p);
                        return;
                    }
                    log.debug("Can't find namespace for endpoint: {}, sessionId: {} probably it was removed.", packet.getNsp(), client.getSessionId());
                    return;
                }

                if (packet.getSubType() == PacketType.CONNECT) {
                    if (log.isDebugEnabled()) {
                        log.debug("Processing CONNECT packet for namespace: {} from client: {}, Engine.IO version: {}", 
                                 ns.getName(), client.getSessionId(), client.getEngineIOVersion());
                    }
                    NamespaceClient nClient = new NamespaceClient(client, ns);
                    if (EngineIOVersion.V4.equals(client.getEngineIOVersion())) {
                        if (!handleV4Connect(packet, client, ns, nClient)) {
                            return;
                        }
                    }
                    client.addNamespaceClient(nClient);
                }

                NamespaceClient nClient = client.getChildClient(ns);
                if (nClient == null) {
                    if (EngineIOVersion.V4.equals(client.getEngineIOVersion())) {
                        // The Socket.IO v3/v4 wire protocol (protocol v5) requires
                        // CONNECT before any other packet on a namespace. Do not let
                        // an unconnected client emit events or ACKs into application code.
                        client.disconnectWithProtocolClose();
                        ctx.close();
                    }
                    log.debug("Can't find namespace client in namespace: {}, sessionId: {} probably it was disconnected.", ns.getName(), client.getSessionId());
                    return;
                }
                if (packet.hasAttachments() && !packet.isAttachmentsLoaded()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Packet has unloaded attachments, deferring processing for client: {}, namespace: {}", 
                                 client.getSessionId(), ns.getName());
                        log.debug("Waiting for binary attachment...");
                    }
                    // Continue decoding remaining packets in the current POST body.
                    // A polling request may contain:
                    //   attachment(A), header(B), attachment(B)
                    // Returning here would abandon unread bytes and leave later
                    // binary attachments unprocessed.
                    continue;
                }
                packetListener.onPacket(packet, nClient, message.getTransport());
                if (log.isDebugEnabled()) {
                    log.debug("Successfully processed packet for client: {}, namespace: {}", 
                             client.getSessionId(), ns.getName());
                }
            } catch (Exception ex) {
                final int payloadSize;
                if (content.refCnt() > 0) payloadSize = content.readableBytes();
                else payloadSize = -1;
                log.error("Error during data processing. Client sessionId: {}, payloadSize={} bytes",
                        client.getSessionId(), payloadSize, ex);
                if (log.isTraceEnabled() && content.refCnt() > 0) {
                    int length = Math.min(payloadSize, MAX_LOG_PREVIEW);
                    log.trace("Error payload hex preview for sessionId {}: {}",
                            client.getSessionId(),
                            io.netty.buffer.ByteBufUtil.hexDump(content, content.readerIndex(), length));
                }
                throw ex;
            }
        }
        
        if (log.isDebugEnabled()) {
            log.debug("Completed processing {} packets for client: {}", packetsProcessed, client.getSessionId());
        }
    }
    private static Object toConnectErrorPayload(ClientHead client, Object errorData) {

        if (client.getEngineIOVersion() == EngineIOVersion.V4) {
            if (errorData instanceof Map) {
                return errorData;
            }

            if (errorData != null) {
                return Collections.singletonMap(
                        "message",
                        String.valueOf(errorData));
            }
            return Collections.singletonMap(
                    "message",
                    "Authentication failed");
        }

        if (errorData != null) {
            return String.valueOf(errorData);
        }
        return "Authentication failed";
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Exception caught in InPacketHandler for channel: {}, exception type: {}, message: {}", 
                     ctx.channel().id(), e.getClass().getSimpleName(), e.getMessage());
        }
        
        boolean handled = exceptionListener.exceptionCaught(ctx, e);
        
        if (log.isDebugEnabled()) {
            log.debug("Exception (handled: {}) by custom exception listener for channel: {}",
                    handled, ctx.channel().id());
        }
        
        if (!handled) {
            if (log.isDebugEnabled()) {
                log.debug("Delegating exception handling to parent handler for channel: {}", ctx.channel().id());
            }
            super.exceptionCaught(ctx, e);
        }
    }

    private boolean handleV4Connect(Packet packet, ClientHead client, Namespace ns, NamespaceClient nClient) {
        if (log.isDebugEnabled()) {
            log.debug("Starting Engine.IO v4 connect handling for client: {}, namespace: {}, hasAuthData: {}", 
                     client.getSessionId(), ns.getName(), packet.getData() != null);
        }
        
        // Check for an auth token
        if (packet.getData() != null) {
            final Object authData = packet.getData();
            
            if (log.isDebugEnabled()) {
                log.debug("Processing authentication data for client: {}, namespace: {}, authData type: {}", 
                         client.getSessionId(), ns.getName(), authData.getClass().getSimpleName());
            }
            
            client.getHandshakeData().setAuthToken(authData);
            
            // Call all authTokenListeners to see if one denies it
            final AuthTokenResult allowAuth = ns.onAuthData(nClient, authData);
            if (!allowAuth.isSuccess()) {
                if (log.isDebugEnabled()) {
                    log.debug("Authentication failed for client: {}, namespace: {}, sending error response", 
                             client.getSessionId(), ns.getName());
                }
                
                Packet p = new Packet(PacketType.MESSAGE);
                p.setSubType(PacketType.ERROR);
                p.setNsp(packet.getNsp());
                p.setData(toConnectErrorPayload(allowAuth.getErrorData()));
                client.send(p);
                return false;
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No authentication data provided for client: {}, namespace: {}, proceeding with connection", 
                         client.getSessionId(), ns.getName());
            }
        }
        Packet p = new Packet(PacketType.MESSAGE);
        p.setSubType(PacketType.CONNECT);
        p.setNsp(packet.getNsp());
        p.setData(new ConnPacket(client.getSessionId()));
        client.send(p);
        if (log.isDebugEnabled()) {
            log.debug("Completed Engine.IO v4 connect handling for client: {}, namespace: {}", 
                     client.getSessionId(), ns.getName());
        }
        return true;
    }

    /**
     * The Java socket.io-client parser accepts only JSON objects for CONNECT_ERROR payloads; bare strings fail
     * validation so connect_error is never emitted to application listeners.
     */
    private static Object toConnectErrorPayload(Object errorData) {
        if (errorData instanceof Map) {
            return errorData;
        }
        String message = "Authentication failed";
        if (errorData != null) {
            message = String.valueOf(errorData);
        }
        return Collections.singletonMap("message", message);
    }

}
