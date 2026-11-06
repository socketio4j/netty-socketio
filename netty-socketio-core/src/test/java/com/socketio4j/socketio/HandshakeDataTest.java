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
package com.socketio4j.socketio;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HandshakeData Tests")
class HandshakeDataTest {

    private static final InetSocketAddress REMOTE = InetSocketAddress.createUnresolved("10.0.0.1", 5555);
    private static final InetSocketAddress LOCAL = InetSocketAddress.createUnresolved("127.0.0.1", 8080);

    private static HandshakeData handshakeData(Map<String, List<String>> urlParams) {
        HttpHeaders headers = new DefaultHttpHeaders().add("Origin", "http://localhost");
        return new HandshakeData(headers, urlParams, REMOTE, LOCAL, "/socket.io/?EIO=4", true);
    }

    @Test
    @DisplayName("Should expose all handshake attributes")
    void shouldExposeAllAttributes() {
        Map<String, List<String>> urlParams = new HashMap<>();
        urlParams.put("EIO", Collections.singletonList("4"));
        HandshakeData data = handshakeData(urlParams);

        assertThat(data.getAddress()).isEqualTo(REMOTE);
        assertThat(data.getLocal()).isEqualTo(LOCAL);
        assertThat(data.getUrl()).isEqualTo("/socket.io/?EIO=4");
        assertThat(data.isXdomain()).isTrue();
        assertThat(data.getUrlParams()).isEqualTo(urlParams);
        assertThat(data.getHttpHeaders().get("Origin")).isEqualTo("http://localhost");
        assertThat(data.getTime()).isNotNull();
    }

    @Test
    @DisplayName("Should leave the local address unset when it is not provided")
    void shouldLeaveLocalAddressUnset() {
        HandshakeData data = new HandshakeData(new DefaultHttpHeaders(), Collections.emptyMap(),
                REMOTE, "/socket.io/", false);

        assertThat(data.getLocal()).isNull();
        assertThat(data.isXdomain()).isFalse();
    }

    @Test
    @DisplayName("Should return a single url param value only when it is unambiguous")
    void shouldReturnSingleUrlParam() {
        Map<String, List<String>> urlParams = new HashMap<>();
        urlParams.put("single", Collections.singletonList("value"));
        urlParams.put("multiple", Arrays.asList("first", "second"));
        urlParams.put("empty", Collections.emptyList());
        HandshakeData data = handshakeData(urlParams);

        assertThat(data.getSingleUrlParam("single")).isEqualTo("value");
        assertThat(data.getSingleUrlParam("multiple")).isNull();
        assertThat(data.getSingleUrlParam("empty")).isNull();
        assertThat(data.getSingleUrlParam("unknown")).isNull();
    }

    @Test
    @DisplayName("Should store the auth token")
    void shouldStoreAuthToken() {
        HandshakeData data = handshakeData(Collections.emptyMap());

        assertThat(data.getAuthToken()).isNull();
        data.setAuthToken("token");

        assertThat(data.getAuthToken()).isEqualTo("token");
    }

    @Test
    @DisplayName("Should provide a no argument constructor for deserialization")
    void shouldProvideNoArgConstructor() {
        HandshakeData data = new HandshakeData();

        assertThat(data.getTime()).isNotNull();
        assertThat(data.getAddress()).isNull();
        assertThat(data.getUrl()).isNull();
    }
}
