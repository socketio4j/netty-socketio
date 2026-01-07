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