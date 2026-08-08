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
package com.socketio4j.socketio.integration.interop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.socketio4j.socketio.SocketIONamespace;
import com.socketio4j.socketio.integration.protocol.AbstractSocketIOIntegrationTest;
import com.socketio4j.socketio.namespace.NamespaceTestReuseAssertions;

/**
 * Shares one single-node server through an interop class while treating every
 * individual case as isolated. A case cannot pass into the next one with
 * clients, room membership, dynamically-created namespaces, event mappings,
 * or any listener type still registered.
 */
abstract class AbstractReusableSocketIOInteropTest
        extends AbstractSocketIOIntegrationTest {

    private static final long DISCONNECT_SETTLE_TIMEOUT_MILLIS = 2_000L;
    private static final long FORCED_DISCONNECT_TIMEOUT_MILLIS = 5_000L;
    private static final long POLL_INTERVAL_MILLIS = 10L;

    private Set<String> baselineNamespaces;

    @Override
    protected final boolean reuseServerForTestClass() {
        return true;
    }

    @BeforeEach
    void assertReusableServerIsCleanBeforeCase() {
        if (getServer() == null || !getServer().isStarted()) {
            throw new AssertionError("Reusable interop server is not running before test case");
        }

        if (baselineNamespaces == null) {
            baselineNamespaces = namespaceNames();
        } else if (!baselineNamespaces.equals(namespaceNames())) {
            throw new AssertionError("Reusable interop server retained unexpected namespaces before test case. "
                    + "expected=" + baselineNamespaces + ", actual=" + namespaceNames());
        }

        for (SocketIONamespace namespace : getServer().getAllNamespaces()) {
            NamespaceTestReuseAssertions.assertEmpty(namespace, "before test case");
            NamespaceTestReuseAssertions.assertNoListeners(namespace, "before test case");
        }
    }

    @AfterEach
    void resetReusableServerAfterCase() throws Exception {
        if (getServer() == null) {
            return;
        }

        Throwable isolationFailure = null;
        if (!waitForNoClients(DISCONNECT_SETTLE_TIMEOUT_MILLIS)) {
            String retainedClients = describeConnectedClients();
            getServer().getBroadcastOperations().disconnect();

            if (!waitForNoClients(FORCED_DISCONNECT_TIMEOUT_MILLIS)) {
                isolationFailure = new AssertionError(
                        "Interop case left clients connected and forced cleanup did not finish: "
                                + retainedClients + "; remaining=" + describeConnectedClients());
            } else {
                isolationFailure = new AssertionError(
                        "Interop case left clients connected after its client process exited: "
                                + retainedClients);
            }
        }

        try {
            resetNamespaceStateAfterCase();
        } catch (Throwable cleanupFailure) {
            if (isolationFailure == null) {
                isolationFailure = cleanupFailure;
            } else {
                isolationFailure.addSuppressed(cleanupFailure);
            }
        }

        if (isolationFailure != null) {
            rethrow(isolationFailure);
        }
    }

    private void resetNamespaceStateAfterCase() {
        List<SocketIONamespace> namespaces =
                new ArrayList<SocketIONamespace>(getServer().getAllNamespaces());
        if (!baselineNamespaces.equals(namespaceNames())) {
            for (SocketIONamespace namespace : namespaces) {
                if (!baselineNamespaces.contains(namespace.getName())) {
                    getServer().removeNamespace(namespace.getName());
                }
            }
        }

        for (SocketIONamespace namespace : getServer().getAllNamespaces()) {
            NamespaceTestReuseAssertions.clearListeners(namespace);
            NamespaceTestReuseAssertions.assertEmpty(namespace, "after listener cleanup");
            NamespaceTestReuseAssertions.assertNoListeners(namespace, "after listener cleanup");
        }

        if (!baselineNamespaces.equals(namespaceNames())) {
            throw new AssertionError("Reusable interop server failed to remove test-created namespaces. "
                    + "expected=" + baselineNamespaces + ", actual=" + namespaceNames());
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw new RuntimeException(failure);
    }

    @AfterAll
    void stopReusableInteropServer() {
        stopServer();
    }

    private Set<String> namespaceNames() {
        Set<String> names = new HashSet<String>();
        for (SocketIONamespace namespace : getServer().getAllNamespaces()) {
            names.add(namespace.getName());
        }
        return names;
    }

    private boolean waitForNoClients(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            if (allNamespacesAreEmpty()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
        } while (System.nanoTime() < deadline);
        return allNamespacesAreEmpty();
    }

    private boolean allNamespacesAreEmpty() {
        for (SocketIONamespace namespace : getServer().getAllNamespaces()) {
            if (!namespace.getAllClients().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String describeConnectedClients() {
        List<String> descriptions = new ArrayList<String>();
        Collection<SocketIONamespace> namespaces = getServer().getAllNamespaces();
        for (SocketIONamespace namespace : namespaces) {
            if (!namespace.getAllClients().isEmpty()) {
                descriptions.add(namespace.getName() + "=" + namespace.getAllClients());
            }
        }
        return descriptions.toString();
    }
}
