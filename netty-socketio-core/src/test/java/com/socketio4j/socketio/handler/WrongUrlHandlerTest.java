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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WrongUrlHandler Tests")
class WrongUrlHandlerTest {

    @Test
    @DisplayName("Should answer BAD REQUEST, release the request and close the channel")
    void shouldRejectHttpRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(new WrongUrlHandler());
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/wrong/?key=value");

        channel.writeInbound(request);

        HttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        assertThat(request.refCnt()).isZero();
        assertThat(channel.isOpen()).isFalse();
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    @DisplayName("Should pass through messages which are not http requests")
    void shouldPassThroughNonHttpMessages() {
        EmbeddedChannel channel = new EmbeddedChannel(new WrongUrlHandler());

        channel.writeInbound("payload");

        assertThat((Object) channel.readOutbound()).isNull();
        assertThat((String) channel.readInbound()).isEqualTo("payload");
        assertThat(channel.isOpen()).isTrue();
        channel.finishAndReleaseAll();
    }
}
