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


import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import com.socketio4j.socketio.store.pubsub.*;


public class HazelcastPubSubStore implements PubSubStore {

    private final HazelcastInstance hazelcastPub;
    private final HazelcastInstance hazelcastSub;
    private final Long nodeId;

    private final AtomicReference<UUID> map = new AtomicReference<>();

    public HazelcastPubSubStore(HazelcastInstance hazelcastPub, HazelcastInstance hazelcastSub, Long nodeId) {
        this.hazelcastPub = hazelcastPub;
        this.hazelcastSub = hazelcastSub;
        this.nodeId = nodeId;
    }

    @Override
    public void publish(PubSubMessage msg) {
        msg.setNodeId(nodeId);
        hazelcastPub.getTopic(PubSubConstants.TOPIC_NAME).publish(msg);
    }

    @Override
    public void subscribe(final PubSubListener<PubSubMessage> listener) {
        String name = PubSubConstants.TOPIC_NAME;
        ITopic<PubSubMessage> topic = hazelcastSub.getTopic(name);
        UUID regId = topic.addMessageListener(message -> {
            PubSubMessage msg = message.getMessageObject();
            if (!nodeId.equals(msg.getNodeId())) {
                listener.onMessage(message.getMessageObject());
            }
        });
        map.set(regId);
    }

    @Override
    public void unsubscribe() {
        String name = PubSubConstants.TOPIC_NAME;

        ITopic<Object> topic = hazelcastSub.getTopic(name);
        topic.removeMessageListener(map.get());
        map.set(null);
    }

    @Override
    public void shutdown() {
    }

}
