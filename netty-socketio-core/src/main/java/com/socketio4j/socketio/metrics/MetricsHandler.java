package com.socketio4j.socketio.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * @author https://github.com/sanjomo
 * @date 07/01/26 6:31 pm
 */

public final class MetricsHandler
        extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final PrometheusMeterRegistry registry;
    private final String metricsUrl;

    MetricsHandler(PrometheusMeterRegistry registry, String metricsUrl) {
        this.registry = registry;
        this.metricsUrl = metricsUrl;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx,
                                FullHttpRequest req) {

        if (!HttpMethod.GET.equals(req.method())
                || !metricsUrl.equals(req.uri())) {

            send(ctx);
            return;
        }

        String body = registry.scrape();

        FullHttpResponse response =
                new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(body, CharsetUtil.UTF_8)
                );

        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE,
                        "text/plain; version=0.0.4")
                .set(HttpHeaderNames.CONTENT_LENGTH,
                        response.content().readableBytes());

        ctx.writeAndFlush(response);
    }

    private void send(ChannelHandlerContext ctx) {

        FullHttpResponse response =
                new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.NOT_FOUND,
                        Unpooled.copiedBuffer("Not Found", CharsetUtil.UTF_8)
                );

        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                .set(HttpHeaderNames.CONTENT_LENGTH,
                        response.content().readableBytes());

        ctx.writeAndFlush(response);
    }
}