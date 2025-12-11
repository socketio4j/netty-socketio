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
package com.socketio4j.socketio;


import com.socketio4j.socketio.store.redis_stream.RedisStreamsStoreFactory;
import org.redisson.config.Config;


public class DevMain {
    public static void main ( String[] args ) {
        Configuration configuration = new Configuration();
        configuration.setHostname("127.0.0.1");
        configuration.setPort(9099);
        configuration.setBossThreads(1);
        configuration.setWorkerThreads(1);
        configuration.setWebsocketCompression(false);
        configuration.setHttpCompression(false);
        Config c = new Config();
        c.useSingleServer().setAddress("redis://127.0.0.1:6379");
        configuration.setStoreFactory(new RedisStreamsStoreFactory());
        SocketIOServer socketIOServer = new SocketIOServer(configuration);
        socketIOServer.start();
        socketIOServer.addConnectListener(client ->
        { System.out.println(client.getSessionId());
            System.out.println("rooms:"+client.getAllRooms());
        socketIOServer.getBroadcastOperations().sendEvent("c", client.getSessionId());
        });

        Configuration configuration1 = new Configuration();
        configuration1.setHostname("127.0.0.1");
        configuration1.setPort(9100);
        configuration1.setWorkerThreads(1);
        configuration1.setBossThreads(1);
        configuration1.setWebsocketCompression(false);
        configuration1.setHttpCompression(false);
        Config c1 = new Config();
        c1.useSingleServer().setAddress("redis://127.0.0.1:6379");
        configuration1.setStoreFactory(new RedisStreamsStoreFactory());
        SocketIOServer socketIOServer1 = new SocketIOServer(configuration1);
        socketIOServer1.start();
        socketIOServer1.addConnectListener(client ->
        { System.out.println(client.getSessionId());
            System.out.println("rooms:"+client.getAllRooms());
            socketIOServer1.getBroadcastOperations().sendEvent("c", client.getSessionId());
        });
    }
}
