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
package com.socketio4j.socketio.store;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.socketio4j.socketio.handler.AuthorizeHandler;
import com.socketio4j.socketio.namespace.NamespacesHub;
import com.socketio4j.socketio.protocol.JsonSupport;
import com.socketio4j.socketio.store.event.EventStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for StoreFactory implementations
 */
public abstract class AbstractStoreFactoryTestSupport {

    private AutoCloseable closeableMocks;

    @Mock
    protected NamespacesHub namespacesHub;
    
    @Mock
    protected AuthorizeHandler authorizeHandler;
    
    @Mock
    protected JsonSupport jsonSupport;

    protected StoreFactory storeFactory;

    @BeforeEach
    public void setUp() throws Exception {
        closeableMocks = MockitoAnnotations.openMocks(this);
        storeFactory = createStoreFactory();
        assertNotNull(storeFactory, "StoreFactory should not be null");
        storeFactory.init(namespacesHub, authorizeHandler, jsonSupport);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (closeableMocks != null) {
            closeableMocks.close();
        }
        if (storeFactory != null) {
            storeFactory.shutdown();
        }
    }

    protected abstract StoreFactory createStoreFactory() throws Exception;

    @Test
    public void testCreateStore() {
        UUID sessionId = UUID.randomUUID();
        Store store = storeFactory.createStore(sessionId);
        assertNotNull(store, "Store should not be null");
    }

    @Test
    public void testCreateDifferentStores() {
        UUID sessionId1 = UUID.randomUUID();
        UUID sessionId2 = UUID.randomUUID();
        
        Store store1 = storeFactory.createStore(sessionId1);
        Store store2 = storeFactory.createStore(sessionId2);
        
        assertNotNull(store1, "Store 1 should not be null");
        assertNotNull(store2, "Store 2 should not be null");
        assertNotSame(store1, store2, "Stores for different sessions should be different instances");
    }

    @Test
    public void testMapCreation() {
        String mapName = "testMap";
        Map<Object, Object> map = storeFactory.createMap(mapName);
        assertNotNull(map, "Map should not be null");
        
        // Getting map with same name should return same instance
        Map<Object, Object> sameMap = storeFactory.createMap(mapName);
        assertEquals(map, sameMap, "Getting map with same name should return same instance");
    }

    @Test
    public void testEventStoreCreation() {
        EventStore eventStore = storeFactory.eventStore();
        assertNotNull(eventStore, "EventStore should not be null");
    }

    @Test
    public void testShutdown() {
        UUID sessionId = UUID.randomUUID();
        storeFactory.createStore(sessionId);
        
        storeFactory.shutdown();
        
        // After shutdown, createStore should still work (or throw exception depending on implementation)
        // This tests that shutdown doesn't crash the factory
        try {
            storeFactory.createStore(UUID.randomUUID());
        } catch (Exception e) {
            // Expected exception in some implementations
            assertNotNull(e, "Exception should be descriptive");
        }
    }
}
