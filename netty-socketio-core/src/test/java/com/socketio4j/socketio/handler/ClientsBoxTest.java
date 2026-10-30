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

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientsBoxTest {

    private ClientsBox clientsBox;

    @BeforeEach
    void setUp() {
        clientsBox = new ClientsBox();
    }

    @Test
    @DisplayName("Basic add, get and remove client by session ID")
    void testBasicClientOperations() {
        UUID sessionId = UUID.randomUUID();
        ClientHead client = mock(ClientHead.class);
        when(client.getSessionId()).thenReturn(sessionId);

        assertThat(clientsBox.getClientsCount()).isEqualTo(0);
        assertThat(clientsBox.get(sessionId)).isNull();

        clientsBox.addClient(client);

        assertThat(clientsBox.getClientsCount()).isEqualTo(1);
        assertThat(clientsBox.get(sessionId)).isSameAs(client);
        assertThat(clientsBox.getAllClients()).contains(client);

        ClientHead removed = clientsBox.removeClient(sessionId);
        assertThat(removed).isSameAs(client);
        assertThat(clientsBox.getClientsCount()).isEqualTo(0);
        assertThat(clientsBox.get(sessionId)).isNull();
    }

    @Test
    @DisplayName("canAcceptClient checks capacity against limit")
    void testCanAcceptClient() {
        assertThat(clientsBox.canAcceptClient(0)).isTrue();
        assertThat(clientsBox.canAcceptClient(-1)).isTrue();
        assertThat(clientsBox.canAcceptClient(2)).isTrue();

        UUID sid1 = UUID.randomUUID();
        ClientHead client1 = mock(ClientHead.class);
        when(client1.getSessionId()).thenReturn(sid1);
        clientsBox.addClient(client1);

        assertThat(clientsBox.canAcceptClient(2)).isTrue();
        assertThat(clientsBox.canAcceptClient(1)).isFalse();

        UUID sid2 = UUID.randomUUID();
        ClientHead client2 = mock(ClientHead.class);
        when(client2.getSessionId()).thenReturn(sid2);
        clientsBox.addClient(client2);

        assertThat(clientsBox.canAcceptClient(2)).isFalse();
        assertThat(clientsBox.canAcceptClient(3)).isTrue();
    }

    @Test
    @DisplayName("tryAddClient enforces max limit correctly")
    void testTryAddClientWithLimit() {
        UUID sid1 = UUID.randomUUID();
        ClientHead client1 = mock(ClientHead.class);
        when(client1.getSessionId()).thenReturn(sid1);

        UUID sid2 = UUID.randomUUID();
        ClientHead client2 = mock(ClientHead.class);
        when(client2.getSessionId()).thenReturn(sid2);

        // Limit = 1
        assertThat(clientsBox.tryAddClient(client1, 1)).isTrue();
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);

        // Updating the same client session is allowed
        assertThat(clientsBox.tryAddClient(client1, 1)).isTrue();
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);

        // Adding second client is rejected when limit is 1
        assertThat(clientsBox.tryAddClient(client2, 1)).isFalse();
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);
        assertThat(clientsBox.get(sid2)).isNull();

        // Unlimited (<= 0) allows adding
        assertThat(clientsBox.tryAddClient(client2, 0)).isTrue();
        assertThat(clientsBox.getClientsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Channel mapping operations")
    void testChannelOperations() {
        Channel channel = new EmbeddedChannel();
        ClientHead client = mock(ClientHead.class);

        assertThat(clientsBox.get(channel)).isNull();

        clientsBox.add(channel, client);
        assertThat(clientsBox.get(channel)).isSameAs(client);

        clientsBox.remove(channel);
        assertThat(clientsBox.get(channel)).isNull();
    }

    @Test
    @DisplayName("Session ID collision in tryAddClient does not replace active client")
    void testSessionIdCollisionInTryAddClient() {
        UUID sid = UUID.randomUUID();
        ClientHead client1 = mock(ClientHead.class);
        when(client1.getSessionId()).thenReturn(sid);

        ClientHead client2 = mock(ClientHead.class);
        when(client2.getSessionId()).thenReturn(sid);

        assertThat(clientsBox.tryAddClient(client1, 10)).isTrue();
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);
        assertThat(clientsBox.get(sid)).isSameAs(client1);

        // Attempt to register different client with same session ID must fail
        assertThat(clientsBox.tryAddClient(client2, 10)).isFalse();
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);
        assertThat(clientsBox.get(sid)).isSameAs(client1);
    }

    @Test
    @DisplayName("Session ID collision in tryAddClient unlimited does not replace active client")
    void testSessionIdCollisionInTryAddClientUnlimited() {
        UUID sid = UUID.randomUUID();
        ClientHead client1 = mock(ClientHead.class);
        when(client1.getSessionId()).thenReturn(sid);

        ClientHead client2 = mock(ClientHead.class);
        when(client2.getSessionId()).thenReturn(sid);

        assertThat(clientsBox.tryAddClient(client1, 0)).isTrue();
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);
        assertThat(clientsBox.get(sid)).isSameAs(client1);

        // Collision rejected even when unlimited
        assertThat(clientsBox.tryAddClient(client2, 0)).isFalse();
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);
        assertThat(clientsBox.get(sid)).isSameAs(client1);
    }

    @Test
    @DisplayName("Session ID collision in addClient preserves existing active client")
    void testSessionIdCollisionInAddClient() {
        UUID sid = UUID.randomUUID();
        ClientHead client1 = mock(ClientHead.class);
        when(client1.getSessionId()).thenReturn(sid);

        ClientHead client2 = mock(ClientHead.class);
        when(client2.getSessionId()).thenReturn(sid);

        clientsBox.addClient(client1);
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);
        assertThat(clientsBox.get(sid)).isSameAs(client1);

        // Colliding addClient must not displace client1
        clientsBox.addClient(client2);
        assertThat(clientsBox.getClientsCount()).isEqualTo(1);
        assertThat(clientsBox.get(sid)).isSameAs(client1);
    }
}
