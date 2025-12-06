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
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventType;

public class RedissonEventStore implements EventStore {

    private final RedissonClient redissonPub;
    private final RedissonClient redissonSub;
    private final Long nodeId;

    private final ConcurrentMap<String, Queue<Integer>> map = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(RedissonEventStore.class);

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------

    public RedissonEventStore(RedissonClient redisson, Long nodeId) {
        Objects.requireNonNull(redisson, "redisson is null");

        this.redissonPub = redisson;
        this.redissonSub = redisson;
        this.nodeId = nodeId;
    }

    public RedissonEventStore(RedissonClient redissonPub, RedissonClient redissonSub, Long nodeId) {
        Objects.requireNonNull(redissonPub, "redissonPub is null");
        Objects.requireNonNull(redissonSub, "redissonSub is null");
        Objects.requireNonNull(nodeId, "nodeId is null");
        this.redissonPub = redissonPub;
        this.redissonSub = redissonSub;
        this.nodeId = nodeId;
    }

    public RedissonEventStore(RedissonClient redissonPub, RedissonClient redissonSub) {
        Objects.requireNonNull(redissonPub, "redissonPub is null");
        Objects.requireNonNull(redissonSub, "redissonSub is null");

        this.redissonPub = redissonPub;
        this.redissonSub = redissonSub;
        this.nodeId = getNodeId();
    }


    @Override
    public void publish(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);
        redissonPub.getTopic(type.toString()).publish(msg);
    }

    @Override
    public <T extends EventMessage> void subscribe(EventType type, final EventListener<T> listener, Class<T> clazz) {
        RTopic topic = redissonSub.getTopic(type.toString());

        int regId = topic.addListener(clazz, (channel, msg) -> {
            if (!nodeId.equals(msg.getNodeId())) {
                listener.onMessage(msg);
            }
        });

        map.computeIfAbsent(type.toString(), k -> new ConcurrentLinkedQueue<>()).add(regId);
    }

    @Override
    public void unsubscribe(EventType type) {
        String name = type.toString();

        Queue<Integer> regIds = map.remove(name);
        if (regIds == null || regIds.isEmpty()) {
            return;
        }

        RTopic topic = redissonSub.getTopic(name);
        if (topic == null) {
            return;
        }

        for (Integer id : regIds) {
            try {
                topic.removeListener(id);
            } catch (Exception ex) {
                log.warn("Failed to remove listener {} from topic {}", id, name, ex);
            }
        }
    }

    @Override
    public void shutdown() {
        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
        map.clear();
    }
}
