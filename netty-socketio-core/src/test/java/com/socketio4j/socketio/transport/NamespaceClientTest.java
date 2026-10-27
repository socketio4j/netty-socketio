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

import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.protocol.Packet;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import java.io.IOException;

import static org.mockito.Mockito.*;


public class NamespaceClientTest {
    @Test
    @DisplayName("Should cleanup namespace even when disconnect send fails")
    void shouldCleanupNamespaceWhenDisconnectSendFails() {

        ClientHead baseClient = mock(ClientHead.class);
        Namespace namespace = mock(Namespace.class);

        when(namespace.getName()).thenReturn("/chat");
        when(baseClient.isConnected()).thenReturn(true);

        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPromise promise = channel.newPromise();

        when(baseClient.send(any(Packet.class))).thenReturn(promise);

        NamespaceClient client = new NamespaceClient(baseClient, namespace);

        client.disconnect();

        promise.setFailure(new IOException("boom"));

        verify(baseClient).removeNamespaceClient(client);
        verify(namespace).onDisconnect(client);
    }

    @Test
    @DisplayName("Should defer cleanup for polling transport when send returns null")
    void shouldDeferCleanupForPollingTransportWhenSendReturnsNull() {
        ClientHead baseClient = mock(ClientHead.class);
        Namespace namespace = mock(Namespace.class);

        when(namespace.getName()).thenReturn("/chat");
        when(baseClient.isConnected()).thenReturn(true);
        when(baseClient.getCurrentTransport()).thenReturn(com.socketio4j.socketio.Transport.POLLING);
        when(baseClient.send(any(Packet.class))).thenReturn(null);

        NamespaceClient client = new NamespaceClient(baseClient, namespace);

        client.disconnect();

        // Should not immediately remove namespace client
        verify(baseClient, never()).removeNamespaceClient(client);
        verify(namespace, never()).onDisconnect(client);

        // Verify onPollFlushed was registered and invoke its callback
        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(baseClient).onPollFlushed(captor.capture(), eq(5000L));

        captor.getValue().run();

        // Now it should be cleaned up
        verify(baseClient).removeNamespaceClient(client);
        verify(namespace).onDisconnect(client);
    }

    @Test
    @DisplayName("Should immediately cleanup when send returns null and not polling")
    void shouldImmediatelyCleanupWhenSendReturnsNullAndNotPolling() {
        ClientHead baseClient = mock(ClientHead.class);
        Namespace namespace = mock(Namespace.class);

        when(namespace.getName()).thenReturn("/chat");
        when(baseClient.isConnected()).thenReturn(true);
        when(baseClient.getCurrentTransport()).thenReturn(com.socketio4j.socketio.Transport.WEBSOCKET);
        when(baseClient.send(any(Packet.class))).thenReturn(null);

        NamespaceClient client = new NamespaceClient(baseClient, namespace);

        client.disconnect();

        verify(baseClient).removeNamespaceClient(client);
        verify(namespace).onDisconnect(client);
    }
}
