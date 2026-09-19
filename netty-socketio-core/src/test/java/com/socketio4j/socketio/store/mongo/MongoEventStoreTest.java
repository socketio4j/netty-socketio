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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoNamespace;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.store.event.DispatchMessage;
import com.socketio4j.socketio.store.event.EventListener;
import com.socketio4j.socketio.store.event.EventMessage;
import com.socketio4j.socketio.store.event.EventMessageJsonSupport;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.event.EventStoreType;
import com.socketio4j.socketio.store.event.EventType;
import com.socketio4j.socketio.store.event.PublishMode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MongoEventStoreTest {

        private MongoClient mongoClient;
        private MongoDatabase mongoDatabase;
        private MongoCollection<Document> mongoCollection;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
                mongoClient = mock(MongoClient.class);
                mongoDatabase = mock(MongoDatabase.class);
                mongoCollection = mock(MongoCollection.class);
                when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
        }

        @Test
        void testBuilderDefaultsAndCustomValues() {
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(42L)
                                .collectionPrefix("custom_events_")
                                .ttlSeconds(120)
                                .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                                .writeConcern(WriteConcern.W1)
                                .readPreference(ReadPreference.secondaryPreferred())
                                .build();

                assertEquals(EventStoreMode.SINGLE_CHANNEL, store.getEventStoreMode());
                assertEquals(EventStoreType.PUBSUB, store.getEventStoreType());
                assertEquals(PublishMode.UNRELIABLE, store.getPublishMode());
                assertEquals("custom_events_", store.getCollectionPrefix());
                assertEquals(120, store.getTtlSeconds());
                assertEquals(WriteConcern.W1, store.getWriteConcern());
                assertEquals(ReadPreference.secondaryPreferred(), store.getReadPreference());
        }

        @Test
        void testValidateSubscribeModes() {
                MongoEventStore singleChannelStore = new MongoEventStore.Builder(mongoClient, "testdb")
                                .eventStoreMode(EventStoreMode.SINGLE_CHANNEL)
                                .build();

                assertThrows(UnsupportedOperationException.class,
                                () -> singleChannelStore.subscribe0(EventType.DISPATCH, msg -> {
                                }, EventMessage.class));

                MongoEventStore multiChannelStore = new MongoEventStore.Builder(mongoClient, "testdb")
                                .eventStoreMode(EventStoreMode.MULTI_CHANNEL)
                                .build();

                assertThrows(UnsupportedOperationException.class,
                                () -> multiChannelStore.subscribe0(EventType.ALL_SINGLE_CHANNEL, msg -> {
                                }, EventMessage.class));
        }

        @Test
        void testWatcherHandleLifecycleAndCancel() {
                BsonTimestamp startAt = new BsonTimestamp(100, 1);
                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(startAt);

                assertEquals(startAt, handle.startAt());
                assertNull(handle.resumeToken());

                BsonDocument token = new BsonDocument();
                handle.setResumeToken(token);
                assertEquals(token, handle.resumeToken());

                handle.clearResumePoint();
                assertNull(handle.resumeToken());
                assertNull(handle.startAt());

                AtomicBoolean cancelled = new AtomicBoolean(false);
                Subscription subscription = new Subscription() {
                        @Override
                        public void request(long n) {
                        }

                        @Override
                        public void cancel() {
                                cancelled.set(true);
                        }
                };

                handle.setSubscription(subscription);
                assertEquals(subscription, handle.getSubscription());

                handle.stop();
                assertTrue(cancelled.get());
                assertTrue(handle.stopped.get());
                assertNull(handle.getSubscription());
        }

        @Test
        void testFastShutdownWithoutArtificialDelay() {
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .build();

                long start = System.currentTimeMillis();
                store.shutdown0();
                long elapsed = System.currentTimeMillis() - start;

                // Shutdown should complete promptly, well below the previous 1000ms artificial
                // delay
                assertTrue(elapsed < 800, "Shutdown took " + elapsed + "ms, expected under 800ms");
        }

        @Test
        void testPublishValidation() {
                when(mongoDatabase.getCollection(anyString())).thenReturn(mongoCollection);
                Publisher<InsertOneResult> dummyPublisher = subscriber -> subscriber
                                .onSubscribe(mock(Subscription.class));
                when(mongoCollection.insertOne(any(Document.class))).thenReturn(dummyPublisher);

                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(10L)
                                .build();

                EventMessage msg = new EventMessage() {
                        @Override
                        public String getType() {
                                return "DISPATCH";
                        }
                };

                store.publish0(EventType.DISPATCH, msg);
                assertEquals(10L, msg.getNodeId());
        }

        @Test
        void testOplogHistoryLostClearsResumePoint() {
                when(mongoCollection.getNamespace()).thenReturn(new MongoNamespace("testdb.events"));
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(1L)
                                .build();

                BsonTimestamp startAt = new BsonTimestamp(100, 1);
                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(startAt);
                handle.setResumeToken(new BsonDocument());

                MongoEventStore.ChangeSubscriber<DispatchMessage> subscriber = store.new ChangeSubscriber<>(
                                mongoCollection, EventType.DISPATCH, handle, msg -> {
                                }, DispatchMessage.class);

                // Simulate MongoCommandException with error code 286 (ChangeStreamHistoryLost)
                MongoCommandException historyLostException = mock(MongoCommandException.class);
                when(historyLostException.getErrorCode()).thenReturn(286);
                when(historyLostException.getMessage())
                                .thenReturn("ChangeStreamHistoryLost: resume point no longer in oplog");

                subscriber.onError(historyLostException);

                // Resume point must be cleared so the next watch() starts at current time
                assertNull(handle.resumeToken());
                assertNull(handle.startAt());

                store.shutdown0();
        }

        @Test
        void testTransientErrorPreservesResumePoint() {
                when(mongoCollection.getNamespace()).thenReturn(new MongoNamespace("testdb.events"));
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(1L)
                                .build();

                BsonTimestamp startAt = new BsonTimestamp(100, 1);
                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(startAt);
                BsonDocument token = new BsonDocument();
                handle.setResumeToken(token);

                MongoEventStore.ChangeSubscriber<DispatchMessage> subscriber = store.new ChangeSubscriber<>(
                                mongoCollection, EventType.DISPATCH, handle, msg -> {
                                }, DispatchMessage.class);

                // Simulate transient network exception
                subscriber.onError(new RuntimeException("Transient connection reset"));

                // Resume token must be preserved to resume after the last delivered event
                assertEquals(token, handle.resumeToken());

                store.shutdown0();
        }

        @Test
        @SuppressWarnings("unchecked")
        void testChangeSubscriberDropsSelfPublishedEvents() {
                when(mongoCollection.getNamespace()).thenReturn(new MongoNamespace("testdb.events"));
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(50L)
                                .build();

                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(new BsonTimestamp(1, 1));
                EventListener<DispatchMessage> listener = mock(EventListener.class);

                MongoEventStore.ChangeSubscriber<DispatchMessage> subscriber = store.new ChangeSubscriber<>(
                                mongoCollection, EventType.DISPATCH, handle, listener, DispatchMessage.class);

                ChangeStreamDocument<Document> change = mock(ChangeStreamDocument.class);
                Document doc = new Document()
                                .append("nodeId", 50L) // same node id
                                .append("eventType", "DISPATCH")
                                .append("payload",
                                                "{\"room\":\"r\",\"namespace\":\"/\",\"packet\":{\"type\":2,\"data\":\"hi\"}}");

                when(change.getFullDocument()).thenReturn(doc);
                when(change.getResumeToken()).thenReturn(new BsonDocument());

                subscriber.onNext(change);

                // Must drop without calling listener
                verify(listener, never()).onMessage(any());

                store.shutdown0();
        }

        @Test
        void testChangeSubscriberDeliversAndPreservesBinaryPayload() {
                when(mongoCollection.getNamespace()).thenReturn(new MongoNamespace("testdb.events"));
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(50L)
                                .build();

                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(new BsonTimestamp(1, 1));
                AtomicReference<DispatchMessage> receivedRef = new AtomicReference<>();

                MongoEventStore.ChangeSubscriber<DispatchMessage> subscriber = store.new ChangeSubscriber<>(
                                mongoCollection, EventType.DISPATCH, handle, receivedRef::set, DispatchMessage.class);

                byte[] rawBytes = new byte[] { 0x10, 0x20, 0x30, 0x40, 0x50 };
                Packet packet = new Packet(PacketType.BINARY_EVENT);
                packet.setName("bin-event");
                packet.setNsp("/binary");
                packet.setData(rawBytes);

                DispatchMessage msg = new DispatchMessage("binRoom", packet, "/binary");
                msg.setNodeId(99L); // remote node

                // Use store's serializer logic
                String json;
                try {
                        json = EventMessageJsonSupport.createObjectMapper().writeValueAsString(msg);
                } catch (Exception e) {
                        throw new RuntimeException(e);
                }

                Document doc = new Document()
                                .append("nodeId", 99L)
                                .append("eventType", "DISPATCH")
                                .append("payload", json);

                @SuppressWarnings("unchecked")
                ChangeStreamDocument<Document> change = mock(ChangeStreamDocument.class);
                when(change.getFullDocument()).thenReturn(doc);
                when(change.getResumeToken()).thenReturn(new BsonDocument());

                subscriber.onNext(change);

                DispatchMessage received = receivedRef.get();
                assertNotNull(received);
                assertEquals(99L, received.getNodeId());
                assertEquals("binRoom", received.getRoom());
                assertNotNull(received.getPacket());
                assertArrayEquals(rawBytes, (byte[]) received.getPacket().getData());

                store.shutdown0();
        }

        @Test
        void testPublishAndSubscribeAfterShutdown() {
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(1L)
                                .build();

                store.shutdown0();

                // Second shutdown must be safe and idempotent
                assertDoesNotThrow(store::shutdown0);

                // Publish after shutdown should safely no-op without exception
                assertDoesNotThrow(() -> store.publish0(EventType.DISPATCH, new DispatchMessage()));

                // Subscribe after shutdown must be rejected
                assertThrows(IllegalStateException.class, () -> store.subscribe0(EventType.DISPATCH, msg -> {
                }, DispatchMessage.class));
        }

        @Test
        void testReconnectAttemptsPersistAcrossReopens() {
                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(new BsonTimestamp(1, 1));

                assertEquals(1, handle.incrementReconnectAttempts());
                assertEquals(2, handle.incrementReconnectAttempts());
                assertEquals(3, handle.incrementReconnectAttempts());

                handle.resetReconnectAttempts();
                assertEquals(1, handle.incrementReconnectAttempts());
        }

        @Test
        @SuppressWarnings("unchecked")
        void testStoppedWatcherHandleDropsInFlightOnNext() {
                when(mongoCollection.getNamespace()).thenReturn(new MongoNamespace("testdb.events"));
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(50L)
                                .build();

                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(new BsonTimestamp(1, 1));
                EventListener<DispatchMessage> listener = mock(EventListener.class);

                MongoEventStore.ChangeSubscriber<DispatchMessage> subscriber = store.new ChangeSubscriber<>(
                                mongoCollection, EventType.DISPATCH, handle, listener, DispatchMessage.class);

                // Stop handle before event arrives
                handle.stop();

                ChangeStreamDocument<Document> change = mock(ChangeStreamDocument.class);
                Document doc = new Document()
                                .append("nodeId", 99L)
                                .append("eventType", "DISPATCH")
                                .append("payload",
                                                "{\"room\":\"r\",\"namespace\":\"/\",\"packet\":{\"type\":2,\"data\":\"hi\"}}");

                when(change.getFullDocument()).thenReturn(doc);
                when(change.getResumeToken()).thenReturn(new BsonDocument());

                subscriber.onNext(change);

                // Must drop without calling listener because handle is stopped
                verify(listener, never()).onMessage(any());

                store.shutdown0();
        }

        @Test
        @SuppressWarnings("unchecked")
        void testChangeSubscriberCatchesExceptionFromListener() {
                when(mongoCollection.getNamespace()).thenReturn(new MongoNamespace("testdb.events"));
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(50L)
                                .build();

                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(new BsonTimestamp(1, 1));
                EventListener<DispatchMessage> listener = mock(EventListener.class);
                org.mockito.Mockito.doThrow(new RuntimeException("Error in listener")).when(listener).onMessage(any());

                MongoEventStore.ChangeSubscriber<DispatchMessage> subscriber = store.new ChangeSubscriber<>(
                                mongoCollection, EventType.DISPATCH, handle, listener, DispatchMessage.class);

                ChangeStreamDocument<Document> change = mock(ChangeStreamDocument.class);
                Document doc = new Document()
                                .append("nodeId", 99L)
                                .append("eventType", "DISPATCH")
                                .append("payload",
                                                "{\"room\":\"r\",\"namespace\":\"/\",\"packet\":{\"type\":2,\"data\":\"hi\"}}");

                when(change.getFullDocument()).thenReturn(doc);
                when(change.getResumeToken()).thenReturn(new BsonDocument());

                // Should safely catch Throwable without propagating or failing
                assertDoesNotThrow(() -> subscriber.onNext(change));

                store.shutdown0();
        }

        @Test
        void testStandaloneErrorDetectionInChangeSubscriber() {
                when(mongoCollection.getNamespace()).thenReturn(new MongoNamespace("testdb.events"));
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(1L)
                                .build();

                MongoEventStore.WatcherHandle handle = new MongoEventStore.WatcherHandle(new BsonTimestamp(1, 1));
                MongoEventStore.ChangeSubscriber<DispatchMessage> subscriber = store.new ChangeSubscriber<>(
                                mongoCollection, EventType.DISPATCH, handle, msg -> {
                                }, DispatchMessage.class);

                MongoCommandException standaloneEx = mock(MongoCommandException.class);
                when(standaloneEx.getErrorCode()).thenReturn(40573);
                when(standaloneEx.getMessage())
                                .thenReturn("The $changeStream stage is only supported on replica sets");

                assertDoesNotThrow(() -> subscriber.onError(standaloneEx));

                store.shutdown0();
        }

        @Test
        void testShutdownAsyncCompletesPromptly() {
                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .build();

                java.util.concurrent.CompletableFuture<Void> future = store.shutdownAsync();
                assertNotNull(future);
                assertDoesNotThrow(() -> future.get(2, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(future.isDone());
        }

        @Test
        @SuppressWarnings("unchecked")
        void testSubscribe0IsNonBlocking() {
                when(mongoDatabase.getCollection(anyString())).thenReturn(mongoCollection);
                when(mongoCollection.getNamespace()).thenReturn(new MongoNamespace("testdb.events"));

                // Mock ping publisher that never completes (simulating a slow network)
                Publisher<Document> slowPingPublisher = subscriber -> {
                        // intentionally do not invoke onNext or onComplete
                };
                when(mongoDatabase.runCommand(any(Document.class))).thenReturn(slowPingPublisher);

                Publisher<String> indexPublisher = subscriber -> subscriber.onSubscribe(mock(Subscription.class));
                when(mongoCollection.createIndex(any(org.bson.conversions.Bson.class), any(com.mongodb.client.model.IndexOptions.class)))
                                .thenReturn(indexPublisher);

                MongoEventStore store = new MongoEventStore.Builder(mongoClient, "testdb")
                                .nodeId(1L)
                                .build();

                long start = System.currentTimeMillis();
                // subscribe0 must return immediately without waiting for ping or index
                store.subscribe0(EventType.DISPATCH, msg -> {
                }, DispatchMessage.class);
                long elapsed = System.currentTimeMillis() - start;

                assertTrue(elapsed < 200, "subscribe0 blocked for " + elapsed + "ms, expected non-blocking (<200ms)");
                store.shutdown0();
        }
}
