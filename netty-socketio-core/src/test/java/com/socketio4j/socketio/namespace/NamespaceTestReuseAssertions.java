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
package com.socketio4j.socketio.namespace;

import com.socketio4j.socketio.SocketIONamespace;

/**
 * Test-only access to the package-private namespace state checks used when a
 * Socket.IO integration server is intentionally shared between test cases.
 */
public final class NamespaceTestReuseAssertions {

    private NamespaceTestReuseAssertions() {
    }

    public static void assertEmpty(SocketIONamespace namespace, String phase) {
        asNamespace(namespace).assertEmptyForTestReuse(phase);
    }

    public static void assertNoListeners(SocketIONamespace namespace, String phase) {
        asNamespace(namespace).assertNoListenersForTestReuse(phase);
    }

    public static void clearListeners(SocketIONamespace namespace) {
        asNamespace(namespace).clearListenersForTestReuse();
    }

    private static Namespace asNamespace(SocketIONamespace namespace) {
        if (!(namespace instanceof Namespace)) {
            throw new AssertionError("Expected built-in Namespace implementation but got "
                    + (namespace == null ? "null" : namespace.getClass().getName()));
        }
        return (Namespace) namespace;
    }
}
