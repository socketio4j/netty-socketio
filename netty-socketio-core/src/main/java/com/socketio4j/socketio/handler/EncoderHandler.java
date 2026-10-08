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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Queue;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.messages.HttpErrorMessage;
import com.socketio4j.socketio.messages.HttpMessage;
import com.socketio4j.socketio.messages.OutPacketMessage;
import com.socketio4j.socketio.messages.XHROptionsMessage;
import com.socketio4j.socketio.messages.XHRPostMessage;
import com.socketio4j.socketio.protocol.EncodePacketsResult;
import com.socketio4j.socketio.protocol.EncodeResult;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketEncoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Sharable
public class EncoderHandler extends ChannelOutboundHandlerAdapter {

    private static final byte[] OK = "ok".getBytes(CharsetUtil.UTF_8);

    public static final AttributeKey<String> ORIGIN = AttributeKey.valueOf("origin");
    public static final AttributeKey<String> USER_AGENT = AttributeKey.valueOf("userAgent");
    public static final AttributeKey<Boolean> B64 = AttributeKey.valueOf("b64");
    public static final AttributeKey<Integer> JSONP_INDEX = AttributeKey.valueOf("jsonpIndex");
    public static final AttributeKey<Boolean> WRITE_ONCE = AttributeKey.valueOf("writeOnce");

    private static final Logger log = LoggerFactory.getLogger(EncoderHandler.class);

    private final PacketEncoder encoder;

    private String version;
    private Configuration configuration;

    public EncoderHandler(Configuration configuration, PacketEncoder encoder) throws IOException {
        this.encoder = encoder;
        this.configuration = configuration;

        if (configuration.isAddVersionHeader()) {
            readVersion();
        }
    }

    private void readVersion() throws IOException {
        Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
        while (resources.hasMoreElements()) {
            try (InputStream inputStream = resources.nextElement().openStream()){
                Manifest manifest = new Manifest(inputStream);
                Attributes attrs = manifest.getMainAttributes();
                if (attrs == null) {
                    continue;
                }
                String name = attrs.getValue("Bundle-Name");
                if (name != null && "netty-socketio".equals(name)) {
                    version = name + "/" + attrs.getValue("Bundle-Version");
                    break;
                }
            } catch (IOException E) {
                // skip it
            }
        }
    }

    private void write(XHROptionsMessage msg, ChannelHandlerContext ctx, ChannelPromise promise) {
        HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);

        if (msg.getSessionId() != null) {
            res.headers().add(HttpHeaderNames.SET_COOKIE, "io=" + msg.getSessionId());
        }
        res.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                .add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, HttpHeaderNames.CONTENT_TYPE);

        String origin = ctx.channel().attr(ORIGIN).get();
        addOriginHeaders(origin, res);

        ByteBuf out = encoder.allocateBuffer(ctx.alloc());
        sendMessage(msg, ctx.channel(), out, res, promise);
    }

    private void write(XHRPostMessage msg, ChannelHandlerContext ctx, ChannelPromise promise) {
        ByteBuf out = encoder.allocateBuffer(ctx.alloc());
        out.writeBytes(OK);
        sendMessage(msg, ctx.channel(), out, "text/html", promise, HttpResponseStatus.OK);
    }

    private void sendMessage(HttpMessage msg, Channel channel, ByteBuf out, String type, ChannelPromise promise, HttpResponseStatus status) {
        HttpResponse res = new DefaultHttpResponse(HTTP_1_1, status);

        res.headers().add(HttpHeaderNames.CONTENT_TYPE, type)
                .add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        if (msg.getSessionId() != null) {
            res.headers().add(HttpHeaderNames.SET_COOKIE, "io=" + msg.getSessionId());
        }

        String origin = channel.attr(ORIGIN).get();
        addOriginHeaders(origin, res);

        HttpUtil.setContentLength(res, out.readableBytes());

        // prevent XSS warnings on IE
        // https://github.com/LearnBoost/socket.io/pull/1333
        String userAgent = channel.attr(EncoderHandler.USER_AGENT).get();
        if (userAgent != null && (userAgent.contains(";MSIE") || userAgent.contains("Trident/"))) {
            res.headers().add("X-XSS-Protection", "0");
        }

        sendMessage(msg, channel, out, res, promise);
    }

    private void sendMessage(HttpMessage msg, Channel channel, ByteBuf out, HttpResponse res, ChannelPromise promise) {
        channel.write(res);

        if (log.isTraceEnabled()) {
            if (msg.getSessionId() != null) {
                log.trace("Out message: {} - sessionId: {}", out.toString(CharsetUtil.UTF_8), msg.getSessionId());
            } else {
                log.trace("Out message: {}", out.toString(CharsetUtil.UTF_8));
            }
        }

        if (out.isReadable()) {
            channel.write(new DefaultHttpContent(out));
        } else {
            out.release();
        }

        if (msg instanceof OutPacketMessage) {
            OutPacketMessage outMsg = (OutPacketMessage) msg;
            if (outMsg.getClientHead().hasPollFlushedListeners()) {
                promise.addListener(f -> {
                    outMsg.getClientHead().notifyPollFlushed();
                });
            }
        }

        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, promise).addListener(ChannelFutureListener.CLOSE);
    }
    private void sendError(HttpErrorMessage errorMsg, ChannelHandlerContext ctx, ChannelPromise promise) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Sending HTTP error response, sessionId: {}, status: {}", 
                errorMsg.getSessionId(), HttpResponseStatus.BAD_REQUEST);
        }
        
        final ByteBuf encBuf = encoder.allocateBuffer(ctx.alloc());
        ByteBufOutputStream out = new ByteBufOutputStream(encBuf);
        encoder.getJsonSupport().writeValue(out, errorMsg.getData());

        sendMessage(errorMsg, ctx.channel(), encBuf, "application/json", promise, HttpResponseStatus.BAD_REQUEST);
    }

    private void addOriginHeaders(String origin, HttpResponse res) {
        if (version != null) {
            res.headers().add(HttpHeaderNames.SERVER, version);
        }

        if (configuration.isEnableCors()) {
            if (configuration.getOrigin() != null) {
                res.headers().add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, configuration.getOrigin());
                res.headers().add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, Boolean.TRUE);
            } else {
                if (origin != null) {
                    res.headers().add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                    res.headers().add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, Boolean.TRUE);
                } else {
                    res.headers().add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                }
            }
            if (configuration.getAllowHeaders() != null) {
                res.headers().add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, configuration.getAllowHeaders());
            }
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof HttpMessage)) {
            super.write(ctx, msg, promise);
            return;
        }

        if (log.isDebugEnabled()) {
            String sessionId = String.valueOf(((HttpMessage) msg).getSessionId());
            log.debug("Processing message type: {}, sessionId: {}", 
                msg.getClass().getSimpleName(), sessionId);
        }

        if (msg instanceof OutPacketMessage) {
            OutPacketMessage m = (OutPacketMessage) msg;
            if (m.getTransport() == Transport.WEBSOCKET) {
                if (log.isDebugEnabled()) {
                    log.debug("Routing to WebSocket handler, sessionId: {}", m.getSessionId());
                }
                handleWebsocket((OutPacketMessage) msg, ctx, promise);
            }
            if (m.getTransport() == Transport.POLLING) {
                if (log.isDebugEnabled()) {
                    log.debug("Routing to HTTP polling handler, sessionId: {}", m.getSessionId());
                }
                handleHTTP((OutPacketMessage) msg, ctx, promise);
            }
        } else if (msg instanceof XHROptionsMessage) {
            if (log.isDebugEnabled()) {
                log.debug("Processing XHR options message, sessionId: {}", ((XHROptionsMessage) msg).getSessionId());
            }
            write((XHROptionsMessage) msg, ctx, promise);
        } else if (msg instanceof XHRPostMessage) {
            if (log.isDebugEnabled()) {
                log.debug("Processing XHR POST message, sessionId: {}", ((XHRPostMessage) msg).getSessionId());
            }
            write((XHRPostMessage) msg, ctx, promise);
        } else if (msg instanceof HttpErrorMessage) {
            if (log.isDebugEnabled()) {
                log.debug("Processing HTTP error message, sessionId: {}", ((HttpErrorMessage) msg).getSessionId());
            }
            sendError((HttpErrorMessage) msg, ctx, promise);
        }
    }


    private void handleWebsocket(final OutPacketMessage msg, ChannelHandlerContext ctx, ChannelPromise promise) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Starting WebSocket message processing, sessionId: {}", msg.getSessionId());
        }
        
        ChannelFutureList writeFutureList = new ChannelFutureList();

        while (true) {
            Queue<Packet> queue = msg.getClientHead().getPacketsQueue(msg.getTransport());
            Packet packet = queue.poll();
            if (packet == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No more packets in queue, setting promise, sessionId: {}", msg.getSessionId());
                }
                writeFutureList.setChannelPromise(promise);
                break;
            }

            if (log.isDebugEnabled()) {
                log.debug("Processing packet type: {}, sessionId: {}", packet.getType(), msg.getSessionId());
            }

            ByteBuf out = encoder.allocateBuffer(ctx.alloc());
            EngineIOVersion engineIOVersion = msg.getClientHead().getEngineIOVersion();
            EncodeResult encodeResult = encoder.encodePacket(engineIOVersion, packet, out, ctx.alloc(), true);

            if (log.isTraceEnabled()) {
                log.trace("Out message: {} sessionId: {}", out.toString(CharsetUtil.UTF_8), msg.getSessionId());
            }
            
            if (out.isReadable()) {
                if (log.isDebugEnabled()) {
                    log.debug("Sending single WebSocket frame, size: {} bytes, sessionId: {}", 
                        out.readableBytes(), msg.getSessionId());
                }
                // Engine.IO requires every packet to occupy exactly one
                // WebSocket frame. The configured max frame payload applies to
                // inbound validation; splitting here would alter packet framing.
                WebSocketFrame res = new TextWebSocketFrame(out);
                ctx.channel().writeAndFlush(res);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Empty packet, releasing buffer, sessionId: {}", msg.getSessionId());
                }
                out.release();
            }

            for (ByteBuf buf : encodeResult.getAttachments()) {
                ByteBuf outBuf = encoder.allocateBuffer(ctx.alloc());
                if (EngineIOVersion.V3.equals(engineIOVersion)
                        || EngineIOVersion.V2.equals(engineIOVersion)) {
                    outBuf.writeByte(4);
                }
                outBuf.writeBytes(buf);
                if (log.isTraceEnabled()) {
                    log.trace("Out attachment: {} sessionId: {}", ByteBufUtil.hexDump(outBuf), msg.getSessionId());
                }
                writeFutureList.add(ctx.channel().writeAndFlush(new BinaryWebSocketFrame(outBuf)));
            }
        }
    }

    private void handleHTTP(OutPacketMessage msg, ChannelHandlerContext ctx, ChannelPromise promise) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Starting HTTP polling message processing, sessionId: {}", msg.getSessionId());
        }
        
        Channel channel = ctx.channel();
        Attribute<Boolean> attr = channel.attr(WRITE_ONCE);

        Queue<Packet> queue = msg.getClientHead().getPacketsQueue(msg.getTransport());

        if (!channel.isActive() || queue.isEmpty() || !attr.compareAndSet(null, true)) {
            if (log.isDebugEnabled()) {
                log.debug("HTTP processing skipped - channel active: {}, queue empty: {}, write once set: {}, sessionId: {}", 
                    channel.isActive(), queue.isEmpty(), attr.get() != null, msg.getSessionId());
            }
            promise.trySuccess();
            return;
        }

        ClientHead clientHead = msg.getClientHead();
        ByteBuf out = encoder.allocateBuffer(ctx.alloc());
        EngineIOVersion engineIOVersion = clientHead.getEngineIOVersion();
        if (engineIOVersion == null) {
            engineIOVersion = EngineIOVersion.V4;
        }

        Boolean b64 = ctx.channel().attr(EncoderHandler.B64).get();
        Integer jsonpIndex = ctx.channel().attr(EncoderHandler.JSONP_INDEX).get();
        // Engine.IO v3 selects JSONP with j=<index>; b64=1 is a separate
        // capability flag for base64 polling. Both use the legacy payload
        // encoder, while only JSONP must be returned as JavaScript.
        // Socket.IO v3/v4 also sends b64=1 but uses EIOv4 text framing.
        if (!EngineIOVersion.V4.equals(engineIOVersion)
                && (Boolean.TRUE.equals(b64) || jsonpIndex != null)) {
            if (log.isDebugEnabled()) {
                log.debug("Using JSONP encoding, index: {}, sessionId: {}", jsonpIndex, msg.getSessionId());
            }
            encoder.encodeJsonP(engineIOVersion, jsonpIndex, queue, out, ctx.alloc(), 50);
            String type = "application/javascript";
            if (jsonpIndex == null) {
                type = "text/plain";
            }
            sendMessage(msg, channel, out, type, promise, HttpResponseStatus.OK);
        } else {
            EncodePacketsResult result = encoder.encodePackets(engineIOVersion, queue, out, ctx.alloc(), 50);
            // Engine.IO v4 polling serializes every binary packet as base64 text
            // ("b<base64>") in a record-separated text payload. Only the legacy
            // v2/v3 binary payload format is sent as application/octet-stream.
            String contentType;
            if (result.hasBinary() && !EngineIOVersion.V4.equals(engineIOVersion))
                contentType = "application/octet-stream";
            else
                contentType = "text/plain";

            if (log.isDebugEnabled()) {
                log.debug("Using {} encoding, sessionId: {}", contentType, msg.getSessionId());
            }

            sendMessage(msg, channel, out, contentType, promise, HttpResponseStatus.OK);
        }
    }

    /**
     * Helper class for the handleWebsocket method, handles a list of ChannelFutures and
     * sets the status of a promise when
     * - any of the operations fail
     * - all of the operations succeed
     * The setChannelPromise method should be called after all the futures are added
     */
    private static class ChannelFutureList implements GenericFutureListener<Future<Void>> {

        private List<ChannelFuture> futureList = new ArrayList<>();
        private ChannelPromise promise = null;

        private void cleanup() {
            promise = null;
            for (ChannelFuture f : futureList) {
                f.removeListener(this);
            }
        }

        private void validate() {
            boolean allSuccess = true;
            for (ChannelFuture f : futureList) {
                if (f.isDone()) {
                    if (!f.isSuccess()) {
                        if (log.isDebugEnabled()) {
                            log.debug("ChannelFuture failed, setting promise failure, cause: {}", f.cause().getMessage());
                        }
                        promise.tryFailure(f.cause());
                        cleanup();
                        return;
                    }
                } else {
                    allSuccess = false;
                }
            }
            if (allSuccess) {
                if (log.isDebugEnabled()) {
                    log.debug("All ChannelFutures completed successfully, setting promise success");
                }
                promise.trySuccess();
                cleanup();
            }
        }

        public void add(ChannelFuture f) {
            futureList.add(f);
            f.addListener(this);
        }

        public void setChannelPromise(ChannelPromise p) {
            promise = p;
            validate();
        }

        @Override
        public void operationComplete(Future<Void> voidFuture) {
            if (promise != null) validate();
        }
    }

}
