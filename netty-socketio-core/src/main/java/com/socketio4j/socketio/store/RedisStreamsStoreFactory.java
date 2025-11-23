package com.socketio4j.socketio.store;

import java.util.Map;
import java.util.UUID;

import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.store.pubsub.BaseStoreFactory;
import com.socketio4j.socketio.store.pubsub.PubSubStore;

public class RedisStreamsStoreFactory extends BaseStoreFactory {

    private final RedissonClient redis;
    private final RedisStreamsPubSubStore pubSubStore;

    public RedisStreamsStoreFactory(RedissonClient redis) {
        this.redis = redis;
        this.pubSubStore = new RedisStreamsPubSubStore(getNodeId(), redis);

    }

    @Override
    public PubSubStore pubSubStore() {
        return pubSubStore;
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        // For now Redis Streams backend does not use shared maps
        return redis.getMap(name);
    }

    @Override
    public Store createStore(UUID sessionId) {
        // One Store per session (correct)
        return new RedissonStore(sessionId, this.redis);
    }

    @Override
    public void shutdown() {
        redis.shutdown();
    }
}

