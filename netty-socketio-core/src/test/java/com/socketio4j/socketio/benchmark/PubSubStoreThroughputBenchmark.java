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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import com.mongodb.reactivestreams.client.MongoClient;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.container.CustomizedHazelcastContainer;
import com.socketio4j.socketio.store.container.CustomizedKafkaContainer;
import com.socketio4j.socketio.store.container.CustomizedMongoContainer;
import com.socketio4j.socketio.store.container.CustomizedNatsContainer;
import com.socketio4j.socketio.store.container.CustomizedRedisContainer;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.hazelcast.HazelcastPubSubEventStore;
import com.socketio4j.socketio.store.hazelcast_ringbuffer.HazelcastPubSubRingBufferEventStore;
import com.socketio4j.socketio.store.kafka.KafkaEventStore;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageDeserializer;
import com.socketio4j.socketio.store.kafka.serialization.EventMessageSerializer;
import com.socketio4j.socketio.store.mongo.MongoEventStore;
import com.socketio4j.socketio.store.nats_pubsub.NatsEventStore;
import com.socketio4j.socketio.store.redis_pubsub.RedisPubSubEventStore;
import com.socketio4j.socketio.store.redis_reliable.RedisPubSubReliableEventStore;
import com.socketio4j.socketio.store.redis_stream.RedisStreamEventStore;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;

/**
 * End-to-end throughput and latency benchmark harness for all Socketio4j pub/sub event stores.
 * Measures publish throughput (msg/sec), consume throughput (msg/sec), and latency distribution.
 */
@Tag("benchmark")
public class PubSubStoreThroughputBenchmark {

    private static final Logger log = LoggerFactory.getLogger(PubSubStoreThroughputBenchmark.class);

    private static final int DEFAULT_MESSAGES = 2000;
    private static final int DEFAULT_WARMUP = 200;
    private static final int DEFAULT_THREADS = 1;
    private static final int DEFAULT_ITERATIONS = 3;

    /**
     * Benchmark result holding throughput and latency statistics.
     */
    public static class BenchmarkResult {
        public final String storeName;
        public final int totalMessages;
        public final int threads;
        public final int iterations;
        public final double publishThroughput;
        public final double consumeThroughput;
        public final double meanLatencyMs;
        public final double p50LatencyMs;
        public final double p95LatencyMs;
        public final double p99LatencyMs;
        public final double correctedP50LatencyMs;
        public final double correctedP95LatencyMs;
        public final double correctedP99LatencyMs;
        public final double lossRatePercent;

        public BenchmarkResult(String storeName, int totalMessages, int threads, int iterations,
                               double publishThroughput, double consumeThroughput,
                               double meanLatencyMs, double p50LatencyMs,
                               double p95LatencyMs, double p99LatencyMs,
                               double correctedP50LatencyMs, double correctedP95LatencyMs,
                               double correctedP99LatencyMs,
                               double lossRatePercent) {
            this.storeName = storeName;
            this.totalMessages = totalMessages;
            this.threads = threads;
            this.iterations = iterations;
            this.publishThroughput = publishThroughput;
            this.consumeThroughput = consumeThroughput;
            this.meanLatencyMs = meanLatencyMs;
            this.p50LatencyMs = p50LatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.correctedP50LatencyMs = correctedP50LatencyMs;
            this.correctedP95LatencyMs = correctedP95LatencyMs;
            this.correctedP99LatencyMs = correctedP99LatencyMs;
            this.lossRatePercent = lossRatePercent;
        }
    }

    /**
     * In-memory pub/sub implementation providing a zero-network baseline.
     */
    public static class InMemoryPubSubEventStore implements EventStore {
        private final ConcurrentLinkedQueue<EventListener<EventMessage>> listeners = new ConcurrentLinkedQueue<EventListener<EventMessage>>();
        private final Long nodeId;
        private final EventStoreMode mode;

        public InMemoryPubSubEventStore(Long nodeId) {
            this(nodeId, EventStoreMode.MULTI_CHANNEL);
        }

        public InMemoryPubSubEventStore(Long nodeId, EventStoreMode mode) {
            this.nodeId = nodeId;
            this.mode = mode != null ? mode : EventStoreMode.MULTI_CHANNEL;
        }

        @Override
        public void publish(EventType type, EventMessage message) {
            publish0(type, message);
        }

        @Override
        public void publish0(EventType type, EventMessage message) {
            message.setNodeId(nodeId);
            for (EventListener<EventMessage> listener : listeners) {
                listener.onMessage(message);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends EventMessage> void subscribe(EventType type, EventListener<T> listener, Class<T> clazz) {
            listeners.add((EventListener<EventMessage>) listener);
        }

        @Override
        public <T extends EventMessage> void subscribe0(EventType type, EventListener<T> listener, Class<T> clazz) {
            subscribe(type, listener, clazz);
        }

        @Override
        public void unsubscribe(EventType type) {
        }

        @Override
        public void unsubscribe0(EventType type) {
        }

        @Override
        public void shutdown() {
            listeners.clear();
        }

        @Override
        public void shutdown0() {
            shutdown();
        }

        @Override
        public EventStoreMode getEventStoreMode() {
            return mode;
        }
    }

    /**
     * Factory interface for initializing pub/sub store test pairs.
     */
    public interface StoreFixture extends AutoCloseable {
        String getName();
        EventStore getPublisher();
        EventStore getSubscriber();
    }

    /**
     * Runs a single iteration of a benchmark on a given StoreFixture.
     */
    private static BenchmarkResult runSingleIteration(StoreFixture fixture, int totalMessages, int warmupMessages, int threads) throws Exception {
        EventStore publisher = fixture.getPublisher();
        EventStore subscriber = fixture.getSubscriber();

        final EventType subType;
        if (EventStoreMode.SINGLE_CHANNEL.equals(subscriber.getEventStoreMode())) {
            subType = EventType.ALL_SINGLE_CHANNEL;
        } else {
            subType = EventType.DISPATCH;
        }

        final CountDownLatch warmupLatch = new CountDownLatch(warmupMessages);
        final CountDownLatch latch = new CountDownLatch(totalMessages);
        final double[] latencies = new double[totalMessages];
        final long[] receiveTimestamps = new long[totalMessages];
        final int[] receivedSeqs = new int[totalMessages];
        Arrays.fill(receivedSeqs, -1);
        final AtomicInteger receivedCount = new AtomicInteger(0);
        final AtomicBoolean inMeasurement = new AtomicBoolean(false);
        final long[] firstReceiveTime = new long[]{0};

        subscriber.subscribe(subType, (EventListener<DispatchMessage>) msg -> {
            long now = System.nanoTime();
            String data = (String) msg.getPacket().getData();
            if (!inMeasurement.get()) {
                if (data != null && data.contains("\"warmup\":true")) {
                    warmupLatch.countDown();
                }
                return;
            }

            int idx = receivedCount.getAndIncrement();
            if (idx == 0) {
                firstReceiveTime[0] = now;
            }
            if (idx < totalMessages && data != null) {
                receiveTimestamps[idx] = now;

                // Parse sequence number for coordinated omission correction
                int seqIndex = data.indexOf("\"seq\":");
                if (seqIndex != -1) {
                    int valStart = seqIndex + 6;
                    int seqEnd = data.indexOf(",", valStart);
                    if (seqEnd == -1) {
                        seqEnd = data.indexOf("}", valStart);
                    }
                    if (seqEnd != -1) {
                        try {
                            receivedSeqs[idx] = Integer.parseInt(data.substring(valStart, seqEnd).trim());
                        } catch (Exception e) {
                            receivedSeqs[idx] = -1;
                        }
                    }
                }

                // Parse timestamp for raw latency
                int tsIndex = data.indexOf("\"ts\":");
                if (tsIndex != -1) {
                    int endIndex = data.indexOf("}", tsIndex);
                    if (endIndex == -1) {
                        endIndex = data.length();
                    }
                    String tsStr = data.substring(tsIndex + 5, endIndex).trim();
                    try {
                        long sentTs = Long.parseLong(tsStr);
                        latencies[idx] = (now - sentTs) / 1_000_000.0;
                    } catch (Exception e) {
                        latencies[idx] = 0.0;
                    }
                }
            }
            latch.countDown();
        }, DispatchMessage.class);

        final EventType pubType;
        if (EventStoreMode.SINGLE_CHANNEL.equals(publisher.getEventStoreMode())) {
            pubType = EventType.ALL_SINGLE_CHANNEL;
        } else {
            pubType = EventType.DISPATCH;
        }

        // 1. Warmup
        if (warmupMessages > 0) {
            for (int i = 0; i < warmupMessages; i++) {
                Packet packet = new Packet(PacketType.MESSAGE);
                packet.setData("{\"warmup\":true,\"seq\":" + i + "}");
                publisher.publish(pubType, new DispatchMessage("warmup_room", packet, "/warmup"));
            }
            warmupLatch.await(10, TimeUnit.SECONDS);
            Thread.sleep(100);
        }

        // 2. Timed measurement
        inMeasurement.set(true);

        long publishStartTime = System.nanoTime();
        if (threads <= 1) {
            for (int i = 0; i < totalMessages; i++) {
                long now = System.nanoTime();
                Packet packet = new Packet(PacketType.MESSAGE);
                packet.setData("{\"seq\":" + i + ",\"ts\":" + now + "}");
                publisher.publish(pubType, new DispatchMessage("bench_room", packet, "/bench"));
            }
        } else {
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            int perThread = totalMessages / threads;
            int remainder = totalMessages % threads;
            for (int t = 0; t < threads; t++) {
                final int start = t * perThread;
                int endVal = start + perThread;
                if (t == threads - 1) {
                    endVal = endVal + remainder;
                }
                final int end = endVal;
                exec.submit(() -> {
                    for (int i = start; i < end; i++) {
                        long now = System.nanoTime();
                        Packet packet = new Packet(PacketType.MESSAGE);
                        packet.setData("{\"seq\":" + i + ",\"ts\":" + now + "}");
                        publisher.publish(pubType, new DispatchMessage("bench_room", packet, "/bench"));
                    }
                });
            }
            exec.shutdown();
            exec.awaitTermination(60, TimeUnit.SECONDS);
        }
        long publishEndTime = System.nanoTime();

        // Await consumption (up to 30 seconds)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        long consumeEndTime = System.nanoTime();

        double publishDurationSec = (publishEndTime - publishStartTime) / 1_000_000_000.0;
        if (publishDurationSec <= 0) {
            publishDurationSec = 0.0001;
        }

        int received = receivedCount.get();
        double lossRate = 0.0;
        if (received < totalMessages) {
            lossRate = ((totalMessages - received) * 100.0) / totalMessages;
        }

        double publishThroughput = totalMessages / publishDurationSec;

        // Consume throughput: measured from first received to last received message
        double consumeThroughput;
        if (received > 1 && firstReceiveTime[0] > 0) {
            double consumeDurationSec = (consumeEndTime - firstReceiveTime[0]) / 1_000_000_000.0;
            if (consumeDurationSec <= 0) {
                consumeDurationSec = 0.0001;
            }
            consumeThroughput = received / consumeDurationSec;
        } else {
            consumeThroughput = received > 0 ? publishThroughput : 0;
        }

        // Raw latency percentiles
        double mean = 0;
        double p50 = 0;
        double p95 = 0;
        double p99 = 0;
        int validCount = Math.min(received, totalMessages);
        if (validCount > 0) {
            double[] sorted = Arrays.copyOf(latencies, validCount);
            Arrays.sort(sorted);
            double sum = 0;
            for (double l : sorted) {
                sum += l;
            }
            mean = sum / validCount;
            p50 = sorted[Math.min((int) (validCount * 0.50), validCount - 1)];
            p95 = sorted[Math.min((int) (validCount * 0.95), validCount - 1)];
            p99 = sorted[Math.min((int) (validCount * 0.99), validCount - 1)];
        }

        // Corrected latency percentiles (coordinated omission correction)
        // Uses uniform intended inter-arrival times instead of actual send timestamps
        double corrP50 = 0;
        double corrP95 = 0;
        double corrP99 = 0;
        if (validCount > 0) {
            double intendedIntervalNanos = (publishEndTime - publishStartTime) / (double) totalMessages;
            double[] corrected = new double[validCount];
            for (int i = 0; i < validCount; i++) {
                int seq = receivedSeqs[i];
                if (seq >= 0 && seq < totalMessages && receiveTimestamps[i] > 0) {
                    long intendedSendTime = publishStartTime + (long) (seq * intendedIntervalNanos);
                    corrected[i] = (receiveTimestamps[i] - intendedSendTime) / 1_000_000.0;
                    if (corrected[i] < 0) {
                        corrected[i] = latencies[i];
                    }
                } else {
                    corrected[i] = latencies[i];
                }
            }
            Arrays.sort(corrected);
            corrP50 = corrected[Math.min((int) (validCount * 0.50), validCount - 1)];
            corrP95 = corrected[Math.min((int) (validCount * 0.95), validCount - 1)];
            corrP99 = corrected[Math.min((int) (validCount * 0.99), validCount - 1)];
        }

        subscriber.unsubscribe(subType);

        return new BenchmarkResult(
                fixture.getName(),
                totalMessages,
                threads,
                1,
                publishThroughput,
                consumeThroughput,
                mean,
                p50,
                p95,
                p99,
                corrP50,
                corrP95,
                corrP99,
                lossRate);
    }

    /**
     * Runs a benchmark with multiple iterations and returns averaged results.
     * Reduces single-run variance by running N iterations and averaging throughput/latency.
     */
    public static BenchmarkResult runBenchmark(StoreFixture fixture, int totalMessages,
                                               int warmupMessages, int threads, int iterations) throws Exception {
        List<BenchmarkResult> iterResults = new ArrayList<BenchmarkResult>();
        for (int iter = 0; iter < iterations; iter++) {
            BenchmarkResult r = runSingleIteration(fixture, totalMessages, warmupMessages, threads);
            iterResults.add(r);
            if (iterations > 1) {
                System.out.printf(Locale.US, "  Iteration %d/%d: pub=%.0f msg/s, sub=%.0f msg/s, P50=%.2fms\n",
                        iter + 1, iterations, r.publishThroughput, r.consumeThroughput, r.p50LatencyMs);
                if (iter < iterations - 1) {
                    Thread.sleep(200);
                }
            }
        }
        return averageResults(fixture.getName(), iterResults);
    }

    /**
     * Averages multiple benchmark results into a single combined result.
     */
    private static BenchmarkResult averageResults(String storeName, List<BenchmarkResult> results) {
        int n = results.size();
        if (n == 1) {
            return results.get(0);
        }
        double pubThroughput = 0;
        double conThroughput = 0;
        double meanLat = 0;
        double p50Lat = 0;
        double p95Lat = 0;
        double p99Lat = 0;
        double cp50 = 0;
        double cp95 = 0;
        double cp99 = 0;
        double loss = 0;
        for (BenchmarkResult r : results) {
            pubThroughput += r.publishThroughput;
            conThroughput += r.consumeThroughput;
            meanLat += r.meanLatencyMs;
            p50Lat += r.p50LatencyMs;
            p95Lat += r.p95LatencyMs;
            p99Lat += r.p99LatencyMs;
            cp50 += r.correctedP50LatencyMs;
            cp95 += r.correctedP95LatencyMs;
            cp99 += r.correctedP99LatencyMs;
            loss += r.lossRatePercent;
        }
        BenchmarkResult first = results.get(0);
        return new BenchmarkResult(
                storeName, first.totalMessages, first.threads, n,
                pubThroughput / n, conThroughput / n,
                meanLat / n, p50Lat / n, p95Lat / n, p99Lat / n,
                cp50 / n, cp95 / n, cp99 / n,
                loss / n);
    }

    /**
     * Prints a formatted ASCII comparison table with raw and corrected latencies.
     */
    public static void printSummaryTable(List<BenchmarkResult> results) {
        String separator = "+--------------------------------------+----------+------+-------------+-------------+------------+------------+------------+------------+------------+";
        System.out.println("\n" + separator);
        System.out.printf("| %-36s | %-8s | %-4s | %-11s | %-11s | %-10s | %-10s | %-10s | %-10s | %-10s |\n",
                "Store Backend", "Messages", "Iter", "Pub msg/s", "Sub msg/s", "Mean (ms)", "P50 (ms)", "P95 (ms)", "Corr P95", "Loss %");
        System.out.println(separator);

        for (BenchmarkResult r : results) {
            System.out.printf(Locale.US, "| %-36s | %-8d | %-4d | %-11.1f | %-11.1f | %-10.2f | %-10.2f | %-10.2f | %-10.2f | %-10.1f |\n",
                    r.storeName,
                    r.totalMessages,
                    r.iterations,
                    r.publishThroughput,
                    r.consumeThroughput,
                    r.meanLatencyMs,
                    r.p50LatencyMs,
                    r.p95LatencyMs,
                    r.correctedP95LatencyMs,
                    r.lossRatePercent);
        }
        System.out.println(separator + "\n");
    }

    // -------------------------------------------------------------------------
    // Fixture Implementations
    // -------------------------------------------------------------------------

    public static StoreFixture createMemoryFixture() {
        return createMemoryFixture(EventStoreMode.MULTI_CHANNEL);
    }

    public static StoreFixture createMemoryFixture(EventStoreMode mode) {
        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "Memory (" + modeName + ")";
        return new StoreFixture() {
            private final InMemoryPubSubEventStore store = new InMemoryPubSubEventStore(1L, mode);

            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return store;
            }

            @Override
            public EventStore getSubscriber() {
                return store;
            }

            @Override
            public void close() {
                store.shutdown();
            }
        };
    }

    public static StoreFixture createMongoFixture(CustomizedMongoContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        MongoClient mc1 = container.createClient();
        MongoClient mc2 = container.createClient();

        String dbName = "bench_mongo_" + UUID.randomUUID().toString().replace("-", "");
        MongoEventStore storePub = new MongoEventStore.Builder(mc1, dbName)
                .eventStoreMode(mode)
                .nodeId(1L)
                .collectionPrefix("bench_")
                .build();
        MongoEventStore storeSub = new MongoEventStore.Builder(mc2, dbName)
                .eventStoreMode(mode)
                .nodeId(2L)
                .collectionPrefix("bench_")
                .build();

        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "MongoDB (" + modeName + ")";

        return new StoreFixture() {
            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return storePub;
            }

            @Override
            public EventStore getSubscriber() {
                return storeSub;
            }

            @Override
            public void close() {
                try {
                    storePub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    storeSub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    mc1.close();
                } catch (Exception ignored) {
                }
                try {
                    mc2.close();
                } catch (Exception ignored) {
                }
            }
        };
    }

    public static StoreFixture createRedisPubSubFixture(CustomizedRedisContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + container.getHost() + ":" + container.getRedisPort());

        RedissonClient redissonPub = Redisson.create(config);
        RedissonClient redissonSub = Redisson.create(config);

        RedisPubSubEventStore storePub = new RedisPubSubEventStore(redissonPub, redissonPub, mode, 1L);
        RedisPubSubEventStore storeSub = new RedisPubSubEventStore(redissonSub, redissonSub, mode, 2L);

        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "Redis (Pub/Sub - " + modeName + ")";

        return new StoreFixture() {
            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return storePub;
            }

            @Override
            public EventStore getSubscriber() {
                return storeSub;
            }

            @Override
            public void close() {
                try {
                    storePub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    storeSub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    redissonPub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    redissonSub.shutdown();
                } catch (Exception ignored) {
                }
            }
        };
    }

    public static StoreFixture createRedisStreamFixture(CustomizedRedisContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + container.getHost() + ":" + container.getRedisPort());

        RedissonClient redissonPub = Redisson.create(config);
        RedissonClient redissonSub = Redisson.create(config);

        String streamPrefix = "bench_stream_" + (EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "s_" : "m_") + UUID.randomUUID().toString().substring(0, 8) + "_";
        RedisStreamEventStore storePub = new RedisStreamEventStore(redissonPub, redissonPub, 1L, mode, streamPrefix, 10000);
        RedisStreamEventStore storeSub = new RedisStreamEventStore(redissonSub, redissonSub, 2L, mode, streamPrefix, 10000);

        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "Redis (Stream - " + modeName + ")";

        return new StoreFixture() {
            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return storePub;
            }

            @Override
            public EventStore getSubscriber() {
                return storeSub;
            }

            @Override
            public void close() {
                try {
                    storePub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    storeSub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    redissonPub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    redissonSub.shutdown();
                } catch (Exception ignored) {
                }
            }
        };
    }

    public static StoreFixture createRedisReliableFixture(CustomizedRedisContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + container.getHost() + ":" + container.getRedisPort());

        RedissonClient redissonPub = Redisson.create(config);
        RedissonClient redissonSub = Redisson.create(config);

        String streamPrefix = "bench_rel_" + (EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "s_" : "m_") + UUID.randomUUID().toString().substring(0, 8) + "_";
        RedisPubSubReliableEventStore storePub = new RedisPubSubReliableEventStore(redissonPub, redissonPub, 1L, mode, streamPrefix, 10000, Duration.ofSeconds(10));
        RedisPubSubReliableEventStore storeSub = new RedisPubSubReliableEventStore(redissonSub, redissonSub, 2L, mode, streamPrefix, 10000, Duration.ofSeconds(10));

        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "Redis (Reliable - " + modeName + ")";

        return new StoreFixture() {
            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return storePub;
            }

            @Override
            public EventStore getSubscriber() {
                return storeSub;
            }

            @Override
            public void close() {
                try {
                    storePub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    storeSub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    redissonPub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    redissonSub.shutdown();
                } catch (Exception ignored) {
                }
            }
        };
    }

    public static StoreFixture createNatsFixture(CustomizedNatsContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        Options options = new Options.Builder().server(container.getNatsUrl()).build();
        Connection ncPub = Nats.connect(options);
        Connection ncSub = Nats.connect(options);

        NatsEventStore storePub = new NatsEventStore(ncPub, mode, 1L);
        NatsEventStore storeSub = new NatsEventStore(ncSub, mode, 2L);

        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "NATS (" + modeName + ")";

        return new StoreFixture() {
            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return storePub;
            }

            @Override
            public EventStore getSubscriber() {
                return storeSub;
            }

            @Override
            public void close() {
                try {
                    storePub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    storeSub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    ncPub.close();
                } catch (Exception ignored) {
                }
                try {
                    ncSub.close();
                } catch (Exception ignored) {
                }
            }
        };
    }

    public static StoreFixture createKafkaFixture(CustomizedKafkaContainer container, EventStoreMode mode) throws Exception {
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

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "bench-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventMessageDeserializer.class);

        KafkaProducer<String, EventMessage> producer = new KafkaProducer<String, EventMessage>(producerProps);
        String topicPrefix = "bench_kafka_" + (EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "s_" : "m_") + UUID.randomUUID().toString().substring(0, 6) + "_";
        String initTopic = topicPrefix + (EventStoreMode.SINGLE_CHANNEL.equals(mode) ? EventType.ALL_SINGLE_CHANNEL.name() : EventType.DISPATCH.name());
        try {
            Packet initPacket = new Packet(PacketType.MESSAGE);
            initPacket.setData("{\"init\":true}");
            producer.send(new ProducerRecord<String, EventMessage>(initTopic, new DispatchMessage("init", initPacket, "/"))).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }

        KafkaEventStore storePub = new KafkaEventStore(producer, consumerProps, 1L, mode, topicPrefix);
        KafkaEventStore storeSub = new KafkaEventStore(producer, consumerProps, 2L, mode, topicPrefix);

        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "Apache Kafka (" + modeName + ")";

        return new StoreFixture() {
            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return storePub;
            }

            @Override
            public EventStore getSubscriber() {
                return storeSub;
            }

            @Override
            public void close() {
                try {
                    storePub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    storeSub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    producer.close();
                } catch (Exception ignored) {
                }
            }
        };
    }

    public static StoreFixture createHazelcastPubSubFixture(CustomizedHazelcastContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        ClientConfig config = new ClientConfig();
        config.setClusterName(container.getClusterName());
        config.getNetworkConfig().addAddress(container.getHazelcastAddress());

        HazelcastInstance hz1 = HazelcastClient.newHazelcastClient(config);
        HazelcastInstance hz2 = HazelcastClient.newHazelcastClient(config);

        String prefix = "bench_hz_" + (EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "s_" : "m_") + UUID.randomUUID().toString().substring(0, 6) + "_";
        HazelcastPubSubEventStore storePub = new HazelcastPubSubEventStore(hz1, hz1, 1L, mode, prefix);
        HazelcastPubSubEventStore storeSub = new HazelcastPubSubEventStore(hz2, hz2, 2L, mode, prefix);

        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "Hazelcast (Pub/Sub - " + modeName + ")";

        return new StoreFixture() {
            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return storePub;
            }

            @Override
            public EventStore getSubscriber() {
                return storeSub;
            }

            @Override
            public void close() {
                try {
                    storePub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    storeSub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    hz1.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    hz2.shutdown();
                } catch (Exception ignored) {
                }
            }
        };
    }

    public static StoreFixture createHazelcastRingBufferFixture(CustomizedHazelcastContainer container, EventStoreMode mode) throws Exception {
        if (!container.isRunning()) {
            container.start();
        }
        ClientConfig config = new ClientConfig();
        config.setClusterName(container.getClusterName());
        config.getNetworkConfig().setSmartRouting(false).addAddress(container.getHazelcastAddress());

        HazelcastInstance hz1 = HazelcastClient.newHazelcastClient(config);
        HazelcastInstance hz2 = HazelcastClient.newHazelcastClient(config);

        String prefix = "bench_hzrb_" + (EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "s_" : "m_") + UUID.randomUUID().toString().substring(0, 6) + "_";
        HazelcastPubSubRingBufferEventStore storePub = new HazelcastPubSubRingBufferEventStore(hz1, hz1, 1L, mode, prefix);
        HazelcastPubSubRingBufferEventStore storeSub = new HazelcastPubSubRingBufferEventStore(hz2, hz2, 2L, mode, prefix);

        String modeName = EventStoreMode.SINGLE_CHANNEL.equals(mode) ? "Single Channel" : "Multi Channel";
        final String fixtureName = "Hazelcast (RingBuffer - " + modeName + ")";

        return new StoreFixture() {
            @Override
            public String getName() {
                return fixtureName;
            }

            @Override
            public EventStore getPublisher() {
                return storePub;
            }

            @Override
            public EventStore getSubscriber() {
                return storeSub;
            }

            @Override
            public void close() {
                try {
                    storePub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    storeSub.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    hz1.shutdown();
                } catch (Exception ignored) {
                }
                try {
                    hz2.shutdown();
                } catch (Exception ignored) {
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // Main CLI Entry Point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        int messages = DEFAULT_MESSAGES;
        int warmup = DEFAULT_WARMUP;
        int threads = DEFAULT_THREADS;
        int iterations = DEFAULT_ITERATIONS;
        String modeArg = "both";
        List<String> targetStores = new ArrayList<String>();

        for (int i = 0; i < args.length; i++) {
            if ("--messages".equals(args[i]) || "-m".equals(args[i])) {
                if (i + 1 < args.length) {
                    messages = Integer.parseInt(args[++i]);
                }
            } else if ("--threads".equals(args[i]) || "-t".equals(args[i])) {
                if (i + 1 < args.length) {
                    threads = Integer.parseInt(args[++i]);
                }
            } else if ("--warmup".equals(args[i]) || "-w".equals(args[i])) {
                if (i + 1 < args.length) {
                    warmup = Integer.parseInt(args[++i]);
                }
            } else if ("--iterations".equals(args[i]) || "-i".equals(args[i])) {
                if (i + 1 < args.length) {
                    iterations = Integer.parseInt(args[++i]);
                }
            } else if ("--mode".equals(args[i]) || "-M".equals(args[i])) {
                if (i + 1 < args.length) {
                    modeArg = args[++i].toLowerCase(Locale.ROOT);
                }
            } else if ("--stores".equals(args[i]) || "-s".equals(args[i])) {
                if (i + 1 < args.length) {
                    String[] parts = args[++i].split(",");
                    for (String p : parts) {
                        targetStores.add(p.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        if (targetStores.isEmpty()) {
            targetStores.add("all");
        }

        List<EventStoreMode> modes = new ArrayList<EventStoreMode>();
        if ("single".equals(modeArg)) {
            modes.add(EventStoreMode.SINGLE_CHANNEL);
        } else if ("multi".equals(modeArg)) {
            modes.add(EventStoreMode.MULTI_CHANNEL);
        } else {
            // "both" or default
            modes.add(EventStoreMode.MULTI_CHANNEL);
            modes.add(EventStoreMode.SINGLE_CHANNEL);
        }

        System.out.println("==========================================================================");
        System.out.println("          Socketio4j Pub/Sub Store Throughput & Latency Benchmark         ");
        System.out.println("==========================================================================");
        System.out.println("Config: messages=" + messages + ", warmup=" + warmup + ", threads=" + threads + ", iterations=" + iterations + ", mode=" + modeArg + ", stores=" + targetStores);

        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();

        // Shared containers
        CustomizedMongoContainer mongoContainer = null;
        CustomizedRedisContainer redisContainer = null;
        CustomizedNatsContainer natsContainer = null;
        CustomizedKafkaContainer kafkaContainer = null;
        CustomizedHazelcastContainer hazelcastContainer = null;

        try {
            boolean runAll = targetStores.contains("all");

            if (runAll || targetStores.contains("memory")) {
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking Memory (" + mode + ")...");
                    try (StoreFixture f = createMemoryFixture(mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

            if (runAll || targetStores.contains("mongo") || targetStores.contains("mongodb")) {
                mongoContainer = new CustomizedMongoContainer();
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking MongoDB (" + mode + ")...");
                    try (StoreFixture f = createMongoFixture(mongoContainer, mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

            if (runAll || targetStores.contains("redis") || targetStores.contains("redis_pubsub")) {
                if (redisContainer == null) {
                    redisContainer = new CustomizedRedisContainer();
                }
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking Redis (Pub/Sub - " + mode + ")...");
                    try (StoreFixture f = createRedisPubSubFixture(redisContainer, mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

            if (runAll || targetStores.contains("redis_stream")) {
                if (redisContainer == null) {
                    redisContainer = new CustomizedRedisContainer();
                }
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking Redis (Stream - " + mode + ")...");
                    try (StoreFixture f = createRedisStreamFixture(redisContainer, mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

            if (runAll || targetStores.contains("redis_reliable")) {
                if (redisContainer == null) {
                    redisContainer = new CustomizedRedisContainer();
                }
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking Redis (Reliable Topic - " + mode + ")...");
                    try (StoreFixture f = createRedisReliableFixture(redisContainer, mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

            if (runAll || targetStores.contains("nats")) {
                natsContainer = new CustomizedNatsContainer();
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking NATS (" + mode + ")...");
                    try (StoreFixture f = createNatsFixture(natsContainer, mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

            if (runAll || targetStores.contains("kafka")) {
                kafkaContainer = new CustomizedKafkaContainer();
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking Kafka (" + mode + ")...");
                    try (StoreFixture f = createKafkaFixture(kafkaContainer, mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

            if (runAll || targetStores.contains("hazelcast") || targetStores.contains("hazelcast_pubsub")) {
                if (hazelcastContainer == null) {
                    hazelcastContainer = new CustomizedHazelcastContainer().withReuse(false);
                }
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking Hazelcast (Pub/Sub - " + mode + ")...");
                    try (StoreFixture f = createHazelcastPubSubFixture(hazelcastContainer, mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

            if (runAll || targetStores.contains("hazelcast") || targetStores.contains("hazelcast_ringbuffer")) {
                if (hazelcastContainer == null) {
                    hazelcastContainer = new CustomizedHazelcastContainer().withReuse(false);
                }
                for (EventStoreMode mode : modes) {
                    System.out.println("\nBenchmarking Hazelcast (RingBuffer - " + mode + ")...");
                    try (StoreFixture f = createHazelcastRingBufferFixture(hazelcastContainer, mode)) {
                        results.add(runBenchmark(f, messages, warmup, threads, iterations));
                    }
                }
            }

        } catch (Exception e) {
            log.error("Benchmark execution failed", e);
        } finally {
            if (mongoContainer != null && mongoContainer.isRunning()) {
                mongoContainer.stop();
            }
            if (redisContainer != null && redisContainer.isRunning()) {
                redisContainer.stop();
            }
            if (natsContainer != null && natsContainer.isRunning()) {
                natsContainer.stop();
            }
            if (kafkaContainer != null && kafkaContainer.isRunning()) {
                kafkaContainer.stop();
            }
            if (hazelcastContainer != null && hazelcastContainer.isRunning()) {
                hazelcastContainer.stop();
            }
        }

        printSummaryTable(results);
    }

    /**
     * Default JUnit smoke test to ensure benchmark code functions as part of test suites.
     */
    @Test
    public void testSmokeBenchmark() throws Exception {
        try (StoreFixture f = createMemoryFixture()) {
            BenchmarkResult res = runBenchmark(f, 500, 50, 1, 1);
            org.junit.jupiter.api.Assertions.assertEquals(0.0, res.lossRatePercent, 0.01);
            org.junit.jupiter.api.Assertions.assertTrue(res.publishThroughput > 0);
        }
    }
}
