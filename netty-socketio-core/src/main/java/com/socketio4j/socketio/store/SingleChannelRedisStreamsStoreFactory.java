package com.socketio4j.socketio.store;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.socketio4j.socketio.store.pubsub.PubSubMessage;
import org.redisson.api.RedissonClient;

import com.socketio4j.socketio.store.pubsub.BaseStoreFactory;
import com.socketio4j.socketio.store.pubsub.PubSubStore;
import org.redisson.api.StreamMessageId;

public class SingleChannelRedisStreamsStoreFactory extends BaseStoreFactory {

    private final RedissonClient redissonClient;
    private final PubSubStore pubSubStore;

    public SingleChannelRedisStreamsStoreFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.pubSubStore = new SingleChannelRedisStreamsPubSubStore("socketio", getNodeId(), redissonClient, 3, StreamMessageId.NEWEST, Duration.ofSeconds(1), 100);
    }

    public SingleChannelRedisStreamsStoreFactory(RedissonClient redissonClient, SingleChannelRedisStreamsPubSubStore pubSubStore) {
        this.redissonClient = redissonClient;
        this.pubSubStore = pubSubStore;
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
        pubSubStore.shutdown();
        redissonClient.shutdown();
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return redissonClient.getMap(name);
    }
}