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

import java.util.Collections;
import java.util.List;

import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.namespace.NamespacesHub;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.scheduler.CancelableScheduler;
import com.socketio4j.socketio.scheduler.SchedulerKey;
import com.socketio4j.socketio.transport.NamespaceClient;
import com.socketio4j.socketio.transport.PollingTransport;

import io.netty.channel.ChannelFuture;

public class PacketListener {

    private final NamespacesHub namespacesHub;
    private final AckManager ackManager;
    private final CancelableScheduler scheduler;

    public PacketListener(AckManager ackManager, NamespacesHub namespacesHub, PollingTransport xhrPollingTransport,
            CancelableScheduler scheduler) {
        this.ackManager = ackManager;
        this.namespacesHub = namespacesHub;
        this.scheduler = scheduler;
    }

    /**
     * Handles Engine.IO packets that are valid before any Socket.IO namespace has
     * been connected. Engine.IO ping/pong and transport upgrade are session-level
     * concerns, while {@link #onPacket(Packet, NamespaceClient, Transport)} handles
     * the namespace-scoped Socket.IO message layer.
     */
    public void onTransportPacket(Packet packet, ClientHead client, Transport transport) {
        switch (packet.getType()) {
        case PING: {
            boolean upgrading = "probe".equals(packet.getData())
                    && transport == Transport.WEBSOCKET
                    && client.getCurrentTransport() == Transport.POLLING;
            // EIO v3 is client-ping/server-pong. EIO v4 reverses this
            // direction, except for the PING "probe" sent on the temporary
            // WebSocket while upgrading from polling.
            if (EngineIOVersion.V4.equals(client.getEngineIOVersion()) && !upgrading) {
                client.onChannelDisconnect();
                return;
            }
            Packet outPacket = new Packet(PacketType.PONG);
            outPacket.setData(packet.getData());
            if (upgrading) {
                ChannelFuture pongFuture = client.send(outPacket, transport);
                if (pongFuture != null) {
                    pongFuture.addListener(future -> {
                        if (future.isSuccess()) {
                            client.beginUpgrade();
                            client.send(new Packet(PacketType.NOOP), Transport.POLLING);
                        }
                    });
                }
            } else {
                client.send(outPacket, transport);
                client.schedulePingTimeout();
            }
            notifyPing(client, packet, true);
            break;
        }
        case PONG:
            // EIO v4 is server-ping/client-pong. A PONG from an EIO v3
            // client is therefore a protocol error.
            if (!EngineIOVersion.V4.equals(client.getEngineIOVersion())) {
                client.onChannelDisconnect();
                return;
            }
            client.schedulePingTimeout();
            notifyPing(client, packet, false);
            break;

        case UPGRADE:
            // An upgrade is valid only after the WebSocket probe succeeded.
            if (transport != Transport.WEBSOCKET || !client.isUpgradeInProgress()) {
                client.onChannelDisconnect();
                return;
            }
            client.schedulePingTimeout();
            scheduler.cancel(new SchedulerKey(SchedulerKey.Type.UPGRADE_TIMEOUT, client.getSessionId()));
            client.upgradeCurrentTransport(transport);
            break;

        case CLOSE:
            client.onChannelDisconnect();
            break;

        default:
            break;
        }
    }

    private void notifyPing(ClientHead client, Packet packet, boolean ping) {
        Namespace namespace = namespacesHub.get(packet.getNsp());
        if (namespace == null) {
            return;
        }
        NamespaceClient namespaceClient = client.getChildClient(namespace);
        if (namespaceClient == null) {
            return;
        }
        if (ping) {
            namespace.onPing(namespaceClient);
        } else {
            namespace.onPong(namespaceClient);
        }
    }

    public void onPacket(Packet packet, NamespaceClient client, Transport transport) {
        final AckRequest ackRequest = new AckRequest(packet, client);

        if (packet.isAckRequested()) {
            ackManager.initAckIndex(client.getSessionId(), packet.getAckId());
        }

        switch (packet.getType()) {
        case PING: {
            Packet outPacket = new Packet(PacketType.PONG);
            outPacket.setData(packet.getData());
            // TODO use future
            client.getBaseClient().send(outPacket, transport);
            if ("probe".equals(packet.getData())) {
                client.getBaseClient().send(new Packet(PacketType.NOOP), Transport.POLLING);
            } else {
                client.getBaseClient().schedulePingTimeout();
            }
            Namespace namespace = namespacesHub.get(packet.getNsp());
            namespace.onPing(client);
            break;
        }
        case PONG: {
            client.getBaseClient().schedulePingTimeout();
            Namespace namespace = namespacesHub.get(packet.getNsp());
            namespace.onPong(client);
            break;
        }

        case UPGRADE: {
            client.getBaseClient().schedulePingTimeout();

            SchedulerKey key = new SchedulerKey(SchedulerKey.Type.UPGRADE_TIMEOUT, client.getSessionId());
            scheduler.cancel(key);

            client.getBaseClient().upgradeCurrentTransport(transport);
            break;
        }

        case MESSAGE: {
            if (packet.getSubType() == PacketType.DISCONNECT) {
                client.onDisconnect();
            } else {
                client.getBaseClient().schedulePingTimeout();
            }

            if (packet.getSubType() == PacketType.CONNECT) {
                Namespace namespace = namespacesHub.get(packet.getNsp());
                namespace.onConnect(client);
                // send connect handshake packet back to client
                if (!EngineIOVersion.V4.equals(client.getEngineIOVersion())) {
                    client.getBaseClient().send(packet, transport);
                }
            }

            if (packet.getSubType() == PacketType.ACK
                    || packet.getSubType() == PacketType.BINARY_ACK) {
                ackManager.onAck(client, packet);
            }

            if (packet.getSubType() == PacketType.EVENT
                    || packet.getSubType() == PacketType.BINARY_EVENT) {
                Namespace namespace = namespacesHub.get(packet.getNsp());
                List<Object> args = Collections.emptyList();
                if (packet.getData() != null) {
                    args = packet.getData();
                }
                namespace.onEvent(client, packet.getName(), args, ackRequest);
            }
            break;
        }

        case CLOSE:
            client.getBaseClient().onChannelDisconnect();
            break;

        default:
            break;
        }
    }

}
