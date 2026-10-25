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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit test for WrongUrlHandler.
 * Verifies that invalid context path requests return HTTP 400 Bad Request and close the channel.
 */

public class WrongUrlHandlerTest {

    private WrongUrlHandler handler;
    private EmbeddedChannel channel;

    @BeforeEach
    public void setUp() {
        handler = new WrongUrlHandler();
        channel = new EmbeddedChannel(handler);
    }

    @Test
    public void testWrongUrlReturnsBadRequestAndClosesChannel() throws Exception {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/wrong/socket.io/path");

        channel.writeInbound(req);

        HttpResponse res = channel.readOutbound();
        assertNotNull(res, "Response should not be null");
        assertEquals(HttpResponseStatus.BAD_REQUEST, res.status(), "Response status should be 400 Bad Request");

        // Verify channel is closed after writing bad request response
        assertFalse(channel.isOpen(), "Channel should be closed after handling wrong URL request");
        assertEquals(0, req.refCnt(), "HTTP Request reference count should be released");
    }
}
