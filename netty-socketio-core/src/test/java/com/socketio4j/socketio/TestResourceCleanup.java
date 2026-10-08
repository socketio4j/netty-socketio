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
package com.socketio4j.socketio;

/**
 * Executes every test cleanup action and fails the test when any action fails.
 *
 * <p>Tests must not hide teardown failures: a failed shutdown can leak a port,
 * container, or background thread into the next test. Continuing after a
 * failure lets the remaining resources be released while preserving every
 * failure as evidence on the thrown assertion.</p>
 */
public final class TestResourceCleanup {

    @FunctionalInterface
    public interface ThrowingAction {
        void run() throws Exception;
    }

    private TestResourceCleanup() {
    }

    public static void runAll(String description, ThrowingAction... actions) {
        Throwable failure = null;
        for (ThrowingAction action : actions) {
            try {
                action.run();
            } catch (Throwable error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }

        if (failure != null) {
            throw new AssertionError(description + " failed", failure);
        }
    }
}
