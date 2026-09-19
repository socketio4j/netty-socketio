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
package com.socketio4j.socketio.store.mongo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoCommandException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.ChangeStreamPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventMessageJsonSupport;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.PublishMode;

/**
 * MongoDB Change Streams based EventStore.
 * <p>
 * Uses MongoDB Change Streams to watch for inserts on a collection and deliver
 * events to subscribers. Each event type maps to its own collection
 * (MULTI_CHANNEL)
 * or all events go into one collection (SINGLE_CHANNEL).
 * <p>
 * Built on the reactive streams driver: fully non-blocking and asynchronous.
 * Both {@code publish0} and {@code subscribe0} (and {@code subscribeAsync})
 * are entirely non-blocking. TTL index creation, reconciliation, and change stream
 * initialization run asynchronously via Reactive Streams subscribers without blocking
 * the calling threads.
 * <p>
 * A TTL index is created on each collection to automatically expire documents
 * after a configurable retention period (default 60 seconds), preventing
 * unbounded data growth.
 * <p>
 * Requires a MongoDB replica set (standalone does not support change streams).
 */
public class MongoEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(MongoEventStore.class);

    private static final String DEFAULT_COLLECTION_PREFIX = "socketio_events_";
    private static final long DEFAULT_TTL_SECONDS = 60;

    /** Initial delay before reopening a change stream that ended or failed. */
    private static final long INITIAL_REOPEN_DELAY_MILLIS = 500;

    /** Maximum backoff delay for reopening a change stream. */
    private static final long MAX_REOPEN_DELAY_MILLIS = 8000;

    /**
     * How long {@link #shutdown0()} waits for cancelled change streams to actually
     * end.
     * Cancelling is asynchronous, and the driver may still have a {@code getMore}
     * in flight;
     * bounded by {@link #CANCEL_GRACE_MILLIS} and returns early once cancelled.
     */
    private static final long CANCEL_GRACE_MILLIS = 1000;

    /**
     * MongoDB error code raised when an index exists with the same key but
     * different options.
     */
    private static final int INDEX_OPTIONS_CONFLICT = 85;

    /**
     * Shared mapper: keeps byte[] payloads lossless, as the Kafka and NATS stores
     * do.
     */
    private static final ObjectMapper MAPPER = EventMessageJsonSupport.createObjectMapper();

    private final MongoDatabase database;
    private final Long nodeId;
    private final EventStoreMode eventStoreMode;
    private final String collectionPrefix;
    private final long ttlSeconds;
    private final WriteConcern writeConcern;
    private final ReadPreference readPreference;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final ConcurrentMap<String, MongoCollection<Document>> collectionCache = new ConcurrentHashMap<>();

    private final Set<String> indexedCollections = ConcurrentHashMap.newKeySet();

    private final ConcurrentMap<EventType, Queue<WatcherHandle>> watchers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService watcherExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("socketio-mongo-watcher-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    /**
     * Creates a new MongoEventStore.
     *
     * @param mongoClient      shared MongoDB client
     * @param databaseName     database to use for event collections
     * @param eventStoreMode   SINGLE_CHANNEL or MULTI_CHANNEL (defaults to
     *                         MULTI_CHANNEL)
     * @param nodeId           node identifier used to ignore self-published events
     * @param collectionPrefix prefix for collection names (defaults to
     *                         "socketio_events_")
     * @param ttlSeconds       TTL in seconds for automatic document expiry
     *                         (defaults to 60)
     */
    public MongoEventStore(@NotNull MongoClient mongoClient,
            @NotNull String databaseName,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable Long nodeId,
            @Nullable String collectionPrefix,
            long ttlSeconds) {
        this(mongoClient, databaseName, eventStoreMode, nodeId, collectionPrefix, ttlSeconds, null, null);
    }

    /**
     * Creates a new MongoEventStore with custom write concern and read preference.
     *
     * @param mongoClient      shared MongoDB client
     * @param databaseName     database to use for event collections
     * @param eventStoreMode   SINGLE_CHANNEL or MULTI_CHANNEL (defaults to
     *                         MULTI_CHANNEL)
     * @param nodeId           node identifier used to ignore self-published events
     * @param collectionPrefix prefix for collection names (defaults to
     *                         "socketio_events_")
     * @param ttlSeconds       TTL in seconds for automatic document expiry
     *                         (defaults to 60)
     * @param writeConcern     optional write concern for published events
     * @param readPreference   optional read preference for change stream operations
     */
    public MongoEventStore(@NotNull MongoClient mongoClient,
            @NotNull String databaseName,
            @Nullable EventStoreMode eventStoreMode,
            @Nullable Long nodeId,
            @Nullable String collectionPrefix,
            long ttlSeconds,
            @Nullable WriteConcern writeConcern,
            @Nullable ReadPreference readPreference) {
        Objects.requireNonNull(mongoClient, "mongoClient");
        this.database = mongoClient.getDatabase(
                Objects.requireNonNull(databaseName, "databaseName"));

        if (nodeId != null) {
            this.nodeId = nodeId;
        } else {
            this.nodeId = getNodeId();
        }
        if (eventStoreMode != null) {
            this.eventStoreMode = eventStoreMode;
        } else {
            this.eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        }
        if (collectionPrefix != null) {
            this.collectionPrefix = collectionPrefix;
        } else {
            this.collectionPrefix = DEFAULT_COLLECTION_PREFIX;
        }
        if (ttlSeconds > 0) {
            this.ttlSeconds = ttlSeconds;
        } else {
            this.ttlSeconds = DEFAULT_TTL_SECONDS;
        }
        this.writeConcern = writeConcern;
        this.readPreference = readPreference;
    }

    @Override
    public EventStoreMode getEventStoreMode() {
        return eventStoreMode;
    }

    @Override
    public EventStoreType getEventStoreType() {
        return EventStoreType.PUBSUB;
    }

    @Override
    public PublishMode getPublishMode() {
        return PublishMode.UNRELIABLE;
    }

    public String getCollectionPrefix() {
        return collectionPrefix;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public WriteConcern getWriteConcern() {
        return writeConcern;
    }

    public ReadPreference getReadPreference() {
        return readPreference;
    }

    @Override
    public void publish0(EventType type, EventMessage msg) {
        if (!running.get()) {
            log.warn("MongoEventStore is shut down; ignoring publish of {}", type);
            return;
        }

        msg.setNodeId(nodeId);

        String collectionName = getCollectionName(type);
        String payload;
        try {
            payload = MAPPER.writeValueAsString(msg);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize EventMessage", e);
        }
        Document doc = new Document()
                .append("nodeId", nodeId)
                .append("eventType", type.name())
                .append("createdAt", new Date())
                .append("payload", payload);

        // Fire and forget: this runs on the Netty event loop, so the insert is handed
        // to
        // the driver and its outcome only logged, the model KafkaEventStore.publish0
        // uses.
        getCollection(collectionName)
                .insertOne(doc)
                .subscribe(new PublishSubscriber(type, collectionName));
    }

    @Override
    public <T extends EventMessage> void subscribe0(
            EventType type,
            final EventListener<T> listener,
            Class<T> clazz) {
        if (!running.get()) {
            throw new IllegalStateException("MongoEventStore has been shut down");
        }
        subscribeAsync(type, listener, clazz);
    }

    /**
     * Asynchronously subscribes to the specified event type without blocking.
     * <p>
     * Initiates asynchronous TTL index creation, captures the cluster operation time
     * asynchronously, and opens the change stream cursor.
     *
     * @param type     event type to subscribe to
     * @param listener listener for received messages
     * @param clazz    event message class
     * @param <T>      message type
     * @return a {@link CompletableFuture} that completes when the change stream subscription is established
     */
    public <T extends EventMessage> CompletableFuture<Void> subscribeAsync(
            EventType type,
            final EventListener<T> listener,
            Class<T> clazz) {

        if (!running.get()) {
            throw new IllegalStateException("MongoEventStore has been shut down");
        }

        Objects.requireNonNull(listener, "listener cannot be null");
        Objects.requireNonNull(clazz, "clazz cannot be null");

        validateSubscribe(type);

        String collectionName = getCollectionName(type);
        MongoCollection<Document> collection = getCollection(collectionName);

        if (indexedCollections.add(collectionName)) {
            ensureTtlIndexAsync(collection, collectionName);
        }

        WatcherHandle handle = new WatcherHandle(null);

        // Register before opening the stream in a single atomic map operation
        watchers.compute(type, (k, queue) -> {
            Queue<WatcherHandle> q = queue;
            if (q == null) {
                q = new ConcurrentLinkedQueue<>();
            }
            q.add(handle);
            return q;
        });

        CompletableFuture<Void> subscriptionFuture = new CompletableFuture<>();
        startWatcherAsync(collection, type, handle, listener, clazz, subscriptionFuture);
        return subscriptionFuture;
    }

    /**
     * Builds the server-side Change Stream aggregation pipeline.
     * <p>
     * Filters for inserts only (ignoring TTL deletes) and drops self-published
     * events on the MongoDB server before network transmission.
     */
    private List<Bson> createPipeline() {
        return Collections.singletonList(
                Aggregates.match(Filters.and(
                        Filters.eq("operationType", "insert"),
                        Filters.ne("fullDocument.nodeId", nodeId))));
    }

    /**
     * Starts the watcher asynchronously by retrieving the server's operation time via ping
     * and opening the change stream at that point without blocking the calling thread.
     */
    private <T extends EventMessage> void startWatcherAsync(
            MongoCollection<Document> collection,
            EventType type,
            WatcherHandle handle,
            EventListener<T> listener,
            Class<T> clazz,
            CompletableFuture<Void> subscriptionFuture) {

        if (handle.stopped.get()) {
            subscriptionFuture.complete(null);
            return;
        }

        database.runCommand(new Document("ping", 1)).subscribe(new Subscriber<Document>() {
            private BsonTimestamp opTime;

            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(Document doc) {
                if (doc != null) {
                    Object operationTime = doc.get("operationTime");
                    if (operationTime instanceof BsonTimestamp) {
                        opTime = (BsonTimestamp) operationTime;
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                log.debug("Ping command failed to retrieve operationTime on {}, starting watch with server default: {}",
                        collection.getNamespace(), t.getMessage());
                if (!handle.stopped.get()) {
                    watch(collection, type, handle, listener, clazz, subscriptionFuture);
                } else {
                    subscriptionFuture.complete(null);
                }
            }

            @Override
            public void onComplete() {
                if (handle.stopped.get()) {
                    subscriptionFuture.complete(null);
                    return;
                }
                if (opTime != null) {
                    handle.setStartAt(opTime);
                }
                watch(collection, type, handle, listener, clazz, subscriptionFuture);
            }
        });
    }

    /**
     * Opens the change stream, resuming after the last delivered event when this is a
     * reopen, and otherwise starting at the operation time captured while subscribing.
     */
    private <T extends EventMessage> void watch(MongoCollection<Document> collection,
            EventType type,
            WatcherHandle handle,
            EventListener<T> listener,
            Class<T> clazz,
            @Nullable CompletableFuture<Void> subscriptionFuture) {
        if (handle.stopped.get()) {
            if (subscriptionFuture != null && !subscriptionFuture.isDone()) {
                subscriptionFuture.complete(null);
            }
            return;
        }

        ChangeStreamPublisher<Document> stream = collection.watch(createPipeline());
        BsonDocument resumeToken = handle.resumeToken();
        if (resumeToken != null) {
            stream = stream.resumeAfter(resumeToken);
        } else if (handle.startAt() != null) {
            stream = stream.startAtOperationTime(handle.startAt());
        }

        stream.subscribe(new ChangeSubscriber<T>(collection, type, handle, listener, clazz, subscriptionFuture));
    }

    @Override
    public void unsubscribe0(EventType type) {
        Queue<WatcherHandle> handles = watchers.remove(type);
        if (handles == null || handles.isEmpty()) {
            return;
        }
        for (WatcherHandle handle : handles) {
            handle.stop();
        }
    }

    @Override
    public void shutdown0() {
        shutdownAsync().join();
    }

    /**
     * Asynchronously shuts down the Mongo event store.
     *
     * @return a {@link CompletableFuture} completing when all watchers are cancelled and executor is stopped
     */
    public CompletableFuture<Void> shutdownAsync() {
        if (!running.compareAndSet(true, false)) {
            return CompletableFuture.completedFuture(null);
        }

        List<WatcherHandle> cancelled = new ArrayList<WatcherHandle>();
        for (Queue<WatcherHandle> handles : watchers.values()) {
            cancelled.addAll(handles);
        }

        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
        watchers.clear();

        return CompletableFuture.runAsync(() -> {
            awaitCancellation(cancelled);

            watcherExecutor.shutdown();
            try {
                if (!watcherExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    watcherExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                watcherExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Waits for the change streams cancelled by {@link #shutdown0()} to end, so that a
     * caller closing its {@code MongoClient} right afterwards does not interrupt them
     * mid-flight.
     */
    private void awaitCancellation(List<WatcherHandle> handles) {
        if (handles.isEmpty()) {
            return;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CANCEL_GRACE_MILLIS);
        try {
            for (WatcherHandle handle : handles) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                handle.awaitTermination(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Creates or reconciles the TTL index used to expire published events asynchronously.
     * <p>
     * {@code createIndex} creates the collection when it does not exist yet and is a
     * no-op for an identical index. If an index already exists with a different retention period,
     * it catches {@code INDEX_OPTIONS_CONFLICT} (error code 85) and asynchronously reconciles
     * the TTL using {@code collMod}.
     */
    private void ensureTtlIndexAsync(MongoCollection<Document> collection, String collectionName) {
        collection.createIndex(
                Indexes.ascending("createdAt"),
                new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS))
            .subscribe(new Subscriber<String>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(1);
                }

                @Override
                public void onNext(String indexName) {
                    log.debug("Ensured TTL index {} on {}", indexName, collection.getNamespace());
                }

                @Override
                public void onError(Throwable t) {
                    if (t instanceof MongoCommandException
                            && ((MongoCommandException) t).getErrorCode() == INDEX_OPTIONS_CONFLICT) {
                        reconcileTtlIndexAsync(collection, collectionName);
                    } else {
                        indexedCollections.remove(collectionName);
                        log.error("Failed to apply TTL index of {}s on {}", ttlSeconds, collection.getNamespace(), t);
                    }
                }

                @Override
                public void onComplete() {
                }
            });
    }

    private void reconcileTtlIndexAsync(MongoCollection<Document> collection, String collectionName) {
        Document collModCmd = new Document("collMod", collection.getNamespace().getCollectionName())
                .append("index", new Document("keyPattern", new Document("createdAt", 1))
                        .append("expireAfterSeconds", ttlSeconds));

        database.runCommand(collModCmd).subscribe(new Subscriber<Document>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(Document doc) {
                log.info("Updated TTL index on {} to {} seconds via collMod", collection.getNamespace(), ttlSeconds);
            }

            @Override
            public void onError(Throwable t) {
                indexedCollections.remove(collectionName);
                log.error("Failed to reconcile TTL index on {}", collection.getNamespace(), t);
            }

            @Override
            public void onComplete() {
            }
        });
    }

    /**
     * Rejects a subscription whose {@link EventType} does not match the configured
     * mode.
     * <p>
     * In SINGLE_CHANNEL mode every event type shares one collection, so only the
     * {@code ALL_SINGLE_CHANNEL} subscription — the one {@code BaseStoreFactory}
     * opens —
     * is meaningful; a per-type subscription would silently watch the shared
     * collection
     * and receive unrelated event types. Mirrors {@code RedisStreamEventStore}.
     */
    private void validateSubscribe(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)
                && type != EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "Only ALL_SINGLE_CHANNEL allowed in SINGLE_CHANNEL mode");
        }
        if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)
                && type == EventType.ALL_SINGLE_CHANNEL) {
            throw new UnsupportedOperationException(
                    "ALL_SINGLE_CHANNEL not allowed in MULTI_CHANNEL mode");
        }
    }

    private String getCollectionName(EventType type) {
        if (EventStoreMode.SINGLE_CHANNEL.equals(eventStoreMode)) {
            return collectionPrefix + EventType.ALL_SINGLE_CHANNEL.name();
        }
        return collectionPrefix + type.name();
    }

    private MongoCollection<Document> getCollection(String collectionName) {
        return collectionCache.computeIfAbsent(collectionName, name -> {
            MongoCollection<Document> col = database.getCollection(name);
            if (writeConcern != null) {
                col = col.withWriteConcern(writeConcern);
            }
            if (readPreference != null) {
                col = col.withReadPreference(readPreference);
            }
            return col;
        });
    }

    /**
     * Logs a failed insert; a published event is never retried, as in the Kafka
     * store.
     */
    private static final class PublishSubscriber implements Subscriber<InsertOneResult> {

        private final EventType type;
        private final String collectionName;

        PublishSubscriber(EventType type, String collectionName) {
            this.type = type;
            this.collectionName = collectionName;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(1);
        }

        @Override
        public void onNext(InsertOneResult result) {
            // the insert result carries nothing this store needs
        }

        @Override
        public void onError(Throwable error) {
            log.warn("Failed to publish {} to {}", type, collectionName, error);
        }

        @Override
        public void onComplete() {
            // nothing to do
        }
    }

    /**
     * Delivers change events to one listener and reopens the stream when it ends,
     * which
     * replaces the reconnect loop a blocking cursor needed.
     */
    final class ChangeSubscriber<T extends EventMessage>
            implements Subscriber<ChangeStreamDocument<Document>> {

        private static final long DEMAND_BATCH_SIZE = 128;

        private final MongoCollection<Document> collection;
        private final EventType type;
        private final WatcherHandle handle;
        private final EventListener<T> listener;
        private final Class<T> clazz;

        private final CompletableFuture<Void> initialFuture;

        ChangeSubscriber(MongoCollection<Document> collection,
                EventType type,
                WatcherHandle handle,
                EventListener<T> listener,
                Class<T> clazz) {
            this(collection, type, handle, listener, clazz, null);
        }

        ChangeSubscriber(MongoCollection<Document> collection,
                EventType type,
                WatcherHandle handle,
                EventListener<T> listener,
                Class<T> clazz,
                @Nullable CompletableFuture<Void> initialFuture) {
            this.collection = collection;
            this.type = type;
            this.handle = handle;
            this.listener = listener;
            this.clazz = clazz;
            this.initialFuture = initialFuture;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            handle.setSubscription(subscription);
            subscription.request(DEMAND_BATCH_SIZE);
            if (initialFuture != null && !initialFuture.isDone()) {
                initialFuture.complete(null);
            }
        }

        @Override
        public void onNext(ChangeStreamDocument<Document> change) {
            if (handle.stopped.get()) {
                return;
            }

            handle.resetReconnectAttempts();
            handle.setResumeToken(change.getResumeToken());

            // Replenish demand to maintain backpressure flow control
            Subscription sub = handle.getSubscription();
            if (sub != null && !handle.stopped.get()) {
                sub.request(1);
            }

            Document doc = change.getFullDocument();
            if (doc == null) {
                return;
            }

            // A node sees its own inserts too, so drop them on the document's own nodeId
            // rather than paying for the deserialization first.
            Object rawNodeId = doc.get("nodeId");
            if (rawNodeId instanceof Number && nodeId.longValue() == ((Number) rawNodeId).longValue()) {
                return;
            }

            if (EventStoreMode.MULTI_CHANNEL.equals(eventStoreMode)) {
                String eventTypeName = doc.getString("eventType");
                if (eventTypeName != null && !type.name().equals(eventTypeName)) {
                    return;
                }
            }

            String payload = doc.getString("payload");
            if (payload == null) {
                return;
            }

            try {
                T event = MAPPER.readValue(payload, clazz);
                if (handle.stopped.get()) {
                    return;
                }
                // Belt and braces: a document written without the nodeId field still gets
                // filtered on the value carried by the event itself.
                if (event.getNodeId() == null || nodeId.longValue() != event.getNodeId().longValue()) {
                    listener.onMessage(event);
                }
            } catch (Exception e) {
                log.warn("Failed to process change event on {}", collection.getNamespace(), e);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (handle.stopped.get()) {
                handle.markTerminated();
                if (initialFuture != null && !initialFuture.isDone()) {
                    initialFuture.complete(null);
                }
                return;
            }

            if (initialFuture != null && !initialFuture.isDone()) {
                initialFuture.completeExceptionally(error);
            }

            if (isChangeStreamHistoryLost(error)) {
                log.error("Change stream history lost on {}. Clearing resume point and restarting from current time.",
                        collection.getNamespace(), error);
                handle.clearResumePoint();
            } else if (isStandaloneError(error)) {
                log.error("MongoDB change streams require a replica set or sharded cluster. "
                        + "Standalone deployments are not supported on {}: {}",
                        collection.getNamespace(), error.getMessage());
            } else {
                log.warn("Change stream on {} failed, reopening...", collection.getNamespace(), error);
            }
            reopen();
        }

        @Override
        public void onComplete() {
            if (handle.stopped.get()) {
                handle.markTerminated();
                return;
            }
            // The server ended the stream, for instance because the collection was dropped.
            reopen();
        }

        private void reopen() {
            int attempts = handle.incrementReconnectAttempts();
            long delay = Math.min(
                    INITIAL_REOPEN_DELAY_MILLIS * (1L << Math.min(attempts - 1, 4)),
                    MAX_REOPEN_DELAY_MILLIS);
            try {
                watcherExecutor.schedule(
                        () -> watch(collection, type, handle, listener, clazz, null),
                        delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                log.debug("Not reopening the change stream on {}, the store is shutting down",
                        collection.getNamespace());
            }
        }

        private boolean isChangeStreamHistoryLost(Throwable error) {
            Throwable curr = error;
            while (curr != null) {
                if (curr instanceof MongoCommandException) {
                    int code = ((MongoCommandException) curr).getErrorCode();
                    // 286 = ChangeStreamHistoryLost, 40585 = ChangeStreamFatalError, 40579 =
                    // ChangeStreamTimedOut
                    if (code == 286 || code == 40585 || code == 40579) {
                        return true;
                    }
                }
                String msg = curr.getMessage();
                if (msg != null && (msg.contains("history lost") || msg.contains("ChangeStreamHistoryLost")
                        || msg.contains("resume point may no longer be in the oplog"))) {
                    return true;
                }
                curr = curr.getCause();
            }
            return false;
        }

        private boolean isStandaloneError(Throwable error) {
            Throwable curr = error;
            while (curr != null) {
                if (curr instanceof MongoCommandException) {
                    int code = ((MongoCommandException) curr).getErrorCode();
                    // 40573: The $changeStream stage is only supported on replica sets
                    if (code == 40573) {
                        return true;
                    }
                }
                String msg = curr.getMessage();
                if (msg != null && (msg.contains("only supported on replica sets")
                        || msg.contains("Change streams are only supported on replica sets")
                        || msg.contains("The $changeStream stage is only supported on replica sets"))) {
                    return true;
                }
                curr = curr.getCause();
            }
            return false;
        }
    }

    static final class WatcherHandle {

        private static final Subscription CANCELLED = new Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
            }
        };

        final AtomicBoolean stopped = new AtomicBoolean(false);

        private final CountDownLatch terminated = new CountDownLatch(1);
        private final AtomicReference<Subscription> subRef = new AtomicReference<>();
        private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

        private volatile BsonTimestamp startAt;
        private volatile BsonDocument resumeToken;

        WatcherHandle(BsonTimestamp startAt) {
            this.startAt = startAt;
        }

        BsonTimestamp startAt() {
            return startAt;
        }

        void setStartAt(BsonTimestamp startAt) {
            this.startAt = startAt;
        }

        void clearResumePoint() {
            this.resumeToken = null;
            this.startAt = null;
        }

        int incrementReconnectAttempts() {
            return reconnectAttempts.incrementAndGet();
        }

        void resetReconnectAttempts() {
            reconnectAttempts.set(0);
        }

        /**
         * Atomically publishes the subscription so {@link #stop()} can cancel it.
         * If stop already happened, cancels the subscription immediately.
         */
        void setSubscription(Subscription subscription) {
            while (!stopped.get()) {
                Subscription current = subRef.get();
                if (current == CANCELLED) {
                    subscription.cancel();
                    return;
                }
                if (subRef.compareAndSet(current, subscription)) {
                    if (stopped.get()) {
                        if (subRef.compareAndSet(subscription, CANCELLED)) {
                            subscription.cancel();
                        }
                    }
                    return;
                }
            }
            subscription.cancel();
        }

        Subscription getSubscription() {
            Subscription s = subRef.get();
            if (s == CANCELLED) {
                return null;
            }
            return s;
        }

        void setResumeToken(BsonDocument resumeToken) {
            this.resumeToken = resumeToken;
        }

        BsonDocument resumeToken() {
            return resumeToken;
        }

        void stop() {
            stopped.set(true);
            Subscription s = subRef.getAndSet(CANCELLED);
            if (s != null && s != CANCELLED) {
                try {
                    s.cancel();
                } catch (Exception e) {
                    log.debug("Error cancelling change stream subscription", e);
                }
            }
            terminated.countDown();
        }

        void markTerminated() {
            terminated.countDown();
        }

        void awaitTermination(long nanos) throws InterruptedException {
            terminated.await(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Builder for {@link MongoEventStore}.
     */
    public static final class Builder {

        private final MongoClient mongoClient;
        private final String databaseName;

        private Long nodeId;
        private EventStoreMode eventStoreMode = EventStoreMode.MULTI_CHANNEL;
        private String collectionPrefix;
        private long ttlSeconds = DEFAULT_TTL_SECONDS;
        private WriteConcern writeConcern;
        private ReadPreference readPreference;

        /**
         * Creates a new builder.
         *
         * @param mongoClient  shared MongoDB client (must connect to a replica set)
         * @param databaseName database to use for event collections
         */
        public Builder(@NotNull MongoClient mongoClient, @NotNull String databaseName) {
            this.mongoClient = Objects.requireNonNull(mongoClient, "mongoClient");
            this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
        }

        public Builder nodeId(long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder eventStoreMode(@NotNull EventStoreMode mode) {
            this.eventStoreMode = Objects.requireNonNull(mode, "eventStoreMode");
            return this;
        }

        public Builder collectionPrefix(@NotNull String prefix) {
            this.collectionPrefix = Objects.requireNonNull(prefix, "collectionPrefix");
            return this;
        }

        /**
         * Sets the TTL in seconds for automatic document expiry.
         * Documents older than this are automatically removed by MongoDB.
         * Default is 60 seconds.
         *
         * @param ttlSeconds retention period in seconds
         * @return this builder
         */
        public Builder ttlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
            return this;
        }

        /**
         * Sets the write concern to use for publishing events.
         *
         * @param writeConcern write concern (e.g. WriteConcern.W1 or UNACKNOWLEDGED)
         * @return this builder
         */
        public Builder writeConcern(@NotNull WriteConcern writeConcern) {
            this.writeConcern = Objects.requireNonNull(writeConcern, "writeConcern");
            return this;
        }

        /**
         * Sets the read preference for change stream cursors.
         *
         * @param readPreference read preference (e.g.
         *                       ReadPreference.secondaryPreferred())
         * @return this builder
         */
        public Builder readPreference(@NotNull ReadPreference readPreference) {
            this.readPreference = Objects.requireNonNull(readPreference, "readPreference");
            return this;
        }

        public MongoEventStore build() {
            return new MongoEventStore(
                    mongoClient,
                    databaseName,
                    eventStoreMode,
                    nodeId,
                    collectionPrefix,
                    ttlSeconds,
                    writeConcern,
                    readPreference);
        }
    }
}
