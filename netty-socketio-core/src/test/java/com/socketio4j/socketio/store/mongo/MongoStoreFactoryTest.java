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

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.socketio4j.socketio.store.Store;
import com.socketio4j.socketio.store.event.EventStore;
import com.socketio4j.socketio.store.event.EventStoreMode;
import com.socketio4j.socketio.store.memory.MemoryStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MongoStoreFactoryTest {

    private MongoClient mongoClient;
    private MongoDatabase mongoDatabase;

    @BeforeEach
    void setUp() {
        mongoClient = mock(MongoClient.class);
        mongoDatabase = mock(MongoDatabase.class);
        when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
    }

    @Test
    void testConstructorWithDatabaseName() {
        MongoStoreFactory factory = new MongoStoreFactory(mongoClient, "testdb");
        assertNotNull(factory.eventStore());
        assertEquals(EventStoreMode.MULTI_CHANNEL, factory.eventStore().getEventStoreMode());
    }

    @Test
    void testConstructorWithMode() {
        MongoStoreFactory factory = new MongoStoreFactory(mongoClient, "testdb", EventStoreMode.SINGLE_CHANNEL);
        assertNotNull(factory.eventStore());
        assertEquals(EventStoreMode.SINGLE_CHANNEL, factory.eventStore().getEventStoreMode());
    }

    @Test
    void testCreateStoreReturnsMemoryStore() {
        MongoStoreFactory factory = new MongoStoreFactory(mongoClient, "testdb");
        UUID sessionId = UUID.randomUUID();
        Store store = factory.createStore(sessionId);
        assertNotNull(store);
        assertInstanceOf(MemoryStore.class, store);

        store.set("key", "value");
        assertEquals("value", store.get("key"));
        assertTrue(store.has("key"));
        store.del("key");
        store.destroy();
    }

    @Test
    void testCreateMap() {
        MongoStoreFactory factory = new MongoStoreFactory(mongoClient, "testdb");
        Map<String, String> map = factory.createMap("myMap");
        assertNotNull(map);
        map.put("k", "v");
        assertEquals("v", map.get("k"));
    }

    @Test
    void testShutdownDelegatesToEventStore() {
        EventStore mockEventStore = mock(EventStore.class);
        MongoStoreFactory factory = new MongoStoreFactory(mockEventStore);

        factory.shutdown();
        verify(mockEventStore).shutdown();
    }

    @Test
    void testToString() {
        MongoStoreFactory factory = new MongoStoreFactory(mongoClient, "testdb");
        assertTrue(factory.toString().contains("MongoStoreFactory"));
    }
}
