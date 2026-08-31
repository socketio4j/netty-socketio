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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
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
 * events to subscribers. Each event type maps to its own collection (MULTI_CHANNEL)
 * or all events go into one collection (SINGLE_CHANNEL).
 * <p>
 * Built on the reactive streams driver: {@code publish0} is called from the Netty
 * event loop, so the insert is handed to the driver and never waited on. Only the
 * one-off setup done while subscribing (index creation, reading the cluster time)
 * blocks, and that runs on the thread starting the server.
 * <p>
 * A TTL index is created on each collection to automatically expire documents
 * after a configurable retention period (default 60 seconds), preventing
 * unbounded data growth.
 * <p>
 * Requires a MongoDB replica set (standalone does not support change streams).
 */
public class MongoEventStore implements EventStore {

    private static final Logger log =
            LoggerFactory.getLogger(MongoEventStore.class);

    private static final String DEFAULT_COLLECTION_PREFIX = "socketio_events_";
    private static final long DEFAULT_TTL_SECONDS = 60;

    /** How long a blocking setup command may take before subscribing is given up on. */
    private static final long SETUP_TIMEOUT_SECONDS = 10;

    /** How long to wait before reopening a change stream that ended or failed. */
    private static final long REOPEN_DELAY_MILLIS = 1000;

    /**
     * How long {@link #shutdown0()} waits for cancelled change streams to actually end.
     * Cancelling is asynchronous, and the driver may still have a {@code getMore} in flight;
     * if the caller closes its {@code MongoClient} before that lands, the cursor tries to
     * resume against a closed cluster and the driver logs a failure per stream.
     */
    private static final long CANCEL_GRACE_MILLIS = 1000;

    /**
     * Server-side filter for the change stream: only inserts carry published events, and
     * TTL expiry deletes a batch of documents about once a minute — events every watcher
     * would otherwise receive and decode only to drop.
     */
    private static final List<Bson> INSERT_ONLY = Collections.singletonList(
            Aggregates.match(Filters.eq("operationType", "insert")));

    /** MongoDB error code raised when an index exists with the same key but different options. */
    private static final int INDEX_OPTIONS_CONFLICT = 85;

    /** Shared mapper: keeps byte[] payloads lossless, as the Kafka and NATS stores do. */
    private static final ObjectMapper MAPPER = EventMessageJsonSupport.createObjectMapper();

    private final MongoDatabase database;
    private final Long nodeId;
    private final EventStoreMode eventStoreMode;
    private final String collectionPrefix;
    private final long ttlSeconds;

    private final ConcurrentMap<EventType, Queue<WatcherHandle>> watchers =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService watcherExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
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
     * @param eventStoreMode   SINGLE_CHANNEL or MULTI_CHANNEL (defaults to MULTI_CHANNEL)
     * @param nodeId           node identifier used to ignore self-published events
     * @param collectionPrefix prefix for collection names (defaults to "socketio_events_")
     * @param ttlSeconds       TTL in seconds for automatic document expiry (defaults to 60)
     */
    public MongoEventStore(@NotNull MongoClient mongoClient,
                           @NotNull String databaseName,
                           @Nullable EventStoreMode eventStoreMode,
                           @Nullable Long nodeId,
                           @Nullable String collectionPrefix,
                           long ttlSeconds) {
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

    @Override
    public void publish0(EventType type, EventMessage msg) {
        msg.setNodeId(nodeId);

        String collectionName = getCollectionName(type);
        byte[] data;
        try {
            data = MAPPER.writeValueAsBytes(msg);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize EventMessage", e);
        }
        Document doc = new Document()
                .append("nodeId", nodeId)
                .append("eventType", type.name())
                .append("createdAt", new Date())
                .append("payload", new String(data, StandardCharsets.UTF_8));

        // Fire and forget: this runs on the Netty event loop, so the insert is handed to
        // the driver and its outcome only logged, the model KafkaEventStore.publish0 uses.
        database.getCollection(collectionName)
                .insertOne(doc)
                .subscribe(new PublishSubscriber(type, collectionName));
    }

    @Override
    public <T extends EventMessage> void subscribe0(
            EventType type,
            final EventListener<T> listener,
            Class<T> clazz) {

        Objects.requireNonNull(listener);
        Objects.requireNonNull(clazz);

        validateSubscribe(type);

        String collectionName = getCollectionName(type);
        MongoCollection<Document> collection = database.getCollection(collectionName);

        ensureTtlIndex(collection);

        // The change stream opens asynchronously, so events published in between would be
        // lost. Starting it at the operation time read here covers that window instead of
        // making subscribe0 wait for a cursor it cannot observe.
        WatcherHandle handle = new WatcherHandle(currentOperationTime());

        // Register before opening the stream, and in a single atomic map operation: with a
        // separate computeIfAbsent + add, an unsubscribe0 dropping the queue in between
        // would leave the watcher running but unregistered, still delivering events after
        // unsubscribe.
        watchers.compute(type, (k, queue) -> {
            Queue<WatcherHandle> q = queue;
            if (q == null) {
                q = new ConcurrentLinkedQueue<>();
            }
            q.add(handle);
            return q;
        });

        watch(collection, type, handle, listener, clazz);
    }

    /**
     * Opens the change stream, resuming after the last delivered event when this is a
     * reopen, and otherwise starting at the operation time captured while subscribing.
     */
    private <T extends EventMessage> void watch(MongoCollection<Document> collection,
                                                EventType type,
                                                WatcherHandle handle,
                                                EventListener<T> listener,
                                                Class<T> clazz) {
        if (handle.stopped.get()) {
            return;
        }

        ChangeStreamPublisher<Document> stream = collection.watch(INSERT_ONLY);
        BsonDocument resumeToken = handle.resumeToken();
        if (resumeToken != null) {
            stream = stream.resumeAfter(resumeToken);
        } else if (handle.startAt() != null) {
            stream = stream.startAtOperationTime(handle.startAt());
        }

        stream.subscribe(new ChangeSubscriber<T>(collection, type, handle, listener, clazz));
    }

    /**
     * Removes one handle from its type's queue atomically, so it cannot race with
     * the {@code compute} in {@link #subscribe0} or the removal in {@link #unsubscribe0}.
     */
    private void unregister(EventType type, WatcherHandle handle) {
        watchers.computeIfPresent(type, (k, queue) -> {
            queue.remove(handle);
            if (queue.isEmpty()) {
                return null;
            }
            return queue;
        });
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
        List<WatcherHandle> cancelled = new ArrayList<WatcherHandle>();
        for (Queue<WatcherHandle> handles : watchers.values()) {
            cancelled.addAll(handles);
        }

        Arrays.stream(EventType.values()).forEach(this::unsubscribe);
        watchers.clear();
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
    }

    /**
     * Waits for the change streams cancelled by {@link #shutdown0()} to end, so that a
     * caller closing its {@code MongoClient} right afterwards does not interrupt them
     * mid-flight. The driver is not required to signal a cancelled subscriber at all, so
     * this is bounded by {@link #CANCEL_GRACE_MILLIS} and returns early when it does.
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
     * Creates or reconciles the TTL index used to expire published events.
     * <p>
     * {@code createIndex} creates the collection when it does not exist yet and is a
     * no-op for an identical index, but it never updates an existing {@code createdAt}
     * index whose {@code expireAfterSeconds} differs — it fails with an index-options
     * conflict and leaves the old retention period in place. That conflict is caught
     * here and the TTL is changed in place with {@code collMod}.
     * <p>
     * Any other failure (missing privileges, an unsupported server) aborts the
     * subscription: without the index, published events never expire and the collection
     * grows without bound, which is an operational problem an operator must see rather
     * than find later in a full database.
     */
    private void ensureTtlIndex(MongoCollection<Document> collection) {
        try {
            await(collection.createIndex(
                    Indexes.ascending("createdAt"),
                    new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS)
            ));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_OPTIONS_CONFLICT) {
                throw ttlIndexFailure(collection, e);
            }
            try {
                await(database.runCommand(
                        new Document("collMod", collection.getNamespace().getCollectionName())
                                .append("index", new Document("keyPattern", new Document("createdAt", 1))
                                        .append("expireAfterSeconds", ttlSeconds))));
                log.info("Updated TTL index on {} to {} seconds",
                        collection.getNamespace(), ttlSeconds);
            } catch (MongoException ce) {
                throw ttlIndexFailure(collection, ce);
            }
        } catch (MongoException e) {
            throw ttlIndexFailure(collection, e);
        }
    }

    private IllegalStateException ttlIndexFailure(MongoCollection<Document> collection, Exception cause) {
        return new IllegalStateException("Failed to apply the TTL index of " + ttlSeconds
                + "s on " + collection.getNamespace()
                + "; published events would never expire", cause);
    }

    /**
     * Reads the server's current operation time, used as the change stream start point.
     * Returns {@code null} when the deployment does not report one, in which case the
     * stream simply starts at whatever the server considers now.
     */
    private BsonTimestamp currentOperationTime() {
        Document result = await(database.runCommand(new Document("ping", 1)));
        if (result == null) {
            return null;
        }
        Object operationTime = result.get("operationTime");
        if (operationTime instanceof BsonTimestamp) {
            return (BsonTimestamp) operationTime;
        }
        return null;
    }

    /**
     * Subscribes to a one-shot publisher and waits for it, so the setup done while
     * subscribing keeps its ordering and its failures. Never called from the event loop.
     */
    private static <T> T await(Publisher<T> publisher) {
        final CompletableFuture<T> future = new CompletableFuture<T>();
        publisher.subscribe(new Subscriber<T>() {
            private T value;

            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(T item) {
                value = item;
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                future.complete(value);
            }
        });

        try {
            return future.get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for MongoDB", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "MongoDB did not respond within " + SETUP_TIMEOUT_SECONDS + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("MongoDB command failed", cause);
        }
    }

    /**
     * Rejects a subscription whose {@link EventType} does not match the configured mode.
     * <p>
     * In SINGLE_CHANNEL mode every event type shares one collection, so only the
     * {@code ALL_SINGLE_CHANNEL} subscription — the one {@code BaseStoreFactory} opens —
     * is meaningful; a per-type subscription would silently watch the shared collection
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

    /** Logs a failed insert; a published event is never retried, as in the Kafka store. */
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
     * Delivers change events to one listener and reopens the stream when it ends, which
     * replaces the reconnect loop a blocking cursor needed.
     */
    private final class ChangeSubscriber<T extends EventMessage>
            implements Subscriber<ChangeStreamDocument<Document>> {

        private final MongoCollection<Document> collection;
        private final EventType type;
        private final WatcherHandle handle;
        private final EventListener<T> listener;
        private final Class<T> clazz;

        ChangeSubscriber(MongoCollection<Document> collection,
                         EventType type,
                         WatcherHandle handle,
                         EventListener<T> listener,
                         Class<T> clazz) {
            this.collection = collection;
            this.type = type;
            this.handle = handle;
            this.listener = listener;
            this.clazz = clazz;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            handle.setSubscription(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ChangeStreamDocument<Document> change) {
            handle.setResumeToken(change.getResumeToken());

            Document doc = change.getFullDocument();
            if (doc == null) {
                return;
            }

            // A node sees its own inserts too, so drop them on the document's own nodeId
            // rather than paying for the deserialization first.
            if (nodeId.equals(doc.get("nodeId"))) {
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
                // Belt and braces: a document written without the nodeId field still gets
                // filtered on the value carried by the event itself.
                if (!nodeId.equals(event.getNodeId())) {
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
                return;
            }
            log.warn("Change stream on {} failed, reopening...", collection.getNamespace(), error);
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
            try {
                watcherExecutor.schedule(
                        () -> watch(collection, type, handle, listener, clazz),
                        REOPEN_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                log.debug("Not reopening the change stream on {}, the store is shutting down",
                        collection.getNamespace());
            }
        }
    }

    private static final class WatcherHandle {

        final AtomicBoolean stopped = new AtomicBoolean(false);

        private final CountDownLatch terminated = new CountDownLatch(1);
        private final BsonTimestamp startAt;
        private Subscription subscription;
        private BsonDocument resumeToken;

        WatcherHandle(BsonTimestamp startAt) {
            this.startAt = startAt;
        }

        BsonTimestamp startAt() {
            return startAt;
        }

        /**
         * Publishes the subscription so {@link #stop()} can cancel it. If stop already
         * happened, cancels it right away — otherwise the stream would stay open with
         * nobody left to close it.
         */
        synchronized void setSubscription(Subscription subscription) {
            if (stopped.get()) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
        }

        synchronized void setResumeToken(BsonDocument resumeToken) {
            this.resumeToken = resumeToken;
        }

        synchronized BsonDocument resumeToken() {
            return resumeToken;
        }

        synchronized void stop() {
            stopped.set(true);
            if (subscription != null) {
                subscription.cancel();
                subscription = null;
            } else {
                // Nothing was ever opened, so there is nothing left to wait for.
                terminated.countDown();
            }
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

        public MongoEventStore build() {
            return new MongoEventStore(
                    mongoClient,
                    databaseName,
                    eventStoreMode,
                    nodeId,
                    collectionPrefix,
                    ttlSeconds
            );
        }
    }
}
