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

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.store.event.*;

public class RedissonEventStore implements EventStore {

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;
    private final Long nodeId;

    private final ConcurrentMap<String, Queue<Integer>> map = new ConcurrentHashMap<>();

    public RedissonEventStore(RedissonClient redissonPub, RedissonClient redissonSub, Long nodeId) {
        this.redissonPub = redissonPub;
        this.redissonSub = redissonSub;
        this.nodeId = nodeId;
    }

    @Override
    public void publish(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);
        redissonPub.getTopic(type.toString()).publish(msg);
    }

    @Override
    public <T extends EventMessage> void subscribe(EventType type, final EventListener<T> listener, Class<T> clazz) {
        String name = type.toString();
        RTopic topic = redissonSub.getTopic(name);
        int regId = topic.addListener(EventMessage.class, (channel, msg) -> {
            if (!nodeId.equals(msg.getNodeId())) {
                listener.onMessage((T) msg);
            }
        });

        Queue<Integer> list = map.get(name);
        if (list == null) {
            list = new ConcurrentLinkedQueue<>();
            Queue<Integer> oldList = map.putIfAbsent(name, list);
            if (oldList != null) {
                list = oldList;
            }
        }
        list.add(regId);
    }

    @Override
    public void unsubscribe(EventType type) {
        String name = type.toString();
        Queue<Integer> regIds = map.remove(name);
        RTopic topic = redissonSub.getTopic(name);
        for (Integer id : regIds) {
            topic.removeListener(id);
        }
    }

    @Override
    public void shutdown() {
    }

}
