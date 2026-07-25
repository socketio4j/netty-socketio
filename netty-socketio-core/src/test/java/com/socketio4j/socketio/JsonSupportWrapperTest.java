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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.protocol.AckArgs;
import com.socketio4j.socketio.protocol.JsonSupport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JsonSupportWrapper Tests")
class JsonSupportWrapperTest {

    private JsonSupport delegate;
    private JsonSupportWrapper wrapper;
    private ByteBuf buffer;

    private static final AckCallback<String> CALLBACK = new AckCallback<String>(String.class) {
        @Override
        public void onSuccess(String result) {
        }
    };

    @BeforeEach
    void setUp() {
        delegate = mock(JsonSupport.class);
        wrapper = new JsonSupportWrapper(delegate);
        buffer = Unpooled.copiedBuffer("{\"a\":1}", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        buffer.release();
    }

    private ByteBufInputStream inputStream() {
        ByteBufInputStream in = new ByteBufInputStream(buffer);
        in.mark(buffer.readableBytes());
        return in;
    }

    @Test
    @DisplayName("Should delegate ack args reading")
    void shouldDelegateReadAckArgs() throws IOException {
        AckArgs expected = new AckArgs(Collections.singletonList("value"));
        ByteBufInputStream in = inputStream();
        when(delegate.readAckArgs(in, CALLBACK)).thenReturn(expected);

        assertThat(wrapper.readAckArgs(in, CALLBACK)).isSameAs(expected);
    }

    @Test
    @DisplayName("Should wrap ack args reading failures into an IOException")
    void shouldWrapReadAckArgsFailure() throws IOException {
        ByteBufInputStream in = inputStream();
        when(delegate.readAckArgs(any(), any())).thenThrow(new IllegalStateException("broken"));

        assertThatThrownBy(() -> wrapper.readAckArgs(in, CALLBACK))
                .isInstanceOf(IOException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Should delegate value reading")
    void shouldDelegateReadValue() throws IOException {
        ByteBufInputStream in = inputStream();
        when(delegate.readValue("ns", in, String.class)).thenReturn("value");

        assertThat(wrapper.readValue("ns", in, String.class)).isEqualTo("value");
    }

    @Test
    @DisplayName("Should wrap value reading failures into an IOException")
    void shouldWrapReadValueFailure() throws IOException {
        ByteBufInputStream in = inputStream();
        when(delegate.readValue(eq("ns"), any(), eq(String.class)))
                .thenThrow(new IllegalArgumentException("broken"));

        assertThatThrownBy(() -> wrapper.readValue("ns", in, String.class))
                .isInstanceOf(IOException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should delegate value writing")
    void shouldDelegateWriteValue() throws IOException {
        ByteBufOutputStream out = new ByteBufOutputStream(buffer);

        wrapper.writeValue(out, "value");

        verify(delegate).writeValue(out, "value");
    }

    @Test
    @DisplayName("Should wrap value writing failures into an IOException")
    void shouldWrapWriteValueFailure() throws IOException {
        ByteBufOutputStream out = new ByteBufOutputStream(buffer);
        doThrow(new IllegalStateException("broken")).when(delegate).writeValue(out, "value");

        assertThatThrownBy(() -> wrapper.writeValue(out, "value"))
                .isInstanceOf(IOException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Should delegate event mapping and array access without wrapping")
    void shouldDelegateRemainingCalls() {
        List<byte[]> arrays = Collections.singletonList(new byte[]{1, 2});
        when(delegate.getArrays()).thenReturn(arrays);

        wrapper.addEventMapping("ns", "event", String.class);
        wrapper.removeEventMapping("ns", "event");

        assertThat(wrapper.getArrays()).isSameAs(arrays);
        verify(delegate).addEventMapping("ns", "event", String.class);
        verify(delegate).removeEventMapping("ns", "event");
    }
}
