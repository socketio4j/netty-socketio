/*
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
package com.socketio4j.socketio.integration.interop;

import org.junit.jupiter.api.AfterAll;

import com.socketio4j.socketio.integration.protocol.AbstractSharedSocketIOIntegrationTest;

/**
 * Compatibility marker for the official Node.js client tests. These tests use
 * the default single-node profile and inherit strict per-case and per-class
 * fixture isolation from {@link AbstractSharedSocketIOIntegrationTest}.
 */
abstract class AbstractReusableSocketIOInteropTest
        extends AbstractSharedSocketIOIntegrationTest {

    @AfterAll
    void restoreInteropFixtureAfterClass() throws Exception {
        restoreSharedServerFixtureAfterClass();
    }
}
