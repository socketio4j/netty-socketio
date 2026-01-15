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
package com.socketio4j.socketio.namespace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.AckMode;
import com.socketio4j.socketio.AckRequest;
import com.socketio4j.socketio.AuthTokenListener;
import com.socketio4j.socketio.AuthTokenResult;
import com.socketio4j.socketio.BroadcastOperations;
import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.MultiRoomBroadcastOperations;
import com.socketio4j.socketio.MultiTypeArgs;
import com.socketio4j.socketio.SingleRoomBroadcastOperations;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIONamespace;
import com.socketio4j.socketio.annotation.ScannerEngine;
import com.socketio4j.socketio.listener.CatchAllEventListener;
import com.socketio4j.socketio.listener.ConnectListener;
import com.socketio4j.socketio.listener.DataListener;
import com.socketio4j.socketio.listener.DisconnectListener;
import com.socketio4j.socketio.listener.EventInterceptor;
import com.socketio4j.socketio.listener.ExceptionListener;
import com.socketio4j.socketio.listener.MultiTypeEventListener;
import com.socketio4j.socketio.listener.PingListener;
import com.socketio4j.socketio.listener.PongListener;
import com.socketio4j.socketio.metrics.SocketIOMetrics;
import com.socketio4j.socketio.protocol.JsonSupport;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.store.StoreFactory;
import com.socketio4j.socketio.store.event.BulkJoinMessage;
import com.socketio4j.socketio.store.event.BulkLeaveMessage;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.JoinMessage;
import com.socketio4j.socketio.store.event.LeaveMessage;
import com.socketio4j.socketio.transport.NamespaceClient;


/**
 * Hub object for all clients in one namespace.
 * Namespace shares by different namespace-clients.
 *
 * @see com.socketio4j.socketio.transport.NamespaceClient
 */
public class Namespace implements SocketIONamespace {

    private static final Logger log = LoggerFactory.getLogger(Namespace.class);

    public static final String DEFAULT_NAME = "";

    private final ScannerEngine engine = new ScannerEngine();
    private final ConcurrentMap<String, EventEntry<?>> eventListeners = new ConcurrentHashMap<>();
    private final Queue<CatchAllEventListener> catchAllEventListeners = new ConcurrentLinkedQueue<>();
    private final Queue<ConnectListener> connectListeners = new ConcurrentLinkedQueue<>();
    private final Queue<DisconnectListener> disconnectListeners = new ConcurrentLinkedQueue<>();
    private final Queue<PingListener> pingListeners = new ConcurrentLinkedQueue<>();
    private final Queue<PongListener> pongListeners = new ConcurrentLinkedQueue<>();
    private final Queue<EventInterceptor> eventInterceptors = new ConcurrentLinkedQueue<>();

    private final Queue<AuthTokenListener> authDataInterceptors = new ConcurrentLinkedQueue<>();

    private final Map<UUID, SocketIOClient> allClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<UUID>> roomClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<String>> clientRooms = new ConcurrentHashMap<>();

    private final String name;
    private final AckMode ackMode;
    private final JsonSupport jsonSupport;
    private final StoreFactory storeFactory;
    private final ExceptionListener exceptionListener;

    private final SocketIOMetrics metrics;

    public Namespace(String name, Configuration configuration) {
        super();
        this.name = name;
        this.jsonSupport = configuration.getJsonSupport();
        this.storeFactory = configuration.getStoreFactory();
        this.exceptionListener = configuration.getExceptionListener();
        this.ackMode = configuration.getAckMode();
        if (configuration.isMetricsEnabled()) {
            this.metrics = Objects.requireNonNullElse(configuration.getMetrics(), SocketIOMetrics.noop());
        } else {
            this.metrics = SocketIOMetrics.noop();
        }

    }

    public void addClient(SocketIOClient client) {
        allClients.put(client.getSessionId(), client);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void addMultiTypeEventListener(String eventName, MultiTypeEventListener listener,
            Class<?>... eventClass) {
        EventEntry entry = eventListeners.get(eventName);
        if (entry == null) {
            entry = new EventEntry();
            EventEntry<?> oldEntry = eventListeners.putIfAbsent(eventName, entry);
            if (oldEntry != null) {
                entry = oldEntry;
            }
        }
        entry.addListener(listener);
        jsonSupport.addEventMapping(name, eventName, eventClass);
    }
    
    @Override
    public void removeAllListeners(String eventName) {
        EventEntry<?> entry = eventListeners.remove(eventName);
        if (entry != null) {
            jsonSupport.removeEventMapping(name, eventName);
        }
    }

    @Override
    public void addOnAnyEventListener(CatchAllEventListener listener) {
        catchAllEventListeners.add(listener);
    }

    @Override
    public void removeOnAnyEventListener(CatchAllEventListener listener) {
        catchAllEventListeners.remove(listener);
    }

    //alias of addOnAnyEventListener
    @Override
    public void onAny(CatchAllEventListener listener) {
        addOnAnyEventListener(listener);
    }

    //alias of removeOnAnyEventListener
    @Override
    public void offAny(CatchAllEventListener listener) {
        removeOnAnyEventListener(listener);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> void addEventListener(String eventName, Class<T> eventClass, DataListener<T> listener) {
        EventEntry entry = eventListeners.get(eventName);
        if (entry == null) {
            entry = new EventEntry<T>();
            EventEntry<?> oldEntry = eventListeners.putIfAbsent(eventName, entry);
            if (oldEntry != null) {
                entry = oldEntry;
            }
        }
        entry.addListener(listener);
        jsonSupport.addEventMapping(name, eventName, eventClass);
    }

    @Override
    public void addEventInterceptor(EventInterceptor eventInterceptor) {
        eventInterceptors.add(eventInterceptor);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void onEvent(NamespaceClient client, String eventName, List<Object> args, AckRequest ackRequest) {
        long start = System.nanoTime();
        EventEntry entry = eventListeners.get(eventName);
        metrics.eventReceived(name);
        try {
            if (entry != null) {
                
                Queue<DataListener> listeners = entry.getListeners();
                for (DataListener dataListener : listeners) {
                    Object data = getEventData(args, dataListener);
                    dataListener.onData(client, data, ackRequest);
                }
                for (EventInterceptor eventInterceptor : eventInterceptors) {
                    eventInterceptor.onEvent(client, eventName, args, ackRequest);
                }
                metrics.eventHandled(name, System.nanoTime() - start);
            } else {
                metrics.unknownEventReceived(name);
                metrics.unknownEventNames(name, eventName);
            }
            
            for (CatchAllEventListener catchAllEventListener : catchAllEventListeners) {
                catchAllEventListener.onEvent(client, eventName, args, ackRequest);
            }
        } catch (Exception e) {
            metrics.eventFailed(name);
            exceptionListener.onEventException(e, args, client);
            if (ackMode == AckMode.AUTO_SUCCESS_ONLY) {
                metrics.ackMissing(name);
                return;
            }
        }
        sendAck(ackRequest);
        if (ackRequest.isSent() && entry != null) {
            metrics.ackSent(name, System.nanoTime() - start);
        }

    }

    private void sendAck(AckRequest ackRequest) {
        if (ackMode == AckMode.AUTO || ackMode == AckMode.AUTO_SUCCESS_ONLY) {
            // send ack response if it did not execute
            // during {@link DataListener#onData} invocation
            ackRequest.sendAckData(Collections.emptyList());
            return;
        }
        if (ackMode == AckMode.MANUAL
                && ackRequest != null
                && !ackRequest.isSent()) {
            metrics.ackMissing(name);
        }
    }

    private Object getEventData(List<Object> args, DataListener<?> dataListener) {
        if (dataListener instanceof MultiTypeEventListener) {
            return new MultiTypeArgs(args);
        } else {
            if (!args.isEmpty()) {
                return args.get(0);
            }
        }
        return null;
    }

    @Override
    public void addDisconnectListener(DisconnectListener listener) {
        disconnectListeners.add(listener);
    }

    public void onDisconnect(SocketIOClient client) {
        log.debug("Client disconnected: {} from namespace: {}", client.getSessionId(), getName());
        Set<String> joinedRooms = client.getAllRooms();        
        allClients.remove(client.getSessionId());
        final Set<String> roomsToLeave = new HashSet<>(joinedRooms);

        // client must leave all rooms and publish the leave msg one by one on disconnect.
        for (String joinedRoom : joinedRooms) {
            leave(roomClients, joinedRoom, client.getSessionId());
        }
        clientRooms.remove(client.getSessionId());
        storeFactory.eventStore().publish(EventType.BULK_LEAVE, new BulkLeaveMessage(client.getSessionId(), roomsToLeave, getName()));

        try {
            for (DisconnectListener listener : disconnectListeners) {
                listener.onDisconnect(client);
            }
        } catch (Exception e) {
            exceptionListener.onDisconnectException(e, client);
        }
        metrics.disconnect(name);
    }

    @Override
    public void addConnectListener(ConnectListener listener) {
        connectListeners.add(listener);
    }

    public void onConnect(SocketIOClient client) {
        if (roomClients.containsKey(getName())
                && roomClients.get(getName()).contains(client.getSessionId())) {
            return;
        }

        join(getName(), client.getSessionId());
        storeFactory.eventStore().publish(EventType.JOIN, new JoinMessage(client.getSessionId(), getName(), getName()));

        try {
            for (ConnectListener listener : connectListeners) {
                listener.onConnect(client);
            }
        } catch (Exception e) {
            exceptionListener.onConnectException(e, client);
        }
        metrics.connect(name);
    }

    @Override
    public void addPingListener(PingListener listener) {
        pingListeners.add(listener);
    }

    @Override
    public void addPongListener(PongListener listener) {
        pongListeners.add(listener);
    }

    public void onPing(SocketIOClient client) {
        try {
            for (PingListener listener : pingListeners) {
                listener.onPing(client);
            }
        } catch (Exception e) {
            exceptionListener.onPingException(e, client);
        }
    }

    public void onPong(SocketIOClient client) {
        try {
            for (PongListener listener : pongListeners) {
                listener.onPong(client);
            }
        } catch (Exception e) {
            exceptionListener.onPingException(e, client);
        }
    }

    @Override
    public BroadcastOperations getBroadcastOperations() {
        return new SingleRoomBroadcastOperations(getName(), getName(), allClients.values(), storeFactory);
    }

    @Override
    public BroadcastOperations getRoomOperations(String room) {
        return new SingleRoomBroadcastOperations(getName(), room, getRoomClients(room), storeFactory);
    }

    @Override
    public BroadcastOperations getRoomOperations(String... rooms) {
        List<BroadcastOperations> list = new ArrayList<>();
        for (String room : rooms) {
            list.add(new SingleRoomBroadcastOperations(getName(), room, getRoomClients(room), storeFactory));
        }
        return new MultiRoomBroadcastOperations(list);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        if (name == null) {
            result = prime * result;
        } else {
            result = prime * result + name.hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Namespace other = (Namespace) obj;
        if (name == null) {
            return other.name == null;
        } else return name.equals(other.name);
    }

    @Override
    public void addListeners(Object listeners) {
        if (listeners instanceof Iterable) {
            addListeners((Iterable<?>) listeners);
            return;
        }
        addListeners(listeners, listeners.getClass());
    }

    @Override
    public <L> void addListeners(Iterable<L> listeners) {
        for (L next : listeners) {
            addListeners(next, next.getClass());
        }
    }

    @Override
    public void addListeners(Object listeners, Class<?> listenersClass) {
        if (listeners instanceof Iterable) {
            addListeners((Iterable<?>) listeners);
            return;
        }
        engine.scan(this, listeners, listenersClass);
    }

    public void joinRoom(String room, UUID sessionId) {
        join(room, sessionId);
        storeFactory.eventStore().publish(EventType.JOIN, new JoinMessage(sessionId, room, getName()));
    }

    public void joinRooms(Set<String> rooms, final UUID sessionId) {
        for (String room : rooms) {
            join(room, sessionId);
        }
        storeFactory.eventStore().publish(EventType.BULK_JOIN, new BulkJoinMessage(sessionId, rooms, getName()));
    }

    public void dispatch(String room, Packet packet) {
        int size = forEachRoomClient(room, client -> {
            client.send(packet);
        });

        if (size > 0) {
            metrics.eventSent(name, size);
        }
    }

    public int forEachRoomClient(String room, Consumer<SocketIOClient> action) {
        Objects.requireNonNull(action, "action must not be null");
        Set<UUID> sessionIds = roomClients.get(room);

        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (UUID sessionId : sessionIds) {
            SocketIOClient client = allClients.get(sessionId);
            if (client != null) {
                action.accept(client);
                count++;
            }
        }
        return count;
    }


    private <K, V> void join(ConcurrentMap<K, Set<V>> map, K room, V value) {

        Set<V> clients = map.computeIfAbsent(
                room,
                r -> Collections.newSetFromMap(new ConcurrentHashMap<>())
        );

       if (clients.add(value)) {
           if (room instanceof String) {
               metrics.roomJoin(name); // EXACTLY once
           }
       }

    }


    public void join(String room, UUID sessionId) {
        join(roomClients, room, sessionId);
        join(clientRooms, sessionId, room);
    }

    public void leaveRoom(String room, UUID sessionId) {
        leave(room, sessionId);
        storeFactory.eventStore().publish(EventType.LEAVE, new LeaveMessage(sessionId, room, getName()));
    }

    public void leaveRooms(Set<String> rooms, final UUID sessionId) {
        for (String room : rooms) {
            leave(room, sessionId);
        }
        storeFactory.eventStore().publish(EventType.BULK_LEAVE, new BulkLeaveMessage(sessionId, rooms, getName()));
    }

    @SuppressWarnings("checkstyle:AvoidInlineConditionals")
    private <K, V> void leave(ConcurrentMap<K, Set<V>> map, K room, V sessionId) {

        Set<V> clients = map.get(room);
        if (clients == null) {
            return;
        }

        boolean removed = clients.remove(sessionId);
        if (!removed) {
            return; // nothing changed → do NOT count
        }

        if (room instanceof String) {
            metrics.roomLeave(name); // EXACTLY once
        }

        map.computeIfPresent(room, (k, v) -> v.isEmpty() ? null : v);

    }


    public void leave(String room, UUID sessionId) {
        leave(roomClients, room, sessionId);
        leave(clientRooms, sessionId, room);
    }

    public Set<String> getRooms(SocketIOClient client) {
        Set<String> res = clientRooms.get(client.getSessionId());
        if (res == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(res);
    }

    public Set<String> getRooms() {
        return roomClients.keySet();
    }

    public Iterable<SocketIOClient> getRoomClients(String room) {
        Set<UUID> sessionIds = roomClients.get(room);

        if (sessionIds == null) {
            return Collections.emptyList();
        }

        List<SocketIOClient> result = new ArrayList<>();
        for (UUID sessionId : sessionIds) {
            SocketIOClient client = allClients.get(sessionId);
            if (client != null) {
                result.add(client);
            }
        }
        return result;
    }

    public int getRoomClientsInCluster(String room) {
        Set<UUID> sessionIds = roomClients.get(room);
        if (sessionIds == null) {
            return 0;
        }
        return sessionIds.size();
    }

    @Override
    public Collection<SocketIOClient> getAllClients() {
        return Collections.unmodifiableCollection(allClients.values());
    }

    public JsonSupport getJsonSupport() {
        return jsonSupport;
    }

    @Override
    public SocketIOClient getClient(UUID uuid) {
        return allClients.get(uuid);
    }

    @Override
    public void addAuthTokenListener(final AuthTokenListener listener) {
        this.authDataInterceptors.add(listener);
    }

  public AuthTokenResult onAuthData(SocketIOClient client, Object authData) {
      try {
          for (AuthTokenListener listener : authDataInterceptors) {
              final AuthTokenResult result = listener.getAuthTokenResult(authData, client);
              if (!result.isSuccess()) {
                return result;
              }
          }
          return AuthTokenResult.AUTH_TOKEN_RESULT_SUCCESS;
      } catch (Exception e) {
          exceptionListener.onAuthException(e, client);
      }
      return new AuthTokenResult(false, "Internal error");
  }
}
