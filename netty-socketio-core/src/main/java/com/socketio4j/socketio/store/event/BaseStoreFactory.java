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
package com.socketio4j.socketio.store.event;

import java.util.ArrayList;
import java.util.List;

import com.socketio4j.socketio.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.handler.AuthorizeHandler;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.namespace.Namespace;
import com.socketio4j.socketio.namespace.NamespacesHub;
import com.socketio4j.socketio.protocol.JsonSupport;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.StoreFactory;

import io.netty.util.internal.ObjectUtil;



public abstract class BaseStoreFactory implements StoreFactory {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private Long nodeId = (long) (Math.random() * 1000000);

    protected Long getNodeId() {
        return nodeId;
    }

    @Override
    public void init(final NamespacesHub namespacesHub, final AuthorizeHandler authorizeHandler, JsonSupport jsonSupport) {
        ObjectUtil.checkNotNull(pubSubStore().getMode(), "mode");

        List<EventType> enabledTypes = pubSubStore().getEnabledTypes();

        if (pubSubStore().getMode().equals(EventStoreMode.MULTI_CHANNEL)) {
           handleMultiChannelSubscribe(namespacesHub, authorizeHandler, enabledTypes);
        } else if (pubSubStore().getMode().equals(EventStoreMode.SINGLE_CHANNEL)) {
           handleSingleChannelSubscribe(namespacesHub, authorizeHandler, enabledTypes);
        }

    }

    private void handleSingleChannelSubscribe(
            final NamespacesHub hub,
            final AuthorizeHandler auth,
            final List<EventType> enabled
    ) {

        pubSubStore().subscribe(EventType.ALL_SINGLE_CHANNEL, msg -> {

            if (msg instanceof ConnectMessage && enabled.contains(EventType.CONNECT)) {
                ConnectMessage m = (ConnectMessage) msg;
                auth.connect(m.getSessionId());
                log.debug("[PUBSUB-SC] CONNECT {}", m.getSessionId());
            } else if (msg instanceof DisconnectMessage && enabled.contains(EventType.DISCONNECT)) {
                DisconnectMessage m = (DisconnectMessage) msg;
                log.debug("[PUBSUB-SC] DISCONNECT {}", m.getSessionId());
            } else if (msg instanceof JoinMessage && enabled.contains(EventType.JOIN)) {
                JoinMessage m = (JoinMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    n.join(m.getRoom(), m.getSessionId());
                }
                log.debug("[PUBSUB-SC] JOIN {}", m.getSessionId());
            } else if (msg instanceof LeaveMessage && enabled.contains(EventType.LEAVE)) {
                LeaveMessage m = (LeaveMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    n.leave(m.getRoom(), m.getSessionId());
                }
                log.debug("[PUBSUB-SC] LEAVE {}", m.getSessionId());
            } else if (msg instanceof DispatchMessage && enabled.contains(EventType.DISPATCH)) {
                DispatchMessage m = (DispatchMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (msg.getOffset() != null && !msg.getOffset().isEmpty()) {
                    attachOffset(m.getPacket(), m.getOffset());
                }
                if (n != null) {
                    n.dispatch(m.getRoom(), m.getPacket());
                }
                log.debug("[PUBSUB-SC] DISPATCH {}", m.getPacket());
            } else if (msg instanceof BulkJoinMessage && enabled.contains(EventType.BULK_JOIN)) {
                BulkJoinMessage m = (BulkJoinMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    for (String r : m.getRooms()) {
                        n.join(r, m.getSessionId());
                    }
                }
                log.debug("[PUBSUB-SC] BULK_JOIN {}", m.getSessionId());
            } else if (msg instanceof BulkLeaveMessage && enabled.contains(EventType.BULK_LEAVE)) {
                BulkLeaveMessage m = (BulkLeaveMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    for (String r : m.getRooms()) {
                        n.leave(r, m.getSessionId());
                    }
                }
                log.debug("[PUBSUB-SC] BULK_LEAVE {}", m.getSessionId());
            }
        }, EventMessage.class);
    }

    private void attachOffset(Packet packet, String offset) {
        List<Object> args = packet.getData();
        if (args == null) {
            args = new ArrayList<>();
        } else {
            args = new ArrayList<>(args);
        }
        // avoid duplicate append if already present (extra safety)
        if (args.isEmpty() || !offset.equals(args.get(args.size() - 1))) {
            args.add(offset);
        }
        packet.setData(args);
    }





    private <T extends EventMessage> void subscribeIfEnabled(
            List<EventType> enabled,
            EventType type,
            Class<T> clazz,
            EventListener<T> listener
    ) {
        if (enabled.contains(type)) {
            pubSubStore().subscribe(type, listener, clazz);
        }
    }

    private void handleMultiChannelSubscribe(
            final NamespacesHub hub,
            final AuthorizeHandler auth,
            final List<EventType> enabled
    ) {

        subscribeIfEnabled(enabled, EventType.DISCONNECT, DisconnectMessage.class,
                m -> log.debug("[PUBSUB-MC] DISCONNECT {}", m.getSessionId()));

        subscribeIfEnabled(enabled, EventType.CONNECT, ConnectMessage.class,
                m -> {
                    auth.connect(m.getSessionId());
                    log.debug("[PUBSUB-MC] CONNECT {}", m.getSessionId());
                });

        subscribeIfEnabled(enabled, EventType.DISPATCH, DispatchMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        n.dispatch(m.getRoom(), m.getPacket());
                    }
                    log.debug("[PUBSUB-MC] DISPATCH {}", m.getPacket());
                });

        subscribeIfEnabled(enabled, EventType.JOIN, JoinMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        n.join(m.getRoom(), m.getSessionId());
                    }
                    log.debug("[PUBSUB-MC] JOIN {}", m.getSessionId());
                });

        subscribeIfEnabled(enabled, EventType.BULK_JOIN, BulkJoinMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        for (String r : m.getRooms()) {
                            n.join(r, m.getSessionId());
                        }
                    }
                    log.debug("[PUBSUB-MC] BULK_JOIN {}", m.getSessionId());
                });

        subscribeIfEnabled(enabled, EventType.LEAVE, LeaveMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        n.leave(m.getRoom(), m.getSessionId());
                    }
                    log.debug("[PUBSUB-MC] LEAVE {}", m.getSessionId());
                });

        subscribeIfEnabled(enabled, EventType.BULK_LEAVE, BulkLeaveMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        for (String r : m.getRooms()) {
                            n.leave(r, m.getSessionId());
                        }
                    }
                    log.debug("[PUBSUB-MC] BULK_LEAVE {}", m.getSessionId());
                });
    }


    @Override
    public abstract EventStore pubSubStore();

    /**
     * Handles client disconnection by destroying the associated store.
     * <p>
     * This method retrieves the store from the client and calls its destroy()
     * method to clean up all stored data. The implementation is common for all
     * store factory types, as the actual cleanup logic is encapsulated within
     * each Store implementation.
     * </p>
     *
     * @param client the client that is disconnecting
     */
    @Override
    public void onDisconnect(ClientHead client) {
        Store store = client.getStore();
        if (store != null) {
            try {
                store.destroy();
                log.debug("Destroyed store for sessionId: {}", client.getSessionId());
            } catch (Exception e) {
                log.warn("Failed to destroy store for sessionId: {}", client.getSessionId(), e);
            }
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " (distributed session store, distributed publish/subscribe)";
    }

}
