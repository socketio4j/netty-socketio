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

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * Base test class for Namespace tests providing shared thread pool and utility methods.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractNamespaceTestSupport {

    protected ExecutorService sharedExecutor;
    protected static final int DEFAULT_TASK_COUNT = 10;
    protected static final int DEFAULT_TIMEOUT_SECONDS = 5;
    private final Map<CountDownLatch, Queue<Throwable>> taskFailures = new ConcurrentHashMap<>();

    @BeforeAll
    void setUpSharedResources() {
        sharedExecutor = Executors.newFixedThreadPool(DEFAULT_TASK_COUNT);
    }

    @AfterAll
    void tearDownSharedResources() throws InterruptedException {
        if (sharedExecutor != null) {
            sharedExecutor.shutdown();
            if (!sharedExecutor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                sharedExecutor.shutdownNow();
                if (!sharedExecutor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent test executor did not terminate");
                }
            }
        }
    }

    /**
     * Execute concurrent operations using the shared thread pool.
     *
     * @param taskCount number of tasks to execute concurrently
     * @param operation the operation to execute in each task
     * @return the countdown latch for synchronization
     */
    protected CountDownLatch executeConcurrentOperations(int taskCount, Runnable operation) {
        CountDownLatch latch = new CountDownLatch(taskCount);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        taskFailures.put(latch, failures);

        for (int i = 0; i < taskCount; i++) {
            sharedExecutor.submit(() -> {
                try {
                    operation.run();
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    latch.countDown();
                }
            });
        }

        return latch;
    }

    /**
     * Execute concurrent operations with index using the shared thread pool.
     *
     * @param taskCount number of tasks to execute concurrently
     * @param operation the operation to execute in each task with index
     * @return the countdown latch for synchronization
     */
    protected CountDownLatch executeConcurrentOperationsWithIndex(int taskCount, IntConsumer operation) {
        CountDownLatch latch = new CountDownLatch(taskCount);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        taskFailures.put(latch, failures);

        for (int i = 0; i < taskCount; i++) {
            final int index = i;
            sharedExecutor.submit(() -> {
                try {
                    operation.accept(index);
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    latch.countDown();
                }
            });
        }

        return latch;
    }

    /**
     * Wait for a countdown latch to reach zero with default timeout.
     *
     * @param latch the countdown latch to wait for
     * @throws InterruptedException if thread is interrupted while waiting
     */
    protected void waitForCompletion(CountDownLatch latch) throws InterruptedException {
        boolean completed = latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            throw new RuntimeException("Concurrent operations did not complete within " + DEFAULT_TIMEOUT_SECONDS + " seconds");
        }

        Queue<Throwable> failures = taskFailures.remove(latch);
        if (failures != null && !failures.isEmpty()) {
            AssertionError failure = new AssertionError(
                    "Concurrent operation failed in " + failures.size() + " worker(s)");
            for (Throwable error : failures) {
                failure.addSuppressed(error);
            }
            throw failure;
        }
    }
}
