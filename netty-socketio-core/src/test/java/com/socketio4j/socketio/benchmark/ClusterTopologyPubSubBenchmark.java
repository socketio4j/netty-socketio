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
package com.socketio4j.socketio.benchmark;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.container.CustomizedHazelcastContainer;
import com.socketio4j.socketio.store.container.CustomizedKafkaContainer;
import com.socketio4j.socketio.store.container.CustomizedNatsContainer;
import com.socketio4j.socketio.store.container.CustomizedRedisContainer;
import com.socketio4j.socketio.store.event.ConnectMessage;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.JoinMessage;
import com.socketio4j.socketio.store.hazelcast.HazelcastPubSubEventStore;
import com.socketio4j.socketio.store.hazelcast_ringbuffer.HazelcastPubSubRingBufferEventStore;
import com.socketio4j.socketio.store.kafka.KafkaEventStore;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageDeserializer;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageSerializer;
import com.socketio4j.socketio.store.nats_pubsub.NatsEventStore;
import com.socketio4j.socketio.store.redis_pubsub.RedisPubSubEventStore;
import com.socketio4j.socketio.store.redis_reliable.RedisPubSubReliableEventStore;
import com.socketio4j.socketio.store.redis_stream.RedisStreamEventStore;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;

/**
 * Multi-Server, Multi-Event Cluster Benchmark.
 *
 * <p>Demonstrates where MULTI_CHANNEL mode outperforms SINGLE_CHANNEL mode under realistic
 * cluster topologies with mixed event streams:
 * <ul>
 *   <li><b>Scenario 1: Selective Subscription & Noisy Neighbor</b>: Evaluates CPU & network waste
 *       when a specialized node (e.g. Chat Server) is flooded with irrelevant events (presence/room churn)
 *       in SINGLE_CHANNEL vs. zero-noise targeted delivery in MULTI_CHANNEL.</li>
 *   <li><b>Scenario 2: Head-of-Line (HoL) Blocking & Storm Isolation</b>: Evaluates delivery tail latency
 *       of high-priority chat messages when a burst of reconnect events is published concurrently.</li>
 * </ul>
 */
@Tag("benchmark")
public class ClusterTopologyPubSubBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ClusterTopologyPubSubBenchmark.class);

    public static class ClusterBenchmarkResult {
        public final String storeName;
        public final String scenarioName;
        public final String mode;
        public final int totalPublished;
        public final int targetExpected;
        public final int targetReceived;
        public final int noiseIngested;
        public final double noiseDiscardPercent;
        public final double usefulThroughput;
        public final double p50LatencyMs;
        public final double p95LatencyMs;
        public final double p99LatencyMs;
        public final double maxLatencyMs;

        public ClusterBenchmarkResult(String storeName, String scenarioName, String mode,
                                      int totalPublished, int targetExpected, int targetReceived,
                                      int noiseIngested, double noiseDiscardPercent,
                                      double usefulThroughput, double p50LatencyMs,
                                      double p95LatencyMs, double p99LatencyMs, double maxLatencyMs) {
            this.storeName = storeName;
            this.scenarioName = scenarioName;
            this.mode = mode;
            this.totalPublished = totalPublished;
            this.targetExpected = targetExpected;
            this.targetReceived = targetReceived;
            this.noiseIngested = noiseIngested;
            this.noiseDiscardPercent = noiseDiscardPercent;
            this.usefulThroughput = usefulThroughput;
            this.p50LatencyMs = p50LatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.maxLatencyMs = maxLatencyMs;
        }
    }

    public interface ClusterFixture extends AutoCloseable {
        String getName();
        EventStoreMode getMode();
        EventStore getPublisher();
        EventStore getChatSubscriber();
        EventStore getPresenceSubscriber();
        EventStore getRoomSubscriber();
    }

    // -------------------------------------------------------------------------
    // Benchmark Scenarios
    // -------------------------------------------------------------------------

    /**
     * Scenario 1: Selective Subscription & Noisy Neighbor.
     * Generates a mixed production stream: 35% DISPATCH, 45% CONNECT, 20% JOIN.
     * Chat node (Node 1) is ONLY interested in DISPATCH.
     */
    public static ClusterBenchmarkResult runSelectiveSubscriptionScenario(ClusterFixture fixture, int totalMessages) throws Exception {
        EventStore publisher = fixture.getPublisher();
        EventStore chatSub = fixture.getChatSubscriber();
        EventStore presenceSub = fixture.getPresenceSubscriber();
        EventStore roomSub = fixture.getRoomSubscriber();
        EventStoreMode mode = fixture.getMode();

        int dispatchCount = (int) (totalMessages * 0.35);
        int connectCount = (int) (totalMessages * 0.45);
        int joinCount = totalMessages - dispatchCount - connectCount;

        CountDownLatch chatLatch = new CountDownLatch(dispatchCount);
        AtomicInteger chatUsefulCount = new AtomicInteger(0);
        AtomicInteger chatNoiseCount = new AtomicInteger(0);
        double[] latencies = new double[dispatchCount];
        long[] firstAndLastRxTime = new long[]{0, 0};

        EventType subType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.DISPATCH;

        chatSub.subscribe(subType, (EventListener<EventMessage>) msg -> {
            long now = System.nanoTime();
            if (msg instanceof DispatchMessage) {
                int idx = chatUsefulCount.getAndIncrement();
                if (idx == 0) {
                    firstAndLastRxTime[0] = now;
                }
                firstAndLastRxTime[1] = now;

                if (idx < dispatchCount) {
                    String data = (String) ((DispatchMessage) msg).getPacket().getData();
                    if (data != null) {
                        int tsIndex = data.indexOf("\"ts\":");
                        if (tsIndex != -1) {
                            int endIndex = data.indexOf("}", tsIndex);
                            if (endIndex == -1) endIndex = data.length();
                            try {
                                long sentTs = Long.parseLong(data.substring(tsIndex + 5, endIndex).trim());
                                latencies[idx] = (now - sentTs) / 1_000_000.0;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                chatLatch.countDown();
            } else {
                chatNoiseCount.incrementAndGet();
            }
        }, EventMessage.class);

        // Presence node subscribes to CONNECT (or ALL in SINGLE_CHANNEL)
        EventType presType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.CONNECT;
        CountDownLatch presLatch = new CountDownLatch(connectCount);
        presenceSub.subscribe(presType, (EventListener<EventMessage>) msg -> {
            if (msg instanceof ConnectMessage) {
                presLatch.countDown();
            }
        }, EventMessage.class);

        // Room node subscribes to JOIN (or ALL in SINGLE_CHANNEL)
        EventType roomType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.JOIN;
        CountDownLatch roomLatch = new CountDownLatch(joinCount);
        roomSub.subscribe(roomType, (EventListener<EventMessage>) msg -> {
            if (msg instanceof JoinMessage) {
                roomLatch.countDown();
            }
        }, EventMessage.class);

        Thread.sleep(100);

        // Publish interleaved events
        long pubStartTime = System.nanoTime();
        int dSent = 0, cSent = 0, jSent = 0;
        for (int i = 0; i < totalMessages; i++) {
            int mod = i % 10;
            if (mod < 4 && dSent < dispatchCount) {
                // DISPATCH
                long now = System.nanoTime();
                Packet packet = new Packet(PacketType.MESSAGE);
                packet.setData("{\"seq\":" + dSent + ",\"ts\":" + now + "}");
                EventType pType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.DISPATCH;
                publisher.publish(pType, new DispatchMessage("room_bench", packet, "/bench"));
                dSent++;
            } else if (mod < 8 && cSent < connectCount) {
                // CONNECT
                EventType pType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.CONNECT;
                publisher.publish(pType, new ConnectMessage(UUID.randomUUID()));
                cSent++;
            } else if (jSent < joinCount) {
                // JOIN
                EventType pType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.JOIN;
                publisher.publish(pType, new JoinMessage(UUID.randomUUID(), "room_bench", "/bench"));
                jSent++;
            } else if (dSent < dispatchCount) {
                long now = System.nanoTime();
                Packet packet = new Packet(PacketType.MESSAGE);
                packet.setData("{\"seq\":" + dSent + ",\"ts\":" + now + "}");
                EventType pType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.DISPATCH;
                publisher.publish(pType, new DispatchMessage("room_bench", packet, "/bench"));
                dSent++;
            } else if (cSent < connectCount) {
                EventType pType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.CONNECT;
                publisher.publish(pType, new ConnectMessage(UUID.randomUUID()));
                cSent++;
            }
        }

        boolean completed = chatLatch.await(30, TimeUnit.SECONDS);
        long totalDurationNanos = firstAndLastRxTime[1] > firstAndLastRxTime[0] ? (firstAndLastRxTime[1] - firstAndLastRxTime[0]) : (System.nanoTime() - pubStartTime);
        double durationSec = totalDurationNanos / 1_000_000_000.0;
        if (durationSec <= 0) durationSec = 0.0001;

        int received = chatUsefulCount.get();
        int noise = chatNoiseCount.get();
        int totalIngested = received + noise;
        double discardPct = totalIngested > 0 ? (noise * 100.0) / totalIngested : 0.0;
        double throughput = received / durationSec;

        double p50 = 0, p95 = 0, p99 = 0, max = 0;
        if (received > 0) {
            double[] sorted = Arrays.copyOf(latencies, received);
            Arrays.sort(sorted);
            p50 = sorted[(int) (received * 0.50)];
            p95 = sorted[Math.min((int) (received * 0.95), received - 1)];
            p99 = sorted[Math.min((int) (received * 0.99), received - 1)];
            max = sorted[received - 1];
        }

        chatSub.unsubscribe(subType);
        presenceSub.unsubscribe(presType);
        roomSub.unsubscribe(roomType);

        return new ClusterBenchmarkResult(
                fixture.getName(),
                "Selective Sub",
                mode.name(),
                totalMessages,
                dispatchCount,
                received,
                noise,
                discardPct,
                throughput,
                p50,
                p95,
                p99,
                max
        );
    }

    /**
     * Scenario 2: Head-of-Line (HoL) Blocking & Storm Isolation.
     * Emits a background burst storm of stormMessages (e.g. 2,000 CONNECT events),
     * followed immediately by targetMessages (e.g. 200 high-priority DISPATCH events).
     */
    public static ClusterBenchmarkResult runStormHolScenario(ClusterFixture fixture, int stormMessages, int targetMessages) throws Exception {
        EventStore publisher = fixture.getPublisher();
        EventStore chatSub = fixture.getChatSubscriber();
        EventStoreMode mode = fixture.getMode();

        CountDownLatch chatLatch = new CountDownLatch(targetMessages);
        AtomicInteger chatUsefulCount = new AtomicInteger(0);
        AtomicInteger chatNoiseCount = new AtomicInteger(0);
        double[] latencies = new double[targetMessages];
        long[] firstAndLastRxTime = new long[]{0, 0};

        EventType subType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.DISPATCH;

        chatSub.subscribe(subType, (EventListener<EventMessage>) msg -> {
            long now = System.nanoTime();
            if (msg instanceof DispatchMessage) {
                int idx = chatUsefulCount.getAndIncrement();
                if (idx == 0) firstAndLastRxTime[0] = now;
                firstAndLastRxTime[1] = now;

                if (idx < targetMessages) {
                    String data = (String) ((DispatchMessage) msg).getPacket().getData();
                    if (data != null) {
                        int tsIndex = data.indexOf("\"ts\":");
                        if (tsIndex != -1) {
                            int endIndex = data.indexOf("}", tsIndex);
                            if (endIndex == -1) endIndex = data.length();
                            try {
                                long sentTs = Long.parseLong(data.substring(tsIndex + 5, endIndex).trim());
                                latencies[idx] = (now - sentTs) / 1_000_000.0;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                chatLatch.countDown();
            } else {
                chatNoiseCount.incrementAndGet();
            }
        }, EventMessage.class);

        Thread.sleep(100);

        // 1. Emit storm of CONNECT messages
        EventType connPubType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.CONNECT;
        for (int i = 0; i < stormMessages; i++) {
            publisher.publish(connPubType, new ConnectMessage(UUID.randomUUID()));
        }

        // 2. Immediately emit high-priority DISPATCH messages
        long dispatchSendStart = System.nanoTime();
        EventType dispPubType = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL : EventType.DISPATCH;
        for (int i = 0; i < targetMessages; i++) {
            long now = System.nanoTime();
            Packet packet = new Packet(PacketType.MESSAGE);
            packet.setData("{\"seq\":" + i + ",\"ts\":" + now + "}");
            publisher.publish(dispPubType, new DispatchMessage("room_bench", packet, "/bench"));
        }

        boolean completed = chatLatch.await(30, TimeUnit.SECONDS);
        long totalDurationNanos = firstAndLastRxTime[1] > dispatchSendStart ? (firstAndLastRxTime[1] - dispatchSendStart) : (System.nanoTime() - dispatchSendStart);
        double durationSec = totalDurationNanos / 1_000_000_000.0;
        if (durationSec <= 0) durationSec = 0.0001;

        int received = chatUsefulCount.get();
        int noise = chatNoiseCount.get();
        int totalIngested = received + noise;
        double discardPct = totalIngested > 0 ? (noise * 100.0) / totalIngested : 0.0;
        double throughput = received / durationSec;

        double p50 = 0, p95 = 0, p99 = 0, max = 0;
        if (received > 0) {
            double[] sorted = Arrays.copyOf(latencies, received);
            Arrays.sort(sorted);
            p50 = sorted[(int) (received * 0.50)];
            p95 = sorted[Math.min((int) (received * 0.95), received - 1)];
            p99 = sorted[Math.min((int) (received * 0.99), received - 1)];
            max = sorted[received - 1];
        }

        chatSub.unsubscribe(subType);

        return new ClusterBenchmarkResult(
                fixture.getName(),
                "Storm HoL",
                mode.name(),
                stormMessages + targetMessages,
                targetMessages,
                received,
                noise,
                discardPct,
                throughput,
                p50,
                p95,
                p99,
                max
        );
    }

    public static void printClusterResultsTable(List<ClusterBenchmarkResult> results) {
        String sep = "+--------------------------------------+---------------+--------+-----------+-----------+-----------+---------+-------------+----------+----------+----------+----------+";
        System.out.println("\n" + sep);
        System.out.printf("| %-36s | %-13s | %-6s | %-9s | %-9s | %-9s | %-7s | %-11s | %-8s | %-8s | %-8s | %-8s |\n",
                "Store Backend", "Scenario", "Mode", "Total Msg", "Target Rx", "Noise Rx", "Waste %", "Useful msg/s", "P50(ms)", "P95(ms)", "P99(ms)", "Max(ms)");
        System.out.println(sep);

        for (ClusterBenchmarkResult r : results) {
            System.out.printf(Locale.US, "| %-36s | %-13s | %-6s | %-9d | %-9d | %-9d | %-6.1f%% | %-11.1f | %-8.2f | %-8.2f | %-8.2f | %-8.2f |\n",
                    r.storeName,
                    r.scenarioName,
                    r.mode.equals("SINGLE_CHANNEL") ? "SINGLE" : "MULTI",
                    r.totalPublished,
                    r.targetReceived,
                    r.noiseIngested,
                    r.noiseDiscardPercent,
                    r.usefulThroughput,
                    r.p50LatencyMs,
                    r.p95LatencyMs,
                    r.p99LatencyMs,
                    r.maxLatencyMs);
        }
        System.out.println(sep + "\n");
    }

    // -------------------------------------------------------------------------
    // In-Memory Cluster Implementation
    // -------------------------------------------------------------------------

    public static class InMemoryClusterSharedState {
        private final ConcurrentHashMap<String, ConcurrentLinkedQueue<EventListener<EventMessage>>> channelListeners =
                new ConcurrentHashMap<>();

        public void publish(String channel, EventMessage msg, Long senderNodeId) {
            msg.setNodeId(senderNodeId);
            ConcurrentLinkedQueue<EventListener<EventMessage>> list = channelListeners.get(channel);
            if (list != null) {
                for (EventListener<EventMessage> l : list) {
                    l.onMessage(msg);
                }
            }
        }

        public void subscribe(String channel, EventListener<EventMessage> listener) {
            channelListeners.computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>()).add(listener);
        }

        public void unsubscribe(String channel) {
            channelListeners.remove(channel);
        }
    }

    public static class InMemoryClusterNode implements EventStore {
        private final InMemoryClusterSharedState shared;
        private final Long nodeId;
        private final EventStoreMode mode;

        public InMemoryClusterNode(InMemoryClusterSharedState shared, Long nodeId, EventStoreMode mode) {
            this.shared = shared;
            this.nodeId = nodeId;
            this.mode = mode;
        }

        @Override
        public void publish(EventType type, EventMessage message) {
            publish0(type, message);
        }

        @Override
        public void publish0(EventType type, EventMessage message) {
            String ch = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL.name() : type.name();
            shared.publish(ch, message, nodeId);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends EventMessage> void subscribe(EventType type, EventListener<T> listener, Class<T> clazz) {
            String ch = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL.name() : type.name();
            shared.subscribe(ch, (EventListener<EventMessage>) listener);
        }

        @Override
        public <T extends EventMessage> void subscribe0(EventType type, EventListener<T> listener, Class<T> clazz) {
            subscribe(type, listener, clazz);
        }

        @Override
        public void unsubscribe(EventType type) {
            String ch = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL.name() : type.name();
            shared.unsubscribe(ch);
        }

        @Override
        public void unsubscribe0(EventType type) {
            unsubscribe(type);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void shutdown0() {
        }

        @Override
        public EventStoreMode getEventStoreMode() {
            return mode;
        }
    }

    public static ClusterFixture createMemoryClusterFixture(EventStoreMode mode) {
        InMemoryClusterSharedState shared = new InMemoryClusterSharedState();
        InMemoryClusterNode pub = new InMemoryClusterNode(shared, 100L, mode);
        InMemoryClusterNode chat = new InMemoryClusterNode(shared, 1L, mode);
        InMemoryClusterNode pres = new InMemoryClusterNode(shared, 2L, mode);
        InMemoryClusterNode room = new InMemoryClusterNode(shared, 3L, mode);

        return new ClusterFixture() {
            @Override public String getName() { return "Memory (Cluster)"; }
            @Override public EventStoreMode getMode() { return mode; }
            @Override public EventStore getPublisher() { return pub; }
            @Override public EventStore getChatSubscriber() { return chat; }
            @Override public EventStore getPresenceSubscriber() { return pres; }
            @Override public EventStore getRoomSubscriber() { return room; }
            @Override public void close() {}
        };
    }

    // -------------------------------------------------------------------------
    // Redis Stream Cluster Fixture
    // -------------------------------------------------------------------------

    public static ClusterFixture createRedisStreamClusterFixture(CustomizedRedisContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        Config cfg = new Config();
        cfg.useSingleServer().setAddress("redis://" + container.getHost() + ":" + container.getRedisPort());

        RedissonClient redissonPub = Redisson.create(cfg);
        RedissonClient redissonChat = Redisson.create(cfg);
        RedissonClient redissonPres = Redisson.create(cfg);
        RedissonClient redissonRoom = Redisson.create(cfg);

        String prefix = "bench_cstream_" + (EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "s_" : "m_") + UUID.randomUUID().toString().substring(0, 8) + "_";
        RedisStreamEventStore pub = new RedisStreamEventStore(redissonPub, redissonPub, 100L, mode, prefix, 10000);
        RedisStreamEventStore chat = new RedisStreamEventStore(redissonChat, redissonChat, 1L, mode, prefix, 10000);
        RedisStreamEventStore pres = new RedisStreamEventStore(redissonPres, redissonPres, 2L, mode, prefix, 10000);
        RedisStreamEventStore room = new RedisStreamEventStore(redissonRoom, redissonRoom, 3L, mode, prefix, 10000);

        return new ClusterFixture() {
            @Override public String getName() { return "Redis (Stream)"; }
            @Override public EventStoreMode getMode() { return mode; }
            @Override public EventStore getPublisher() { return pub; }
            @Override public EventStore getChatSubscriber() { return chat; }
            @Override public EventStore getPresenceSubscriber() { return pres; }
            @Override public EventStore getRoomSubscriber() { return room; }
            @Override
            public void close() {
                try { pub.shutdown(); } catch (Exception ignored) {}
                try { chat.shutdown(); } catch (Exception ignored) {}
                try { pres.shutdown(); } catch (Exception ignored) {}
                try { room.shutdown(); } catch (Exception ignored) {}
                try { redissonPub.shutdown(); } catch (Exception ignored) {}
                try { redissonChat.shutdown(); } catch (Exception ignored) {}
                try { redissonPres.shutdown(); } catch (Exception ignored) {}
                try { redissonRoom.shutdown(); } catch (Exception ignored) {}
            }
        };
    }

    // -------------------------------------------------------------------------
    // Redis Pub/Sub Cluster Fixture
    // -------------------------------------------------------------------------

    public static ClusterFixture createRedisPubSubClusterFixture(CustomizedRedisContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        Config cfg = new Config();
        cfg.useSingleServer().setAddress("redis://" + container.getHost() + ":" + container.getRedisPort());

        RedissonClient redissonPub = Redisson.create(cfg);
        RedissonClient redissonChat = Redisson.create(cfg);
        RedissonClient redissonPres = Redisson.create(cfg);
        RedissonClient redissonRoom = Redisson.create(cfg);

        RedisPubSubEventStore pub = new RedisPubSubEventStore(redissonPub, redissonPub, mode, 100L);
        RedisPubSubEventStore chat = new RedisPubSubEventStore(redissonChat, redissonChat, mode, 1L);
        RedisPubSubEventStore pres = new RedisPubSubEventStore(redissonPres, redissonPres, mode, 2L);
        RedisPubSubEventStore room = new RedisPubSubEventStore(redissonRoom, redissonRoom, mode, 3L);

        return new ClusterFixture() {
            @Override public String getName() { return "Redis (Pub/Sub)"; }
            @Override public EventStoreMode getMode() { return mode; }
            @Override public EventStore getPublisher() { return pub; }
            @Override public EventStore getChatSubscriber() { return chat; }
            @Override public EventStore getPresenceSubscriber() { return pres; }
            @Override public EventStore getRoomSubscriber() { return room; }
            @Override
            public void close() {
                try { pub.shutdown(); } catch (Exception ignored) {}
                try { chat.shutdown(); } catch (Exception ignored) {}
                try { pres.shutdown(); } catch (Exception ignored) {}
                try { room.shutdown(); } catch (Exception ignored) {}
                try { redissonPub.shutdown(); } catch (Exception ignored) {}
                try { redissonChat.shutdown(); } catch (Exception ignored) {}
                try { redissonPres.shutdown(); } catch (Exception ignored) {}
                try { redissonRoom.shutdown(); } catch (Exception ignored) {}
            }
        };
    }

    // -------------------------------------------------------------------------
    // NATS Cluster Fixture
    // -------------------------------------------------------------------------

    public static ClusterFixture createNatsClusterFixture(CustomizedNatsContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        Options opt = new Options.Builder().server(container.getNatsUrl()).build();
        Connection nc1 = Nats.connect(opt);
        Connection nc2 = Nats.connect(opt);
        Connection nc3 = Nats.connect(opt);
        Connection nc4 = Nats.connect(opt);

        NatsEventStore pub = new NatsEventStore(nc1, mode, 100L);
        NatsEventStore chat = new NatsEventStore(nc2, mode, 1L);
        NatsEventStore pres = new NatsEventStore(nc3, mode, 2L);
        NatsEventStore room = new NatsEventStore(nc4, mode, 3L);

        return new ClusterFixture() {
            @Override public String getName() { return "NATS"; }
            @Override public EventStoreMode getMode() { return mode; }
            @Override public EventStore getPublisher() { return pub; }
            @Override public EventStore getChatSubscriber() { return chat; }
            @Override public EventStore getPresenceSubscriber() { return pres; }
            @Override public EventStore getRoomSubscriber() { return room; }
            @Override
            public void close() {
                try { pub.shutdown(); } catch (Exception ignored) {}
                try { chat.shutdown(); } catch (Exception ignored) {}
                try { pres.shutdown(); } catch (Exception ignored) {}
                try { room.shutdown(); } catch (Exception ignored) {}
                try { nc1.close(); } catch (Exception ignored) {}
                try { nc2.close(); } catch (Exception ignored) {}
                try { nc3.close(); } catch (Exception ignored) {}
                try { nc4.close(); } catch (Exception ignored) {}
            }
        };
    }

    // -------------------------------------------------------------------------
    // Apache Kafka Cluster Fixture
    // -------------------------------------------------------------------------

    public static ClusterFixture createKafkaClusterFixture(CustomizedKafkaContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        String bootstrap = container.getBootstrapServers();

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, EventMessageSerializer.class.getName());

        Properties cProps1 = new Properties();
        cProps1.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cProps1.put(ConsumerConfig.GROUP_ID_CONFIG, "bench-chat-" + UUID.randomUUID());
        cProps1.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cProps1.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cProps1.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventMessageDeserializer.class);

        Properties cProps2 = new Properties();
        cProps2.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cProps2.put(ConsumerConfig.GROUP_ID_CONFIG, "bench-pres-" + UUID.randomUUID());
        cProps2.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cProps2.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cProps2.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventMessageDeserializer.class);

        Properties cProps3 = new Properties();
        cProps3.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cProps3.put(ConsumerConfig.GROUP_ID_CONFIG, "bench-room-" + UUID.randomUUID());
        cProps3.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cProps3.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cProps3.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventMessageDeserializer.class);

        KafkaProducer<String, EventMessage> producer = new KafkaProducer<String, EventMessage>(producerProps);
        String topicPrefix = "bench_ckafka_" + (EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "s_" : "m_") + UUID.randomUUID().toString().substring(0, 6) + "_";

        // Pre-seed topics
        if (EventStoreMode.SINGLE_CHANNEL.equals(mode)) {
            try {
                Packet p = new Packet(PacketType.MESSAGE);
                p.setData("{\"init\":true}");
                producer.send(new ProducerRecord<String, EventMessage>(topicPrefix + EventType.ALL_SINGLE_CHANNEL.name(), new DispatchMessage("init", p, "/"))).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        } else {
            try {
                Packet p = new Packet(PacketType.MESSAGE);
                p.setData("{\"init\":true}");
                producer.send(new ProducerRecord<String, EventMessage>(topicPrefix + EventType.DISPATCH.name(), new DispatchMessage("init", p, "/"))).get(5, TimeUnit.SECONDS);
                producer.send(new ProducerRecord<String, EventMessage>(topicPrefix + EventType.CONNECT.name(), new ConnectMessage(UUID.randomUUID()))).get(5, TimeUnit.SECONDS);
                producer.send(new ProducerRecord<String, EventMessage>(topicPrefix + EventType.JOIN.name(), new JoinMessage(UUID.randomUUID(), "init", "/"))).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }

        KafkaEventStore pub = new KafkaEventStore(producer, cProps1, 100L, mode, topicPrefix);
        KafkaEventStore chat = new KafkaEventStore(producer, cProps1, 1L, mode, topicPrefix);
        KafkaEventStore pres = new KafkaEventStore(producer, cProps2, 2L, mode, topicPrefix);
        KafkaEventStore room = new KafkaEventStore(producer, cProps3, 3L, mode, topicPrefix);

        return new ClusterFixture() {
            @Override public String getName() { return "Apache Kafka"; }
            @Override public EventStoreMode getMode() { return mode; }
            @Override public EventStore getPublisher() { return pub; }
            @Override public EventStore getChatSubscriber() { return chat; }
            @Override public EventStore getPresenceSubscriber() { return pres; }
            @Override public EventStore getRoomSubscriber() { return room; }
            @Override
            public void close() {
                try { pub.shutdown(); } catch (Exception ignored) {}
                try { chat.shutdown(); } catch (Exception ignored) {}
                try { pres.shutdown(); } catch (Exception ignored) {}
                try { room.shutdown(); } catch (Exception ignored) {}
                try { producer.close(); } catch (Exception ignored) {}
            }
        };
    }

    // -------------------------------------------------------------------------
    // Main CLI Entry Point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        int totalMessages = 3000;
        int stormMessages = 2000;
        int targetMessages = 200;
        String storeArg = "all";

        for (int i = 0; i < args.length; i++) {
            if ("--messages".equals(args[i]) || "-m".equals(args[i])) {
                if (i + 1 < args.length) totalMessages = Integer.parseInt(args[++i]);
            } else if ("--stores".equals(args[i]) || "-s".equals(args[i])) {
                if (i + 1 < args.length) storeArg = args[++i].toLowerCase(Locale.ROOT);
            }
        }

        System.out.println("==========================================================================");
        System.out.println("    Socketio4j Multi-Server Multi-Event Cluster Benchmark                ");
        System.out.println("==========================================================================");
        System.out.println("Topology: 4 Cluster Nodes (Publisher, Chat Sub, Presence Sub, Room Sub)");
        System.out.println("Config: totalMessages=" + totalMessages + ", stormMessages=" + stormMessages + ", targetMessages=" + targetMessages);

        List<ClusterBenchmarkResult> results = new ArrayList<>();
        CustomizedRedisContainer redisContainer = null;
        CustomizedNatsContainer natsContainer = null;
        CustomizedKafkaContainer kafkaContainer = null;

        try {
            boolean runAll = storeArg.contains("all");

            // 1. In-Memory Baseline
            if (runAll || storeArg.contains("memory")) {
                System.out.println("\n[1] Benchmarking Memory (Cluster)...");
                // Multi
                try (ClusterFixture f = createMemoryClusterFixture(EventStoreMode.MULTI_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
                // Single
                try (ClusterFixture f = createMemoryClusterFixture(EventStoreMode.SINGLE_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
            }

            // 2. Redis Pub/Sub
            if (runAll || storeArg.contains("redis_pubsub") || storeArg.contains("redis")) {
                System.out.println("\n[2] Benchmarking Redis Pub/Sub...");
                redisContainer = new CustomizedRedisContainer();
                try (ClusterFixture f = createRedisPubSubClusterFixture(redisContainer, EventStoreMode.MULTI_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
                try (ClusterFixture f = createRedisPubSubClusterFixture(redisContainer, EventStoreMode.SINGLE_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
            }

            // 3. Redis Stream
            if (runAll || storeArg.contains("redis_stream")) {
                System.out.println("\n[3] Benchmarking Redis Stream...");
                if (redisContainer == null) {
                    redisContainer = new CustomizedRedisContainer();
                }
                try (ClusterFixture f = createRedisStreamClusterFixture(redisContainer, EventStoreMode.MULTI_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
                try (ClusterFixture f = createRedisStreamClusterFixture(redisContainer, EventStoreMode.SINGLE_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
            }

            // 4. NATS
            if (runAll || storeArg.contains("nats")) {
                System.out.println("\n[4] Benchmarking NATS...");
                natsContainer = new CustomizedNatsContainer();
                try (ClusterFixture f = createNatsClusterFixture(natsContainer, EventStoreMode.MULTI_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
                try (ClusterFixture f = createNatsClusterFixture(natsContainer, EventStoreMode.SINGLE_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
            }

            // 5. Apache Kafka
            if (runAll || storeArg.contains("kafka")) {
                System.out.println("\n[5] Benchmarking Apache Kafka...");
                kafkaContainer = new CustomizedKafkaContainer();
                try (ClusterFixture f = createKafkaClusterFixture(kafkaContainer, EventStoreMode.MULTI_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
                try (ClusterFixture f = createKafkaClusterFixture(kafkaContainer, EventStoreMode.SINGLE_CHANNEL)) {
                    results.add(runSelectiveSubscriptionScenario(f, totalMessages));
                    results.add(runStormHolScenario(f, stormMessages, targetMessages));
                }
            }

        } catch (Exception e) {
            log.error("Cluster benchmark error", e);
        } finally {
            if (redisContainer != null && redisContainer.isRunning()) redisContainer.stop();
            if (natsContainer != null && natsContainer.isRunning()) natsContainer.stop();
            if (kafkaContainer != null && kafkaContainer.isRunning()) kafkaContainer.stop();
        }

        printClusterResultsTable(results);
    }

    @Test
    public void testSmokeClusterBenchmark() throws Exception {
        try (ClusterFixture f = createMemoryClusterFixture(EventStoreMode.MULTI_CHANNEL)) {
            ClusterBenchmarkResult res = runSelectiveSubscriptionScenario(f, 500);
            org.junit.jupiter.api.Assertions.assertEquals(0.0, res.noiseDiscardPercent, 0.01);
            org.junit.jupiter.api.Assertions.assertTrue(res.targetReceived > 0);
        }
    }
}
