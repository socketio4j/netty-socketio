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

public abstract class BaseStoreFactory implements StoreFactory {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private Long nodeId = (long) (Math.random() * 1000000);

    protected Long getNodeId() {
        return nodeId;
    }

    @Override
    public void init(final NamespacesHub namespacesHub,
                     final AuthorizeHandler authorizeHandler,
                     JsonSupport jsonSupport) {

        pubSubStore().subscribe(message -> {
            log.debug("Received message : {}", message);
            switch (message.getType()) {
                case CONNECT:
                    ConnectMessage connectMessage = (ConnectMessage) message;
                    authorizeHandler.connect(connectMessage.getSessionId());
                    log.debug("[PUBSUB] CONNECT sessionId={}", connectMessage.getSessionId());
                    break;
                case JOIN:
                    JoinLeaveMessage joinMessage = (JoinLeaveMessage) message;
                    Namespace n = namespacesHub.get(joinMessage.getNamespace());
                    if (n != null) {
                        n.join(joinMessage.getRoom(), joinMessage.getSessionId());
                    }
                    log.debug("[PUBSUB] JOIN room={} sessionId={}", joinMessage.getRoom(), joinMessage.getSessionId());
                    break;
                case LEAVE:
                    JoinLeaveMessage leaveMessage = (JoinLeaveMessage) message;
                    Namespace n1 = namespacesHub.get(leaveMessage.getNamespace());
                    if (n1 != null) {
                        n1.leave(leaveMessage.getRoom(), leaveMessage.getSessionId());
                    }
                    log.debug("[PUBSUB] LEAVE room={} sessionId={}", leaveMessage.getRoom(), leaveMessage.getSessionId());
                    break;
                case DISPATCH:
                    DispatchMessage dispatchMessage = (DispatchMessage) message;
                    Namespace n2 = namespacesHub.get(dispatchMessage.getNamespace());
                    if (n2 != null) {
                        n2.dispatch(dispatchMessage.getRoom(), dispatchMessage.getPacket());
                    }
                    log.debug("[PUBSUB] DISPATCH packet={} namespace={}", dispatchMessage.getPacket(), dispatchMessage.getNamespace());
                    break;
                case BULK_JOIN:
                    BulkJoinLeaveMessage bulkJoinMessage = (BulkJoinLeaveMessage) message;
                    Namespace n3 = namespacesHub.get(bulkJoinMessage.getNamespace());
                    if (n3 != null) {
                        for (String room : bulkJoinMessage.getRooms()) {
                            n3.join(room, bulkJoinMessage.getSessionId());
                        }
                    }
                    log.debug("[PUBSUB] BULK_JOIN rooms={} sessionId={}", bulkJoinMessage.getRooms(), bulkJoinMessage.getSessionId());
                    break;
                case BULK_LEAVE:
                    BulkJoinLeaveMessage bulkLeaveMessage = (BulkJoinLeaveMessage) message;
                    Namespace n4 = namespacesHub.get(bulkLeaveMessage.getNamespace());
                    if (n4 != null) {
                        for (String room : bulkLeaveMessage.getRooms()) {
                            n4.leave(room, bulkLeaveMessage.getSessionId());
                        }
                    }
                    log.debug("[PUBSUB] BULK_LEAVE rooms={} sessionId={}", bulkLeaveMessage.getRooms(), bulkLeaveMessage.getSessionId());

                    break;
                case DISCONNECT:
                    DisconnectMessage disconnectMessage = (DisconnectMessage) message;
                    log.debug("{} sessionId: {}", PubSubType.DISCONNECT, disconnectMessage.getSessionId());
                    break;
            }

        }, PubSubMessage.class);


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
