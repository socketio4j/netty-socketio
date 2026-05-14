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
package com.socketio4j.socketio.integration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.redisson.config.Config;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOServer;

/**
 * Shared helpers for multi-node integration tests (socket bind options, Redisson URL config,
 * identical room/join listeners on each node).
 */
final class DistributedClusterIntegrationSupport {

    private DistributedClusterIntegrationSupport() {
    }

    static void applyReuseListenAddress(Configuration configuration) {
        configuration.getSocketConfig().setReuseAddress(true);
    }

    static Config redisConfig(String url) {
        Config c = new Config();
        c.useSingleServer().setAddress(url);
        return c;
    }

    static void attachDefaultRoomListeners(SocketIOServer node) {
        node.addEventListener("join-room", String.class, (c, room, ack) -> {
            c.joinRoom(room);
            c.sendEvent("join-ok", "OK");
        });
        node.addEventListener("leave-room", String.class, (c, room, ack) -> {
            c.leaveRoom(room);
            c.sendEvent("leave-ok", "OK");
        });
        node.addEventListener("get-my-rooms", String.class, (client, data, ackSender) -> {
            if (ackSender.isAckRequested()) {
                ackSender.sendAckData(client.getAllRooms());
            }
        });
        node.addConnectListener(client -> {
            Map<String, List<String>> params = client.getHandshakeData().getUrlParams();
            List<String> joinParams = params.get("join");
            if (joinParams == null || joinParams.isEmpty()) {
                return;
            }
            Set<String> rooms = joinParams.stream()
                    .flatMap(v -> Arrays.stream(v.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            rooms.forEach(client::joinRoom);
        });
    }
}
