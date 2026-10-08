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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.DisconnectableHub;
import com.socketio4j.socketio.HandshakeData;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.messages.OutPacketMessage;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.scheduler.CancelableScheduler;
import com.socketio4j.socketio.scheduler.SchedulerKey;
import com.socketio4j.socketio.scheduler.SchedulerKey.Type;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.StoreFactory;
import com.socketio4j.socketio.transport.NamespaceClient;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AttributeKey;

public class ClientHead {

    private static final Logger log = LoggerFactory.getLogger(ClientHead.class);

    public static final AttributeKey<ClientHead> CLIENT = AttributeKey.<ClientHead>valueOf("client");

    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicBoolean pollingPostActive = new AtomicBoolean();
    private final AtomicBoolean upgradeInProgress = new AtomicBoolean();
    private final Map<Namespace, NamespaceClient> namespaceClients = new ConcurrentHashMap<>();
    private final Map<Transport, TransportState> channels = new HashMap<Transport, TransportState>(2);
    private final HandshakeData handshakeData;
    private final UUID sessionId;

    private final EngineIOVersion engineIOVersion;

    private final Store store;
    private final DisconnectableHub disconnectableHub;
    private final AckManager ackManager;
    private ClientsBox clientsBox;
    private final CancelableScheduler scheduler;
    private final Configuration configuration;

    private Packet lastBinaryPacket;
    private ByteBuf lastBinaryPacketSource;

    // TODO use lazy set
    private volatile Transport currentTransport;

    public ClientHead(UUID sessionId, AckManager ackManager, DisconnectableHub disconnectable,
                      StoreFactory storeFactory, HandshakeData handshakeData, ClientsBox clientsBox, Transport transport, CancelableScheduler scheduler,
                      Configuration configuration, Map<String, List<String>> params) {
        this.sessionId = sessionId;
        this.ackManager = ackManager;
        this.disconnectableHub = disconnectable;
        this.store = storeFactory.createStore(sessionId);
        this.handshakeData = handshakeData;
        this.clientsBox = clientsBox;
        this.currentTransport = transport;
        this.scheduler = scheduler;
        this.configuration = configuration;

        channels.put(Transport.POLLING, new TransportState());
        channels.put(Transport.WEBSOCKET, new TransportState());

        List<String> versions = params.getOrDefault(EngineIOVersion.EIO, new ArrayList<>());
        if (versions.isEmpty()) {
            engineIOVersion = EngineIOVersion.V4;
        } else {
            engineIOVersion = EngineIOVersion.fromValue(versions.get(0));
        }
    }

    public void bindChannel(Channel channel, Transport transport) {
        if (!isConnected()) {
            return;
        }
        log.debug("binding channel: {} to transport: {}", channel, transport);

        TransportState state = channels.get(transport);
        Channel prevChannel = state.update(channel);
        if (prevChannel != null) {
            clientsBox.remove(prevChannel);
        }
        clientsBox.add(channel, this);
        if (!isConnected()) {
            clientsBox.remove(channel);
            state.compareAndSet(channel, null);
            return;
        }
        sendPackets(transport, channel);
    }

    /**
     * Binds the outstanding long-poll response, rejecting a second concurrent
     * GET instead of replacing the first response channel.
     */
    public boolean tryBindPollingChannel(Channel channel) {
        return tryBindChannel(channel, Transport.POLLING);
    }

    /** Engine.IO permits only one WebSocket connection for a session. */
    public boolean tryBindWebSocketChannel(Channel channel) {
        return tryBindChannel(channel, Transport.WEBSOCKET);
    }

    private boolean tryBindChannel(Channel channel, Transport transport) {
        if (!isConnected()) {
            return false;
        }

        TransportState state = channels.get(transport);
        for (;;) {
            Channel current = state.getChannel();
            if (current != null && current != channel && current.isActive()) {
                return false;
            }
            if (!state.compareAndSet(current, channel)) {
                continue;
            }

            log.debug("binding channel: {} to transport: {}", channel, transport);
            if (current != null) {
                clientsBox.remove(current);
            }
            clientsBox.add(channel, this);
            if (!isConnected()) {
                clientsBox.remove(channel);
                state.compareAndSet(channel, null);
                return false;
            }
            sendPackets(transport, channel);
            return true;
        }
    }

    /** Engine.IO permits only one polling POST to be active for a session. */
    public boolean tryAcquirePollingPost() {
        return pollingPostActive.compareAndSet(false, true);
    }

    public void releasePollingPost() {
        pollingPostActive.set(false);
    }

    public void releasePollingChannel(Channel channel) {
        try {
            if (channels.get(Transport.POLLING).compareAndSet(channel, null)) {
                clientsBox.remove(channel);
            }
        } catch (Exception e) {
            log.error("Failed to release polling channel for session: {}", sessionId, e);
        }
    }

    public String getOrigin() {
        return handshakeData.getHttpHeaders().get(HttpHeaderNames.ORIGIN);
    }

    public @Nullable ChannelFuture send(Packet packet) {
        return send(packet, getCurrentTransport());
    }

    public void cancelPing() {
        try {
            SchedulerKey key = new SchedulerKey(Type.PING, sessionId);
            scheduler.cancel(key);
        } catch (Exception e) {
            log.error("Failed to cancel ping task for session: {}", sessionId, e);
        }
    }
    public void cancelPingTimeout() {
        try {
            SchedulerKey key = new SchedulerKey(Type.PING_TIMEOUT, sessionId);
            scheduler.cancel(key);
        } catch (Exception e) {
            log.error("Failed to cancel ping timeout task for session: {}", sessionId, e);
        }
    }

    public void schedulePing() {
        cancelPing();
        final SchedulerKey key = new SchedulerKey(Type.PING, sessionId);
        scheduler.schedule(key, () -> {
            ClientHead client = clientsBox.get(sessionId);
            if (client != null) {
                EngineIOVersion version = client.getEngineIOVersion();
                //only send ping packet for engine.io version 4
                if (EngineIOVersion.V4.equals(version)) {
                    client.send(new Packet(PacketType.PING));
                }
                schedulePing();
            }
        }, configuration.getPingInterval(), TimeUnit.MILLISECONDS);
    }

    public void schedulePingTimeout() {
        cancelPingTimeout();
        SchedulerKey key = new SchedulerKey(Type.PING_TIMEOUT, sessionId);
        scheduler.schedule(key, () -> {
            ClientHead client = clientsBox.get(sessionId);
            if (client != null) {
                client.disconnect();
                log.debug("{} removed due to ping timeout", sessionId);
            }
        }, configuration.getPingTimeout() + configuration.getPingInterval(), TimeUnit.MILLISECONDS);
    }

    public @Nullable ChannelFuture send(Packet packet, Transport transport) {
        TransportState state = channels.get(transport);
        state.getPacketsQueue().add(packet);

        Channel channel = state.getChannel();
        if (channel == null
                || (transport == Transport.POLLING && channel.attr(EncoderHandler.WRITE_ONCE).get() != null)) {
            return null;
        }
        return sendPackets(transport, channel);
    }

    private ChannelFuture sendPackets(Transport transport, Channel channel) {
        return channel.writeAndFlush(new OutPacketMessage(this, transport));
    }

    public void removeNamespaceClient(NamespaceClient client) {
        namespaceClients.remove(client.getNamespace());
        // A Socket.IO namespace disconnect does not necessarily close the
        // underlying Engine.IO session. Keep its SID registered until the
        // transport closes so a polling client can finish its final request
        // without receiving a spurious "Session ID unknown" response.
    }

    public NamespaceClient getChildClient(Namespace namespace) {
        return namespaceClients.get(namespace);
    }

    public NamespaceClient addNamespaceClient(Namespace namespace) {
        NamespaceClient client = new NamespaceClient(this, namespace);
        return addNamespaceClient(client);
    }

    /**
     * Registers a namespace client after protocol-level validation has succeeded.
     * A Socket.IO v3/v4 CONNECT (wire protocol v5) carrying authentication
     * data must not become visible to namespace listeners before that
     * authentication has been accepted.
     */
    public NamespaceClient addNamespaceClient(NamespaceClient client) {
        NamespaceClient existing = namespaceClients.putIfAbsent(client.getNamespace(), client);
        if (existing != null) {
            return existing;
        }
        client.getNamespace().addClient(client);
        return client;
    }

    public Set<Namespace> getNamespaces() {
        return namespaceClients.keySet();
    }

    public boolean isConnected() {
        return !disconnected.get();
    }

    private final List<PollFlushedListener> pollFlushedListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong pollFlushTimeoutSequence = new AtomicLong();

    public boolean hasPollFlushedListeners() {
        return !pollFlushedListeners.isEmpty();
    }

    public void onPollFlushed(Runnable listener, long gracePeriodMs) {
        if (!isConnected()) {
            listener.run();
            return;
        }

        SchedulerKey timeoutKey = null;
        if (gracePeriodMs > 0 && scheduler != null) {
            timeoutKey = new SchedulerKey(SchedulerKey.Type.POLL_FLUSH_TIMEOUT,
                    sessionId.toString() + ":" + pollFlushTimeoutSequence.incrementAndGet());
        }
        PollFlushedListener pollFlushedListener = new PollFlushedListener(listener, timeoutKey);
        pollFlushedListeners.add(pollFlushedListener);

        if (timeoutKey != null) {
            scheduler.schedule(timeoutKey, () -> {
                if (pollFlushedListeners.remove(pollFlushedListener)) {
                    log.debug("Polling disconnect grace period expired for session {}, executing deferred cleanup", sessionId);
                    listener.run();
                }
            }, gracePeriodMs, TimeUnit.MILLISECONDS);
        }
    }

    public void notifyPollFlushed() {
        if (!pollFlushedListeners.isEmpty()) {
            List<PollFlushedListener> listeners = new ArrayList<>(pollFlushedListeners);
            for (PollFlushedListener pollFlushedListener : listeners) {
                if (!pollFlushedListeners.remove(pollFlushedListener)) {
                    continue;
                }
                if (pollFlushedListener.timeoutKey != null && scheduler != null) {
                    scheduler.cancel(pollFlushedListener.timeoutKey);
                }
                try {
                    pollFlushedListener.listener.run();
                } catch (Exception e) {
                    log.error("Error executing poll flushed listener for session {}", sessionId, e);
                }
            }
        }
    }

    private static final class PollFlushedListener {
        private final Runnable listener;
        private final SchedulerKey timeoutKey;

        private PollFlushedListener(Runnable listener, SchedulerKey timeoutKey) {
            this.listener = listener;
            this.timeoutKey = timeoutKey;
        }
    }

    public void onChannelDisconnect() {
        if (!disconnected.compareAndSet(false, true)) {
            return;
        }
        cleanupDisconnectedSession();
    }

    private void cleanupDisconnectedSession() {
        for (Transport transport : Transport.values()) {
            TransportState state = channels.get(transport);
            Channel channel = state.getChannel();
            if (channel != null && state.compareAndSet(channel, null)) {
                clientsBox.remove(channel);
            }
        }

        notifyPollFlushed();
        cancelPing();
        cancelPingTimeout();
        clearPendingBinaryPacket();

        for (NamespaceClient client : new ArrayList<>(namespaceClients.values())) {
            client.onDisconnect();
        }
        // Namespace teardown and Engine.IO teardown are separate. Once the
        // transport closes, remove the head whether or not it had namespaces
        // when disconnect processing began.
        disconnectableHub.onDisconnect(this);
    }

    /**
     * Terminates an Engine.IO session because a Socket.IO protocol violation
     * occurred. A polling GET can bind in parallel with the POST that carried
     * the invalid packet, so queue a transport CLOSE before unregistering the
     * session. This guarantees that such a poll is completed rather than
     * remaining open after the session has been removed.
     */
    public void disconnectWithProtocolClose() {
        if (!disconnected.compareAndSet(false, true)) {
            return;
        }

        Transport closeTransport = currentTransport;
        TransportState state = channels.get(closeTransport);
        state.getPacketsQueue().add(new Packet(PacketType.CLOSE));
        Channel closeChannel = state.getChannel();
        ChannelFuture future = null;
        if (closeChannel != null
                && (closeTransport != Transport.POLLING
                        || closeChannel.attr(EncoderHandler.WRITE_ONCE).get() == null)) {
            future = sendPackets(closeTransport, closeChannel);
        }
        cleanupDisconnectedSession();

        if (future != null) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    public void releaseTransport(Transport transport, Channel channel) {
        if (channels.get(transport).compareAndSet(channel, null)) {
            clientsBox.remove(channel);
        }
    }

    public HandshakeData getHandshakeData() {
        return handshakeData;
    }

    public AckManager getAckManager() {
        return ackManager;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public SocketAddress getRemoteAddress() {
        return handshakeData.getAddress();
    }

    public void disconnect() {
        if (!disconnected.compareAndSet(false, true)) {
            return;
        }
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.DISCONNECT);
        ChannelFuture future = send(packet);
        if (future != null) {
            future.addListener(ChannelFutureListener.CLOSE);
        }

        cleanupDisconnectedSession();
    }

    public boolean isChannelOpen() {
        for (TransportState state : channels.values()) {
            Channel channel = state.getChannel();
            if (channel != null && channel.isActive()) {
                return true;
            }
        }
        return false;
    }

    public Store getStore() {
        return store;
    }

    public boolean isTransportChannel(Channel channel, Transport transport) {
        Channel current = channels.get(transport).getChannel();
        return current != null && current.equals(channel);
    }

    public void beginUpgrade() {
        upgradeInProgress.set(true);
    }

    public boolean isUpgradeInProgress() {
        return upgradeInProgress.get();
    }

    public void upgradeCurrentTransport(Transport currentTransport) {
        upgradeInProgress.set(false);
        TransportState state = channels.get(currentTransport);
        for (Entry<Transport, TransportState> entry : channels.entrySet()) {
            if (!entry.getKey().equals(currentTransport)) {
                Queue<Packet> queue = entry.getValue().getPacketsQueue();
                // NOOP only releases the old polling transport. Once the client
                // has selected the new transport it must not be replayed over it.
                queue.removeIf(packet -> packet.getType() == PacketType.NOOP);
                state.setPacketsQueue(queue);
                this.currentTransport = currentTransport;
                log.debug("Transport upgraded to: {} for: {}", currentTransport, sessionId);
                break;
            }
        }
        Channel channel = state.getChannel();
        if (channel != null) {
            sendPackets(currentTransport, channel);
        }
    }

    public Transport getCurrentTransport() {
        return currentTransport;
    }

    public Queue<Packet> getPacketsQueue(Transport transport) {
        return channels.get(transport).getPacketsQueue();
    }


    public Packet getLastBinaryPacket() {
        return lastBinaryPacket;
    }

    public ByteBuf getLastBinaryPacketSource() {
        return lastBinaryPacketSource;
    }

    public void setPendingBinaryPacket(@NotNull Packet packet, @NotNull ByteBuf source) {
        if (this.lastBinaryPacketSource != null && this.lastBinaryPacketSource != source) {
            this.lastBinaryPacketSource.release();
        }
        this.lastBinaryPacket = packet;
        this.lastBinaryPacketSource = source;
    }
    public void clearPendingBinaryPacket() {
        this.lastBinaryPacket = null;
        if (lastBinaryPacketSource != null) {
            lastBinaryPacketSource.release();
            lastBinaryPacketSource = null;
        }
    }

    public EngineIOVersion getEngineIOVersion() {
        return engineIOVersion;
    }

    /**
     * Returns true if and only if the I/O thread will perform the requested write operation immediately.
     * Any write requests made when this method returns false are queued until the I/O thread is ready to process the queued write requests.
     * @return
     */
    public boolean isWritable() {
        TransportState state = channels.get(getCurrentTransport());
        Channel channel = state.getChannel();
        return channel != null && channel.isWritable();
    }
}
