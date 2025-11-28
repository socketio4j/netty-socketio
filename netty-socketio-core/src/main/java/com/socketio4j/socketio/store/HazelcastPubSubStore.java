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
package com.socketio4j.socketio.store;

import java.util.Arrays;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import com.socketio4j.socketio.store.pubsub.PubSubListener;
import com.socketio4j.socketio.store.pubsub.PubSubMessage;
import com.socketio4j.socketio.store.pubsub.PubSubStore;
import com.socketio4j.socketio.store.pubsub.PubSubType;


public class HazelcastPubSubStore implements PubSubStore {

    private final HazelcastInstance hazelcastPub;
    private final HazelcastInstance hazelcastSub;
    private final Long nodeId;

    private final ConcurrentMap<String, Queue<UUID>> map = new ConcurrentHashMap<>();

    public HazelcastPubSubStore(HazelcastInstance hazelcastPub, HazelcastInstance hazelcastSub, Long nodeId) {
        this.hazelcastPub = hazelcastPub;
        this.hazelcastSub = hazelcastSub;
        this.nodeId = nodeId;
    }

    @Override
    public void publish(PubSubType type, PubSubMessage msg) {
        msg.setNodeId(nodeId);
        hazelcastPub.getTopic(type.toString()).publish(msg);
    }

    @Override
    public <T extends PubSubMessage> void subscribe(PubSubType type, final PubSubListener<T> listener, Class<T> clazz) {

        ITopic<T> topic = hazelcastSub.getTopic(type.toString());

        UUID regId = topic.addMessageListener(msg -> {
            if (!nodeId.equals(msg.getMessageObject().getNodeId())) {
                listener.onMessage(msg.getMessageObject());
            }
        });

        map.computeIfAbsent(type.toString(), k -> new ConcurrentLinkedQueue<>())
                .add(regId);
    }

    @Override
    public void unsubscribe(PubSubType type) {
        String name = type.toString();
        Queue<UUID> regIds = map.remove(name);
        if (regIds == null || regIds.isEmpty()) {
            return;
        }
        ITopic<Object> topic = hazelcastSub.getTopic(name);
        for (UUID id : regIds) {
            topic.removeMessageListener(id);
        }
    }

    @Override
    public void shutdown() {
        Arrays.stream(PubSubType.values()).forEach(this::unsubscribe);
        map.clear();
        //do not shut down client here
    }

}
