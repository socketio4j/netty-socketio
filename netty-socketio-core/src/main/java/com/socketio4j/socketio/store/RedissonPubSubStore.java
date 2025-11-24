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

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import com.socketio4j.socketio.store.pubsub.*;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedissonPubSubStore implements PubSubStore {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;
    private final Long nodeId;

    private final AtomicReference<Integer> map = new AtomicReference<>();

    public RedissonPubSubStore(RedissonClient redissonPub, RedissonClient redissonSub, Long nodeId) {
        this.redissonPub = redissonPub;
        this.redissonSub = redissonSub;
        this.nodeId = nodeId;
    }

    @Override
    public void publish(PubSubMessage msg) {
        msg.setNodeId(nodeId);
        redissonPub.getTopic(PubSubConstants.TOPIC_NAME).publish(msg);
    }

    @Override
    public void subscribe(final PubSubListener<PubSubMessage> listener) {
        String name = PubSubConstants.TOPIC_NAME;
        RTopic topic = redissonSub.getTopic(name);
        int regId = topic.addListener(PubSubMessage.class, (channel, msg) -> {
            if (!nodeId.equals(msg.getNodeId())) {
                listener.onMessage(msg);
            }
        });
map.set(regId);

    }

    @Override
    public void unsubscribe() {
        String name = PubSubConstants.TOPIC_NAME;
        RTopic topic = redissonSub.getTopic(name);
        topic.removeListener(map.get());
        map.set(null);
    }

    @Override
    public void shutdown() {
    }

}
