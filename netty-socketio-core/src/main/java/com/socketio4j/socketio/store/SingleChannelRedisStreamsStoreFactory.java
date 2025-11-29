package com.socketio4j.socketio.store;

import java.time.Duration;
import java.util.*;

import com.socketio4j.socketio.store.event.EventType;
import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.store.event.BaseStoreFactory;
import com.socketio4j.socketio.store.event.EventStore;
import org.redisson.api.StreamMessageId;

public class SingleChannelRedisStreamsStoreFactory extends BaseStoreFactory {

    private final RedissonClient redissonClient;
    private final EventStore eventStore;

    public SingleChannelRedisStreamsStoreFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.eventStore = new SingleChannelRedisStreamsStore("socketio", getNodeId(), redissonClient, 3, StreamMessageId.NEWEST, Duration.ofSeconds(1), 100, Collections.singletonList(EventType.ALL_SINGLE_CHANNEL)
        );
    }

    public SingleChannelRedisStreamsStoreFactory(RedissonClient redissonClient, SingleChannelRedisStreamsStore pubSubStore) {
        this.redissonClient = redissonClient;
        this.eventStore = pubSubStore;
    }

    @Override
    public Store createStore(UUID sessionId) {
        return new RedissonStore(sessionId, redissonClient);
    }

    @Override
    public EventStore pubSubStore() {
        return eventStore;
    }

    @Override
    public void shutdown() {
        eventStore.shutdown();
        redissonClient.shutdown();
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return redissonClient.getMap(name);
    }
}