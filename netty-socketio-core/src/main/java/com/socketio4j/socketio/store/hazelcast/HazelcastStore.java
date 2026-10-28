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
package com.socketio4j.socketio.store.hazelcast;

import java.util.Objects;
import java.util.UUID;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.socketio4j.socketio.store.Store;

public class HazelcastStore implements Store {

    private final IMap<String, Object> map;

    public HazelcastStore(UUID sessionId, HazelcastInstance hazelcastInstance) {
        this(sessionId, hazelcastInstance, "");
    }

    public HazelcastStore(UUID sessionId, HazelcastInstance hazelcastInstance, String keyPrefix) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(hazelcastInstance, "hazelcastInstance cannot be null");
        String mapName = sessionId.toString();
        if (keyPrefix != null && !keyPrefix.isEmpty()) {
            mapName = keyPrefix + sessionId.toString();
        }
        this.map = hazelcastInstance.getMap(mapName);
    }

    @Override
    public void set(String key, Object val) {
        if (key == null) {
            return;
        }
        if (val != null) {
            map.put(key, val);
        } else {
            map.delete(key);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        if (key == null) {
            return null;
        }
        return (T) map.get(key);
    }

    @Override
    public boolean has(String key) {
        if (key == null) {
            return false;
        }
        return map.containsKey(key);
    }

    @Override
    public void del(String key) {
        if (key == null) {
            return;
        }
        map.delete(key);
    }

    @Override
    public void destroy() {
        map.destroy();
    }

}
