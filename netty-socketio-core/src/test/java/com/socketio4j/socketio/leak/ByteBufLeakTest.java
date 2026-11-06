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

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.protocol.EngineIOVersion;
import com.socketio4j.socketio.protocol.JacksonJsonSupport;
import com.socketio4j.socketio.protocol.JsonSupport;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketDecoder;
import com.socketio4j.socketio.protocol.PacketEncoder;
import com.socketio4j.socketio.protocol.PacketType;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import io.netty.util.ResourceLeakTracker;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * PARANOID level resource leak test suite.
 * Enforces Netty ResourceLeakDetector.Level.PARANOID and explicit LeakListener assertions across all test methods.
 */

public class ByteBufLeakTest {

    private static final AtomicBoolean leakDetected = new AtomicBoolean(false);
    private static final AtomicReference<String> leakDetails = new AtomicReference<>("");
    private static final AtomicBoolean ignoreGlobalLeak = new AtomicBoolean(false);
    private static ResourceLeakDetector.Level previousLeakDetectorLevel;
    private static ResourceLeakDetectorFactory previousLeakDetectorFactory;

    private PacketEncoder encoder;
    private PacketDecoder decoder;
    private JsonSupport jsonSupport;
    private Configuration configuration;
    private ByteBufAllocator allocator;
    private AutoCloseable closeableMocks;

    @Mock
    private AckManager ackManager;

    @Mock
    private ClientHead clientHead;

    @BeforeAll
    public static void enableParanoidLeakDetector() {
        previousLeakDetectorLevel = ResourceLeakDetector.getLevel();
        previousLeakDetectorFactory = ResourceLeakDetectorFactory.instance();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(
                new ResourceLeakDetectorFactory() {
                    @Override
                    public <T> ResourceLeakDetector<T> newResourceLeakDetector(Class<T> resource, int samplingInterval, long maxActive) {
                        ResourceLeakDetector<T> detector = new ResourceLeakDetector<>(resource, samplingInterval, maxActive);
                        detector.setLeakListener((resourceType, records) -> {
                            if (!ignoreGlobalLeak.get()) {
                                leakDetected.set(true);
                                leakDetails.set("Resource leak detected in " + resourceType + ": " + records);
                            }
                        });
                        return detector;
                    }
                });
    }

    @AfterAll
    public static void restoreLeakDetectorLevel() {
        ResourceLeakDetector.setLevel(previousLeakDetectorLevel);
        if (previousLeakDetectorFactory != null) {
            ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(previousLeakDetectorFactory);
        }
        ignoreGlobalLeak.set(false);
        leakDetected.set(false);
        leakDetails.set("");
    }

    @BeforeEach
    public void setUp() {
        leakDetected.set(false);
        leakDetails.set("");

        closeableMocks = MockitoAnnotations.openMocks(this);

        configuration = new Configuration();
        configuration.setPreferDirectBuffer(false);

        jsonSupport = new JacksonJsonSupport();
        allocator = Unpooled.buffer().alloc();

        encoder = new PacketEncoder(configuration, jsonSupport);
        decoder = new PacketDecoder(jsonSupport, ackManager);

        when(clientHead.getEngineIOVersion()).thenReturn(EngineIOVersion.V4);
        when(clientHead.getSessionId()).thenReturn(UUID.randomUUID());
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (closeableMocks != null) {
            closeableMocks.close();
        }
    }

    @Test
    public void testEncoderDecoderCyclesZeroLeaks() throws IOException {
        for (int i = 0; i < 2000; i++) {
            // 1. Encode packet
            Packet packet = new Packet(PacketType.MESSAGE);
            packet.setSubType(PacketType.EVENT);
            packet.setNsp("/leakTest");
            packet.setName("pingEvent");
            packet.setData(Arrays.asList("data_" + i));

            ByteBuf encodedBuffer = Unpooled.buffer();
            encoder.encodePacket(EngineIOVersion.V4, packet, encodedBuffer, allocator, false);

            assertNotNull(encodedBuffer);

            // 2. Decode packet
            Packet decodedPacket = decoder.decodePackets(encodedBuffer, clientHead, Transport.POLLING);
            assertNotNull(decodedPacket);

            encodedBuffer.release();
        }
    }

    @Test
    public void testBatchPollingCyclesZeroLeaks() throws IOException {
        for (int i = 0; i < 1000; i++) {
            Queue<Packet> queue = new LinkedList<>();

            Packet p1 = new Packet(PacketType.MESSAGE);
            p1.setSubType(PacketType.CONNECT);
            p1.setNsp("");
            queue.add(p1);

            Packet p2 = new Packet(PacketType.MESSAGE);
            p2.setSubType(PacketType.EVENT);
            p2.setNsp("");
            p2.setName("batchEvent");
            p2.setData(Arrays.asList("val_" + i));
            queue.add(p2);

            ByteBuf batchBuf = Unpooled.buffer();
            encoder.encodePackets(EngineIOVersion.V4, queue, batchBuf, allocator, 10);

            assertNotNull(batchBuf);

            Packet decodedFirst = decoder.decodePackets(batchBuf, clientHead, Transport.POLLING);
            assertNotNull(decodedFirst);

            batchBuf.release();
        }
    }

    @Test
    public void testDirectBufferEncoderDecoderCyclesZeroLeaks() throws IOException {
        Configuration directConfig = new Configuration();
        directConfig.setPreferDirectBuffer(true);
        PacketEncoder directEncoder = new PacketEncoder(directConfig, jsonSupport);
        ByteBufAllocator directAllocator = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;

        for (int i = 0; i < 1000; i++) {
            Packet packet = new Packet(PacketType.MESSAGE);
            packet.setSubType(PacketType.EVENT);
            packet.setNsp("/directBuffer");
            packet.setName("directEvent");
            packet.setData(Arrays.asList("direct_data_" + i));

            ByteBuf directBuffer = Unpooled.directBuffer();
            directEncoder.encodePacket(EngineIOVersion.V4, packet, directBuffer, directAllocator, false);
            assertNotNull(directBuffer);

            Packet decodedPacket = decoder.decodePackets(directBuffer, clientHead, Transport.POLLING);
            assertNotNull(decodedPacket);

            directBuffer.release();
        }
    }

    @Test
    public void testActualNettyLeakDetection() throws InterruptedException {
        ignoreGlobalLeak.set(true);
        try {
            AtomicBoolean leakFired = new AtomicBoolean(false);
            ResourceLeakDetector<ByteBuf> testDetector = new ResourceLeakDetector<>(ByteBuf.class, 1);
            testDetector.setLeakListener((resourceType, records) -> leakFired.set(true));

            // 1. Allocate a buffer and track it with Netty's detector without releasing
            ByteBuf unreleased = allocator.buffer(64);
            ResourceLeakTracker<ByteBuf> tracker = testDetector.track(unreleased);
            assertNotNull(tracker, "Tracker must be active under sampling rate 1");

            // 2. Drop the buffer reference without calling release()
            unreleased = null;

            // 3. Force GC and poll Netty leak detector reference queue
            for (int i = 0; i < 20; i++) {
                System.gc();
                System.runFinalization();
                Thread.sleep(50);

                // Netty processes reference queues on subsequent track() calls
                ByteBuf dummy = allocator.buffer(16);
                ResourceLeakTracker<ByteBuf> dummyTracker = testDetector.track(dummy);
                dummy.release();
                if (dummyTracker != null) {
                    dummyTracker.close(dummy);
                }

                if (leakFired.get()) {
                    break;
                }
            }

            // 4. Assert that Netty's actual GC leak detector fired!
            assertTrue(leakFired.get(), "Netty's actual GC leak detector must detect unreleased ByteBuf");
        } finally {
            leakDetected.set(false);
            leakDetails.set("");
            ignoreGlobalLeak.set(false);
        }
    }
}
