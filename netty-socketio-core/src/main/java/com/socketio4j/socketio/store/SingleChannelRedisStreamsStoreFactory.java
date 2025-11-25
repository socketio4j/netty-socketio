package com.socketio4j.socketio.store;

import java.util.Map;
import java.util.UUID;

import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.store.pubsub.BaseStoreFactory;
import com.socketio4j.socketio.store.pubsub.PubSubStore;

public class SingleChannelRedisStreamsStoreFactory extends BaseStoreFactory {

    private final RedissonClient redissonClient;
    private final PubSubStore pubSubStore;

    public SingleChannelRedisStreamsStoreFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.pubSubStore = new SingleChannelRedisStreamsPubSubStore(getNodeId(), redissonClient);
    }
    @Override
    public Store createStore(UUID sessionId) {
        return new RedissonStore(sessionId, redissonClient);
    }

    @Override
    public PubSubStore pubSubStore() {
        return pubSubStore;
    }

    @Override
    public void shutdown() {
        redissonClient.shutdown();
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return redissonClient.getMap(name);
    }
}