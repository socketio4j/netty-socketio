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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.handler.AuthorizeHandler;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.handler.ClientsBox;
import com.socketio4j.socketio.handler.EncoderHandler;
import com.socketio4j.socketio.messages.HttpErrorMessage;
import com.socketio4j.socketio.messages.PacketsMessage;
import com.socketio4j.socketio.messages.XHROptionsMessage;
import com.socketio4j.socketio.messages.XHRPostMessage;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketDecoder;
import com.socketio4j.socketio.protocol.PacketType;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;

@Sharable
public class PollingTransport extends ChannelInboundHandlerAdapter {

    public static final String NAME = "polling";

    private static final Logger log = LoggerFactory.getLogger(PollingTransport.class);

    private final PacketDecoder decoder;
    private final ClientsBox clientsBox;
    private final AuthorizeHandler authorizeHandler;

    public PollingTransport(PacketDecoder decoder, AuthorizeHandler authorizeHandler, ClientsBox clientsBox) {
        this.decoder = decoder;
        this.authorizeHandler = authorizeHandler;
        this.clientsBox = clientsBox;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest req = (FullHttpRequest) msg;
            QueryStringDecoder queryDecoder = new QueryStringDecoder(req.uri());

            List<String> transport = queryDecoder.parameters().get("transport");

            if (transport != null && transport.size() == 1 && NAME.equals(transport.get(0))) {
                List<String> sid = queryDecoder.parameters().get("sid");
                List<String> j = queryDecoder.parameters().get("j");
                List<String> b64 = queryDecoder.parameters().get("b64");

                String origin = req.headers().get(HttpHeaderNames.ORIGIN);
                ctx.channel().attr(EncoderHandler.ORIGIN).set(origin);

                String userAgent = req.headers().get(HttpHeaderNames.USER_AGENT);
                ctx.channel().attr(EncoderHandler.USER_AGENT).set(userAgent);

                // Query parameters apply to a single polling request. Reset
                // them on keep-alive channels before reading the current URI.
                ctx.channel().attr(EncoderHandler.JSONP_INDEX).set(null);
                ctx.channel().attr(EncoderHandler.B64).set(false);

                try {
                    if (j != null && j.size() == 1 && j.get(0) != null) {
                        Integer index = Integer.valueOf(j.get(0));
                        ctx.channel().attr(EncoderHandler.JSONP_INDEX).set(index);
                    }
                    if (b64 != null && b64.size() == 1 && b64.get(0) != null) {
                        String flag = b64.get(0);
                        if ("true".equals(flag)) {
                            flag = "1";
                        } else if ("false".equals(flag)) {
                            flag = "0";
                        }
                        Integer enable = Integer.valueOf(flag);
                        ctx.channel().attr(EncoderHandler.B64).set(enable == 1);
                    }

                    if (HttpMethod.OPTIONS.equals(req.method())) {
                        onOptions(ctx, origin);
                    } else if (sid != null && sid.size() == 1 && sid.get(0) != null) {
                        final UUID sessionId = UUID.fromString(sid.get(0));
                        handleMessage(req, sessionId, queryDecoder, ctx);
                    } else {
                        // first connection
                        ClientHead client = ctx.channel().attr(ClientHead.CLIENT).get();
                        if (client != null) {
                            handleMessage(req, client.getSessionId(), queryDecoder, ctx);
                        } else {
                            sendError(ctx);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    log.debug("Malformed polling query for {}", req.uri(), e);
                    sendError(ctx);
                } finally {
                    req.release();
                }
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    private void handleMessage(FullHttpRequest req, UUID sessionId, QueryStringDecoder queryDecoder, ChannelHandlerContext ctx)
                                                                                throws IOException {
            String origin = req.headers().get(HttpHeaderNames.ORIGIN);
            ClientHead client = clientsBox.get(sessionId);
            if (client == null) {
                sendUnknownSessionError(ctx);
                return;
            }
            // A request with a sid must use the session's current transport.
            // In particular, polling must not resume after a WebSocket upgrade.
            if (client.getCurrentTransport() != Transport.POLLING) {
                log.debug("Rejecting polling request for session {} on {} transport", sessionId, client.getCurrentTransport());
                sendError(ctx);
                return;
            }
            if (queryDecoder.parameters().containsKey("disconnect")) {
                client.onChannelDisconnect();
                ctx.channel().writeAndFlush(new XHRPostMessage(origin, sessionId));
            } else if (HttpMethod.POST.equals(req.method())) {
                onPost(sessionId, ctx, origin, req);
            } else if (HttpMethod.GET.equals(req.method())) {
                onGet(sessionId, ctx, origin);
            } else {
                log.error("Wrong {} method invocation for {}", req.method(), sessionId);
                sendError(ctx);
            }
    }

    private void onOptions(ChannelHandlerContext ctx, String origin) {
        ctx.channel().writeAndFlush(new XHROptionsMessage(origin, null));
    }

    private void onPost(UUID sessionId, ChannelHandlerContext ctx, String origin, FullHttpRequest req)
                                                                                throws IOException {
        ClientHead client = clientsBox.get(sessionId);
        if (client == null) {
            log.error("{} is not registered. Closing connection", sessionId);
            sendUnknownSessionError(ctx);
            return;
        }

        String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (client.getEngineIOVersion().getValue().equals("4")
                && contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/octet-stream")) {
            log.debug("Rejecting raw binary Engine.IO v4 polling POST for session {}", sessionId);
            client.onChannelDisconnect();
            sendError(ctx);
            return;
        }

        // Engine.IO v4 polling is a record-separated text payload. Reject an
        // invalid Engine.IO frame before acknowledging the POST so the client
        // receives the protocol-mandated 400 and the session cannot be reused.
        if (client.getEngineIOVersion().getValue().equals("4")
                && !isValidV4PollingPayload(req.content())) {
            log.debug("Rejecting malformed Engine.IO v4 polling payload for session {}", sessionId);
            client.onChannelDisconnect();
            sendError(ctx);
            return;
        }

        if (!client.tryAcquirePollingPost()) {
            log.debug("Rejecting overlapping polling POST for session {}", sessionId);
            client.onChannelDisconnect();
            sendError(ctx);
            return;
        }

        // FullHttpRequest is reference-counted and can be released by upstream.
        // Retain the content since we pass it further down the pipeline.
        ByteBuf content = req.content().retain();

        // release POST response before message processing
        ctx.channel().writeAndFlush(new XHRPostMessage(origin, sessionId))
                .addListener(future -> client.releasePollingPost());

        Integer jsonIndex = ctx.channel().attr(EncoderHandler.JSONP_INDEX).get();
        // JSONP POSTs use the d=<escaped payload> form and must be unwrapped.
        // Do not URL-decode a b64=1 payload: '+' is valid Base64 and must stay
        // intact. JSONP is a legacy (EIO v2/v3) transport only.
        if (!EngineIOVersion.V4.equals(client.getEngineIOVersion()) && jsonIndex != null) {
            content = decoder.preprocessJson(jsonIndex, content);
        }

        ChannelHandlerContext codecCtx = ctx.pipeline().context(HttpRequestDecoder.class);
        if (codecCtx == null) {
            codecCtx = ctx.pipeline().context(WebSocket13FrameDecoder.class);
        }
        if (codecCtx == null) {
            codecCtx = ctx.pipeline().context(HttpServerCodec.class);
        }
        PacketsMessage packetsMessage = new PacketsMessage(client, content, Transport.POLLING);
        if (codecCtx != null) {
            codecCtx.fireChannelRead(packetsMessage);
        } else {
            ctx.pipeline().fireChannelRead(packetsMessage);
        }
    }

    private boolean isValidV4PollingPayload(ByteBuf content) {
        if (!content.isReadable()) {
            return false;
        }
        int frameStart = content.readerIndex();
        int end = content.writerIndex();
        for (int i = frameStart; i <= end; i++) {
            if (i == end || content.getByte(i) == 0x1E) {
                if (i == frameStart) {
                    return false;
                }
                byte type = content.getByte(frameStart);
                // "b" is the v4 polling binary frame marker. Other frames
                // begin with the ASCII Engine.IO packet type (0 through 6).
                if (type != 'b' && (type < '0' || type > '6')) {
                    return false;
                }
                frameStart = i + 1;
            }
        }
        return true;
    }

    protected void onGet(UUID sessionId, ChannelHandlerContext ctx, String origin) {
        ClientHead client = clientsBox.get(sessionId);
        if (client == null) {
            log.error("{} is not registered. Closing connection", sessionId);
            sendUnknownSessionError(ctx);
            return;
        }

        if (!client.tryBindPollingChannel(ctx.channel())) {
            log.debug("Rejecting overlapping polling GET for session {}", sessionId);
            client.onChannelDisconnect();
            sendError(ctx);
            return;
        }

        // A legacy Engine.IO client pauses polling only after it receives the
        // WebSocket probe PONG. Send NOOP on whichever polling GET is current
        // while that pause is in progress, so a rebinding race cannot strand it.
        if (client.isUpgradeInProgress()) {
            client.send(new Packet(PacketType.NOOP), Transport.POLLING);
        }

        authorizeHandler.connect(client);
    }

    private void sendError(ChannelHandlerContext ctx) {
        sendError(ctx, 3, "Bad request");
    }

    private void sendUnknownSessionError(ChannelHandlerContext ctx) {
        sendError(ctx, 1, "Session ID unknown");
    }

    private void sendError(ChannelHandlerContext ctx, int code, String message) {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("code", code);
        errorData.put("message", message);

        // Route polling failures through EncoderHandler so configured CORS
        // headers are present on every cross-origin Engine.IO response,
        // including a trailing request after a client disconnects.
        ctx.channel().writeAndFlush(new HttpErrorMessage(errorData));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        final Channel channel = ctx.channel();
        ClientHead client = clientsBox.get(channel);
        if (client != null && client.isTransportChannel(ctx.channel(), Transport.POLLING)) {
            log.debug("channel inactive {}", client.getSessionId());
            client.releasePollingChannel(channel);
        }
        super.channelInactive(ctx);
    }

}
