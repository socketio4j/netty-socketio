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
package com.socketio4j.socketio.store.pubsub;

import java.util.List;
import java.util.Set;

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

        List<PubSubType> enabledTypes = pubSubStore().getEnabledTypes();

        if (pubSubStore().getMode().equals(PubSubStoreMode.MULTI_CHANNEL)) {
           handleMultiChannelSubscribe(namespacesHub, authorizeHandler, enabledTypes);
        } else if (pubSubStore().getMode().equals(PubSubStoreMode.SINGLE_CHANNEL)) {
           handleSingleChannelSubscribe(namespacesHub, authorizeHandler, enabledTypes);
        }

    }

    private void handleSingleChannelSubscribe(
            final NamespacesHub hub,
            final AuthorizeHandler auth,
            final List<PubSubType> enabled
    ) {

        pubSubStore().subscribe(PubSubType.ALL, msg -> {

            if (msg instanceof ConnectMessage && enabled.contains(PubSubType.CONNECT)) {
                ConnectMessage m = (ConnectMessage) msg;
                auth.connect(m.getSessionId());
                log.debug("[PUBSUB] CONNECT {}", m.getSessionId());
            } else if (msg instanceof DisconnectMessage && enabled.contains(PubSubType.DISCONNECT)) {
                DisconnectMessage m = (DisconnectMessage) msg;
                log.debug("[PUBSUB] DISCONNECT {}", m.getSessionId());
            } else if (msg instanceof JoinMessage && enabled.contains(PubSubType.JOIN)) {
                JoinMessage m = (JoinMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    n.join(m.getRoom(), m.getSessionId());
                }
                log.debug("[PUBSUB] JOIN {}", m.getSessionId());
            } else if (msg instanceof LeaveMessage && enabled.contains(PubSubType.LEAVE)) {
                LeaveMessage m = (LeaveMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    n.leave(m.getRoom(), m.getSessionId());
                }
                log.debug("[PUBSUB] LEAVE {}", m.getSessionId());
            } else if (msg instanceof DispatchMessage && enabled.contains(PubSubType.DISPATCH)) {
                DispatchMessage m = (DispatchMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    n.dispatch(m.getRoom(), m.getPacket());
                }
                log.debug("[PUBSUB] DISPATCH {}", m.getPacket());
            } else if (msg instanceof BulkJoinMessage && enabled.contains(PubSubType.BULK_JOIN)) {
                BulkJoinMessage m = (BulkJoinMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    for (String r : m.getRooms()) {
                        n.join(r, m.getSessionId());
                    }
                }
                log.debug("[PUBSUB] BULK_JOIN {}", m.getSessionId());
            } else if (msg instanceof BulkLeaveMessage && enabled.contains(PubSubType.BULK_LEAVE)) {
                BulkLeaveMessage m = (BulkLeaveMessage) msg;
                Namespace n = hub.get(m.getNamespace());
                if (n != null) {
                    for (String r : m.getRooms()) {
                        n.leave(r, m.getSessionId());
                    }
                }
                log.debug("[PUBSUB] BULK_LEAVE {}", m.getSessionId());
            }
        }, PubSubMessage.class);
    }


    private <T extends PubSubMessage> void subscribeIfEnabled(
            List<PubSubType> enabled,
            PubSubType type,
            Class<T> clazz,
            PubSubListener<T> listener
    ) {
        if (enabled.contains(type)) {
            pubSubStore().subscribe(type, listener, clazz);
        }
    }

    private void handleMultiChannelSubscribe(
            final NamespacesHub hub,
            final AuthorizeHandler auth,
            final List<PubSubType> enabled
    ) {

        subscribeIfEnabled(enabled, PubSubType.DISCONNECT, DisconnectMessage.class,
                m -> log.debug("[PUBSUB] DISCONNECT {}", m.getSessionId()));

        subscribeIfEnabled(enabled, PubSubType.CONNECT, ConnectMessage.class,
                m -> {
                    auth.connect(m.getSessionId());
                    log.debug("[PUBSUB] CONNECT {}", m.getSessionId());
                });

        subscribeIfEnabled(enabled, PubSubType.DISPATCH, DispatchMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        n.dispatch(m.getRoom(), m.getPacket());
                    }
                    log.debug("[PUBSUB] DISPATCH {}", m.getPacket());
                });

        subscribeIfEnabled(enabled, PubSubType.JOIN, JoinMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        n.join(m.getRoom(), m.getSessionId());
                    }
                    log.debug("[PUBSUB] JOIN {}", m.getSessionId());
                });

        subscribeIfEnabled(enabled, PubSubType.BULK_JOIN, BulkJoinMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        for (String r : m.getRooms()) {
                            n.join(r, m.getSessionId());
                        }
                    }
                    log.debug("[PUBSUB] BULK_JOIN {}", m.getSessionId());
                });

        subscribeIfEnabled(enabled, PubSubType.LEAVE, LeaveMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        n.leave(m.getRoom(), m.getSessionId());
                    }
                    log.debug("[PUBSUB] LEAVE {}", m.getSessionId());
                });

        subscribeIfEnabled(enabled, PubSubType.BULK_LEAVE, BulkLeaveMessage.class,
                m -> {
                    Namespace n = hub.get(m.getNamespace());
                    if (n != null) {
                        for (String r : m.getRooms()) {
                            n.leave(r, m.getSessionId());
                        }
                    }
                    log.debug("[PUBSUB] BULK_LEAVE {}", m.getSessionId());
                });
    }


    @Override
    public abstract PubSubStore pubSubStore();

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
