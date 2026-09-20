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
package com.socketio4j.socketio.store.event;

/**
 * Defines the pub/sub channel routing and topology strategy used by {@link EventStore}
 * implementations to distribute cluster events across nodes.
 *
 * <p>In a distributed Socket.IO cluster, multiple server nodes must synchronize events
 * including socket lifecycle ({@code CONNECT}, {@code DISCONNECT}), room membership
 * ({@code JOIN}, {@code LEAVE}, {@code BULK_JOIN}, {@code BULK_LEAVE}), and broadcast
 * messages ({@code DISPATCH}). The choice of {@link EventStoreMode} governs how these
 * events are multiplexed or partitioned onto underlying broker channels, streams, or topics.
 *
 * <h3>Mode Comparison Matrix</h3>
 * <table border="1" cellpadding="5">
 *   <tr>
 *     <th>Mode</th>
 *     <th>Broker Channels / Topics</th>
 *     <th>Ordering Guarantee</th>
 *     <th>Storm Isolation</th>
 *     <th>Deserialization Waste</th>
 *     <th>Primary Use Case</th>
 *   </tr>
 *   <tr>
 *     <td>{@link #SINGLE_CHANNEL}</td>
 *     <td>1 (all events multiplexed)</td>
 *     <td>Global Total Order (Strict FIFO across all types)</td>
 *     <td>&#10060; None (HoL blocking on storms)</td>
 *     <td>High (65%–90% noise waste for selective nodes)</td>
 *     <td>Low-concurrency, simple setups requiring global total order</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #MULTI_CHANNEL}</td>
 *     <td>7 (1 per {@link EventType})</td>
 *     <td>Per-Type FIFO only (&#10060; <b>NO cross-type causal order</b>)</td>
 *     <td>&#9989; 100% Isolated</td>
 *     <td>&#9989; 0% Noise Waste</td>
 *     <td>Decoupled specialized microservices (presence vs chat worker)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #PARTITIONED_CHANNEL}</td>
 *     <td>$N$ Room Partitions + Lifecycle Lane</td>
 *     <td>&#9989; <b>Strict Per-Room Causal FIFO</b></td>
 *     <td>&#9989; 100% Isolated</td>
 *     <td>&#9989; 0% Noise Waste</td>
 *     <td>High-scale production chat, gaming, live collaboration</td>
 *   </tr>
 * </table>
 *
 * @see EventStore
 * @see EventType
 */
public enum EventStoreMode {

    /**
     * Single Channel Mode: Multiplexes all 7 cluster event types onto a single, global broker
     * topic or stream (e.g. {@code ALL_SINGLE_CHANNEL}).
     *
     * <h4>Ordering Guarantees</h4>
     * <ul>
     *   <li><b>Global Total Order:</b> Because all events are appended to a single FIFO log,
     *       events are consumed in the exact order they were published across all event types.
     *       A {@code JOIN} is guaranteed to be processed before a subsequent {@code DISPATCH}.</li>
     * </ul>
     *
     * <h4>Use Cases</h4>
     * <ul>
     *   <li>Small-to-medium clusters with low connection churn and low room count.</li>
     *   <li>Applications requiring a single, strictly linearizable timeline across all event types.</li>
     * </ul>
     *
     * <h4>Warnings &amp; Failure Modes</h4>
     * <ul>
     *   <li><b>Head-of-Line (HoL) Blocking:</b> During reconnection storms (e.g. 50,000 clients
     *       reconnecting simultaneously after a network flap), thousands of {@code CONNECT} events
     *       flood the single channel, stalling chat broadcasts and causing latency spikes (10x+).</li>
     *   <li><b>High Deserialization Noise Waste:</b> Specialized subscriber nodes (e.g. chat-only
     *       nodes) must deserialize and discard 65%–90.9% of incoming traffic representing
     *       presence/connection churn they do not care about.</li>
     *   <li><b>Single-Channel Lock Contention:</b> The single broker topic/stream becomes a centralized
     *       throughput bottleneck that cannot scale horizontally across CPU cores.</li>
     * </ul>
     */
    SINGLE_CHANNEL,

    /**
     * Multi Channel Mode: Allocates an independent broker topic or stream per {@link EventType}
     * (e.g. 7 separate channels: {@code CONNECT}, {@code DISCONNECT}, {@code JOIN}, {@code BULK_JOIN},
     * {@code LEAVE}, {@code BULK_LEAVE}, {@code DISPATCH}).
     *
     * <h4>Ordering Guarantees</h4>
     * <ul>
     *   <li><b>Per-Type FIFO:</b> Events within the same event type (e.g. {@code DISPATCH} to {@code DISPATCH})
     *       maintain FIFO order.</li>
     *   <li>&#10060; <b>NO Cross-Type Causal Ordering:</b> Because different event types travel through
     *       independent streams and are read by independent consumer threads/pollers, there is
     *       <b>no ordering guarantee between different event types</b>.</li>
     * </ul>
     *
     * <h4>Use Cases</h4>
     * <ul>
     *   <li><b>Decoupled Microservices:</b> Dedicated backend services that only listen to a specific subset
     *       of events (e.g. a Presence Service that only subscribes to {@code CONNECT}/{@code DISCONNECT}
     *       and never touches chat, or an Analytics Service that only listens to {@code DISPATCH}).</li>
     *   <li>Static rooms where clients are pre-assigned at startup and never dynamically join/leave
     *       during active broadcasting.</li>
     * </ul>
     *
     * <h4>&#9888; CRITICAL WARNINGS FOR PRODUCTION</h4>
     * <ul>
     *   <li><b>The "Ghost Message" Race Condition:</b> When a client dynamically joins a room and the server
     *       immediately dispatches a welcome/initialization message, {@code JOIN} and {@code DISPATCH}
     *       race across separate channels. If the subscriber's {@code DISPATCH} thread executes before the
     *       {@code JOIN} thread, the subscriber searches its local room registry, fails to find the client,
     *       and <b>silently drops the message</b>. The client permanently misses their first message.</li>
     *   <li><b>The Data Leak Race Condition:</b> If a client leaves a private room ({@code LEAVE}) and a
     *       confidential message is dispatched shortly after, a faster {@code DISPATCH} consumer thread
     *       will deliver the confidential message to the departed client before the {@code LEAVE} is processed.</li>
     *   <li><b>7x Resource Overhead:</b> Each cluster node maintains 7 independent consumer loops, streams,
     *       and thread schedulers, multiplying broker connections and polling load by 7x.</li>
     * </ul>
     */
    MULTI_CHANNEL,

    /**
     * Partitioned Channel Mode: Employs a room-keyed partitioning architecture that delivers the
     * optimal balance of <b>horizontal scalability</b>, <b>reconnect storm isolation</b>, and
     * <b>strict causal FIFO ordering</b>.
     *
     * <h4>Architecture</h4>
     * <ul>
     *   <li><b>$N$ Room Partitions:</b> Room-scoped events ({@code JOIN}, {@code LEAVE}, {@code DISPATCH})
     *       are routed to a specific partition based on their {@code roomId}
     *       ({@code partition = hash(roomId) % N}). All events for the same room land on the
     *       exact same broker partition / stream in strict FIFO order.</li>
     *   <li><b>Dedicated Lifecycle Lane:</b> Transport-level socket events ({@code CONNECT}, {@code DISCONNECT})
     *       carry no {@code roomId} and route to a dedicated control partition / lifecycle stream, completely
     *       segregated from conversational room traffic.</li>
     * </ul>
     *
     * <h4>Ordering Guarantees</h4>
     * <ul>
     *   <li>&#9989; <b>Strict Per-Room Causal FIFO:</b> Within any individual room, {@code JOIN},
     *       {@code DISPATCH}, and {@code LEAVE} are guaranteed to be consumed in linear chronological
     *       sequence. A client is 100% guaranteed to be added to a room before subsequent messages
     *       in that room are dispatched. The "ghost message" race condition is physically impossible.</li>
     *   <li><b>Concurrent Cross-Room Scaling:</b> Events for Room A and Room B run completely in parallel
     *       across different partitions without cross-room lock contention or latency interference.</li>
     * </ul>
     *
     * <h4>Use Cases</h4>
     * <ul>
     *   <li>High-throughput, large-scale production Socket.IO clusters with dynamic room membership
     *       (chat applications, live multiplayer gaming, trading dashboards, collaborative canvases).</li>
     *   <li>Environments requiring resilience against reconnect storms without degrading active chat latency.</li>
     * </ul>
     *
     * <h4>Operational Considerations</h4>
     * <ul>
     *   <li><b>Kafka:</b> Natively maps to topic partitions using {@code msg.getPartitionKey()} (the {@code roomId})
     *       as the {@code ProducerRecord} key, utilizing Kafka's Murmur2 partitioner for automatic
     *       in-order distribution.</li>
     *   <li><b>Redis Stream / PubSub:</b> Shards by stream/channel name (e.g. {@code streamPrefix + "room_" + partition}),
     *       or utilizes in-memory striped worker executors on subscribers to maintain serial execution per room.</li>
     *   <li><b>Bulk Operations:</b> {@code BULK_JOIN} and {@code BULK_LEAVE} operations with multiple rooms
     *       route by primary room key or should be published per-room to ensure each room's partition
     *       receives its update in order.</li>
     * </ul>
     */
    PARTITIONED_CHANNEL

}
