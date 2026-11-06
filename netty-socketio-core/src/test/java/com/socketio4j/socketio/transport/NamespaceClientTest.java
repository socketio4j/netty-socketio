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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.socketio4j.socketio.AckCallback;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;

import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


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

    @Test
    @DisplayName("Should execute onDisconnect only once when called repeatedly")
    void shouldExecuteOnDisconnectOnlyOnce() {
        ClientHead baseClient = mock(ClientHead.class);
        Namespace namespace = mock(Namespace.class);
        when(namespace.getName()).thenReturn("/chat");

        NamespaceClient client = new NamespaceClient(baseClient, namespace);
        client.onDisconnect();
        client.onDisconnect();

        verify(baseClient, times(1)).removeNamespaceClient(client);
        verify(namespace, times(1)).onDisconnect(client);
    }

    @Test
    @DisplayName("Should isolate ackId per recipient when sending shared Packet with AckCallback")
    void shouldIsolateAckIdWhenSendingSharedPacketWithAckCallback() {
        Namespace namespace = mock(Namespace.class);
        when(namespace.getName()).thenReturn("/chat");

        AckManager ackManager = mock(AckManager.class);
        UUID session1 = UUID.randomUUID();
        UUID session2 = UUID.randomUUID();
        when(ackManager.registerAck(eq(session1), any(AckCallback.class))).thenReturn(101L);
        when(ackManager.registerAck(eq(session2), any(AckCallback.class))).thenReturn(202L);

        List<Packet> sentPackets = new ArrayList<>();

        ClientHead base1 = mock(ClientHead.class);
        when(base1.isConnected()).thenReturn(true);
        when(base1.getSessionId()).thenReturn(session1);
        when(base1.getAckManager()).thenReturn(ackManager);
        doAnswer(inv -> {
            sentPackets.add(inv.getArgument(0));
            return null;
        }).when(base1).send(any(Packet.class));

        ClientHead base2 = mock(ClientHead.class);
        when(base2.isConnected()).thenReturn(true);
        when(base2.getSessionId()).thenReturn(session2);
        when(base2.getAckManager()).thenReturn(ackManager);
        doAnswer(inv -> {
            sentPackets.add(inv.getArgument(0));
            return null;
        }).when(base2).send(any(Packet.class));

        NamespaceClient client1 = new NamespaceClient(base1, namespace);
        NamespaceClient client2 = new NamespaceClient(base2, namespace);

        AckCallback<String> cb = new AckCallback<String>(String.class) {
            @Override
            public void onSuccess(String result) {
            }
        };

        client1.sendEvent("ping-ack", cb, "payload");
        client2.sendEvent("ping-ack", cb, "payload");

        assertEquals(2, sentPackets.size());
        assertNotSame(sentPackets.get(0), sentPackets.get(1));
        assertEquals(101L, sentPackets.get(0).getAckId());
        assertEquals(202L, sentPackets.get(1).getAckId());
    }
}
