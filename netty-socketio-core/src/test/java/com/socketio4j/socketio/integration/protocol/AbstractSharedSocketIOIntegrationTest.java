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
package com.socketio4j.socketio.integration.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIONamespace;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.namespace.NamespaceTestReuseAssertions;
import com.socketio4j.socketio.transport.NamespaceClient;

/**
 * Attaches a test class to an immutable, profile-specific server fixture.
 * Every test and class boundary verifies that no client, room membership,
 * dynamic namespace, or listener has escaped. Cleanup is verified: clients
 * which intentionally exercise an abrupt or polling-only disconnect are
 * disconnected at the Engine.IO level, and the fixture fails if any state
 * remains afterwards.
 */
public abstract class AbstractSharedSocketIOIntegrationTest
        extends AbstractSocketIOIntegrationTest {

    private static final long DISCONNECT_SETTLE_TIMEOUT_MILLIS = 500L;
    private static final long FORCED_DISCONNECT_TIMEOUT_MILLIS = 1_000L;
    private static final long POLL_INTERVAL_MILLIS = 10L;

    private Set<String> baselineNamespaces;
    private Set<String> fixtureNamespaces;

    @Override
    protected final boolean reuseServerForTestClass() {
        return true;
    }

    /**
     * The profile is part of test semantics. Do not reuse a profile after
     * changing any server configuration that a client can observe.
     */
    protected SharedServerFixtureProfile sharedServerFixtureProfile() {
        return SharedServerFixtureProfile.DEFAULT_NIO;
    }

    /**
     * Server configuration is solely defined by the immutable fixture
     * profile, preventing a subclass from silently changing a shared server.
     */
    @Override
    protected final void configureServer(Configuration configuration) {
        sharedServerFixtureProfile().configure(configuration);
    }

    @Override
    protected final void initializeReusableServerFixture() throws Exception {
        SharedSocketIOServerFixtures.Fixture fixture =
                SharedSocketIOServerFixtures.fixture(sharedServerFixtureProfile());
        useServerFromTestFixture(fixture.server(), fixture.port());

        assertFixtureIsClean("before class setup");
        fixtureNamespaces = namespaceNames();
        configureNamespaces(getServer());
        baselineNamespaces = namespaceNames();
    }

    @Override
    protected final void beforeReusedServerTestCase() {
        if (baselineNamespaces == null) {
            throw new AssertionError("Shared server fixture baseline was not recorded");
        }
        if (!baselineNamespaces.equals(namespaceNames())) {
            throw new AssertionError("Shared server retained unexpected namespaces before test case. expected="
                    + baselineNamespaces + ", actual=" + namespaceNames());
        }
        assertFixtureIsClean("before test case");
    }

    @Override
    protected final void afterReusedServerTestCase() throws Exception {
        if (getServer() == null) {
            return;
        }

        if (!waitForNoClients(DISCONNECT_SETTLE_TIMEOUT_MILLIS)) {
            String retainedClients = describeConnectedClients();
            forceDisconnectClientHeads();

            if (!waitForNoClients(FORCED_DISCONNECT_TIMEOUT_MILLIS)) {
                throw new AssertionError(
                        "Shared fixture could not clean test-owned clients. before="
                                + retainedClients + "; remaining=" + describeConnectedClients());
            }
        }

        Throwable isolationFailure = null;
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

    /**
     * Removes class-level configuration such as a configured namespace. Only
     * a PER_CLASS subclass may call this from its {@code @AfterAll} callback.
     */
    protected final void restoreSharedServerFixtureAfterClass() throws Exception {
        if (getServer() == null) {
            return;
        }

        Throwable cleanupFailure = null;
        try {
            afterReusedServerTestCase();
            removeClassConfiguredNamespaces();
            assertFixtureIsClean("after class cleanup");
        } catch (Throwable failure) {
            cleanupFailure = failure;
        } finally {
            baselineNamespaces = null;
            fixtureNamespaces = null;
        }

        if (cleanupFailure != null) {
            rethrow(cleanupFailure);
        }
    }

    private void resetNamespaceStateAfterCase() {
        if (!baselineNamespaces.equals(namespaceNames())) {
            for (SocketIONamespace namespace :
                    new ArrayList<SocketIONamespace>(getServer().getAllNamespaces())) {
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
            throw new AssertionError("Shared server failed to remove test-created namespaces. expected="
                    + baselineNamespaces + ", actual=" + namespaceNames());
        }
    }

    private void removeClassConfiguredNamespaces() {
        if (fixtureNamespaces == null) {
            throw new AssertionError("Shared server fixture baseline was not recorded");
        }

        for (SocketIONamespace namespace :
                new ArrayList<SocketIONamespace>(getServer().getAllNamespaces())) {
            if (!fixtureNamespaces.contains(namespace.getName())) {
                getServer().removeNamespace(namespace.getName());
            }
        }

        if (!fixtureNamespaces.equals(namespaceNames())) {
            throw new AssertionError("Shared server class failed to restore fixture namespaces. expected="
                    + fixtureNamespaces + ", actual=" + namespaceNames());
        }
    }

    private void assertFixtureIsClean(String phase) {
        if (getServer() == null || !getServer().isStarted()) {
            throw new AssertionError("Shared server fixture is not running " + phase);
        }
        for (SocketIONamespace namespace : getServer().getAllNamespaces()) {
            NamespaceTestReuseAssertions.assertEmpty(namespace, phase);
            NamespaceTestReuseAssertions.assertNoListeners(namespace, phase);
        }
    }

    private Set<String> namespaceNames() {
        Set<String> names = new HashSet<String>();
        for (SocketIONamespace namespace : getServer().getAllNamespaces()) {
            names.add(namespace.getName());
        }
        return names;
    }

    private boolean waitForNoClients(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
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

    /**
     * {@link NamespaceClient#disconnect()} correctly defers polling teardown
     * until a client can receive the Socket.IO disconnect packet. A reusable
     * fixture cannot wait for that arbitrary client-side poll: doing so makes
     * the next test dependent on the prior one. Force the underlying
     * Engine.IO connection closed instead, then prove it has gone away.
     */
    private void forceDisconnectClientHeads() {
        Set<ClientHead> heads = new HashSet<ClientHead>();
        List<String> unsupportedClients = new ArrayList<String>();
        for (SocketIONamespace namespace : getServer().getAllNamespaces()) {
            for (SocketIOClient client : new ArrayList<SocketIOClient>(namespace.getAllClients())) {
                if (client instanceof NamespaceClient) {
                    heads.add(((NamespaceClient) client).getBaseClient());
                } else {
                    unsupportedClients.add(client.getClass().getName());
                }
            }
        }
        if (!unsupportedClients.isEmpty()) {
            throw new AssertionError("Shared fixture cannot force-disconnect unsupported clients: "
                    + unsupportedClients);
        }
        for (ClientHead head : heads) {
            head.disconnect();
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
}
