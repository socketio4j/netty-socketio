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

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.socketio4j.socketio.HandshakeData;
import com.socketio4j.socketio.annotation.Internal;

import io.netty.channel.Channel;

@Internal
public class ClientsBox {

    private final Map<UUID, ClientHead> uuid2clients = new ConcurrentHashMap<>();
    private final Map<Channel, ClientHead> channel2clients = new ConcurrentHashMap<>();
    private final AtomicInteger clientsCount = new AtomicInteger();

    // TODO use storeFactory
    public HandshakeData getHandshakeData(UUID sessionId) {
        ClientHead client = uuid2clients.get(sessionId);
        if (client == null) {
            return null;
        }

        return client.getHandshakeData();
    }

    public void addClient(ClientHead clientHead) {
        if (uuid2clients.putIfAbsent(clientHead.getSessionId(), clientHead) == null) {
            clientsCount.incrementAndGet();
        }
    }

    public boolean tryAddClient(ClientHead clientHead, int maxLimit) {
        ClientHead existing = uuid2clients.get(clientHead.getSessionId());
        if (existing != null) {
            return existing == clientHead;
        }

        if (maxLimit <= 0) {
            ClientHead prev = uuid2clients.putIfAbsent(clientHead.getSessionId(), clientHead);
            if (prev == null) {
                clientsCount.incrementAndGet();
                return true;
            }
            return prev == clientHead;
        }

        for (;;) {
            int current = clientsCount.get();
            if (current >= maxLimit) {
                return false;
            }
            if (clientsCount.compareAndSet(current, current + 1)) {
                ClientHead previous = uuid2clients.putIfAbsent(clientHead.getSessionId(), clientHead);
                if (previous != null) {
                    clientsCount.decrementAndGet();
                    return previous == clientHead;
                }
                return true;
            }
        }
    }

    public boolean canAcceptClient(int maxLimit) {
        if (maxLimit <= 0) {
            return true;
        }
        return clientsCount.get() < maxLimit;
    }

    public int getClientsCount() {
        return clientsCount.get();
    }

    public Collection<ClientHead> getAllClients() {
        return Collections.unmodifiableCollection(uuid2clients.values());
    }

    public ClientHead removeClient(UUID sessionId) {
        ClientHead removed = uuid2clients.remove(sessionId);
        if (removed != null) {
            clientsCount.decrementAndGet();
        }
        return removed;
    }

    public ClientHead get(UUID sessionId) {
        return uuid2clients.get(sessionId);
    }

    public void add(Channel channel, ClientHead clientHead) {
        channel2clients.put(channel, clientHead);
    }

    public void remove(Channel channel) {
        channel2clients.remove(channel);
    }


    public ClientHead get(Channel channel) {
        return channel2clients.get(channel);
    }

}
