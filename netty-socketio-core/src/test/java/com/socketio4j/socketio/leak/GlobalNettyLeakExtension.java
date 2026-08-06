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
package com.socketio4j.socketio.leak;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.netty.buffer.ByteBuf;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Global JUnit 5 Extension that enforces zero Netty ByteBuf memory leaks across all test execution.
 */
public class GlobalNettyLeakExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    private static final AtomicBoolean leakDetected = new AtomicBoolean(false);
    private static final AtomicReference<String> leakDetails = new AtomicReference<>("");
    private static final AtomicBoolean active = new AtomicBoolean(false);
    private static ResourceLeakDetector.Level previousLevel;
    private static ResourceLeakDetectorFactory previousFactory;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (active.compareAndSet(false, true)) {
            previousLevel = ResourceLeakDetector.getLevel();
            previousFactory = ResourceLeakDetectorFactory.instance();
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
            ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(
                    new ResourceLeakDetectorFactory() {
                        @Override
                        public <T> ResourceLeakDetector<T> newResourceLeakDetector(Class<T> resource, int samplingInterval, long maxActive) {
                            ResourceLeakDetector<T> detector = new ResourceLeakDetector<>(resource, samplingInterval, maxActive);
                            detector.setLeakListener((resourceType, records) -> {
                                leakDetected.set(true);
                                leakDetails.set("Resource leak detected in " + resourceType + ": " + records);
                            });
                            return detector;
                        }
                    });
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        leakDetected.set(false);
        leakDetails.set("");
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        // Force GC & phantom reference processing
        for (int attempt = 0; attempt < 10; attempt++) {
            System.gc();
            System.runFinalization();
            // Allocate and release dummy buffer to trigger Netty's internal reference queue polling
            ByteBuf dummy = io.netty.buffer.Unpooled.buffer(1);
            dummy.release();
            Thread.sleep(30);
            if (leakDetected.get()) {
                break;
            }
        }

        assertFalse(leakDetected.get(),
                () -> "Global Netty ByteBuf Resource Leak Detected during test: " +
                        context.getDisplayName() + ". Details: " + leakDetails.get());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (active.compareAndSet(true, false)) {
            if (previousLevel != null) {
                ResourceLeakDetector.setLevel(previousLevel);
            }
            if (previousFactory != null) {
                ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(previousFactory);
            }
            leakDetected.set(false);
            leakDetails.set("");
        }
    }
}
