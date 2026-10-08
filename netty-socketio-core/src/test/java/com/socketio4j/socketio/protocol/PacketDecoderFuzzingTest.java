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
package com.socketio4j.socketio.protocol;

import java.io.IOException;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.ack.AckManager;
import com.socketio4j.socketio.handler.ClientHead;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Fuzzing and robustness test suite for PacketDecoder.
 * Verifies parser stability against corrupted, truncated, malformed, and randomized ByteBuf inputs.
 */
public class PacketDecoderFuzzingTest extends BaseProtocolTest {

    private PacketDecoder decoder;
    private AutoCloseable closeableMocks;

    private JsonSupport jsonSupport;

    @Mock
    private AckManager ackManager;

    @Mock
    private ClientHead clientHead;

    @BeforeEach
    @Override
    public void setUp() {
        closeableMocks = MockitoAnnotations.openMocks(this);
        jsonSupport = new JacksonJsonSupport();
        decoder = new PacketDecoder(jsonSupport, ackManager);

        when(clientHead.getEngineIOVersion()).thenReturn(EngineIOVersion.V4);
        when(clientHead.getSessionId()).thenReturn(UUID.randomUUID());
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        closeableMocks.close();
    }

    @Test
    void testFuzzRandomByteArrays() {
        long[] seeds = {42L, 73L, 101L, 211L, 503L, 997L, 2027L, 7919L};
        for (long seed : seeds) {
            Random random = new Random(seed);
            for (int i = 0; i < 256; i++) {
                byte[] randomBytes = new byte[random.nextInt(256) + 1];
                random.nextBytes(randomBytes);

                ByteBuf buffer = Unpooled.copiedBuffer(randomBytes);
                try {
                    // Decoder should either parse or reject the input with a protocol parsing exception.
                    decoder.decodePackets(buffer, clientHead, Transport.POLLING);
                } catch (Exception expected) {
                    assertExpectedParsingException(expected);
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void testTruncatedJsonPayloads() {
        String[] truncatedInputs = {
                "42[\"event_name\",",
                "42/admin,123[\"event\",{\"key\":",
                "40/admin,{\"auth\":",
                "43/admin,999[\"ack\",",
                "44/admin,{\"message\":"
        };

        for (String truncated : truncatedInputs) {
            ByteBuf buffer = Unpooled.copiedBuffer(truncated, CharsetUtil.UTF_8);
            try {
                decoder.decodePackets(buffer, clientHead);
            } catch (Exception e) {
                // Expected handled parsing exception for truncated payloads
                assertExpectedParsingException(e);
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void testMalformedHeaderDividers() {
        String[] malformedHeaders = {
                "45abc-/admin,123[\"event\"]",
                "45-999999999999999999999999-/admin,123[\"event\"]",
                "42/admin,abc999[\"event\"]",
                "40/admin,extra,comma,{\"token\":\"123\"}"
        };

        for (String header : malformedHeaders) {
            ByteBuf buffer = Unpooled.copiedBuffer(header, CharsetUtil.UTF_8);
            try {
                decoder.decodePackets(buffer, clientHead);
            } catch (Exception e) {
                assertExpectedParsingException(e);
            } finally {
                buffer.release();
            }
        }
    }

    @ParameterizedTest(name = "Fuzz Invalid Outer Packet Type Byte {0}")
    @EnumSource(value = EngineIOVersion.class, names = {"V2", "V3", "V4"})
    void testInvalidOuterPacketTypeBytes(EngineIOVersion version) {
        when(clientHead.getEngineIOVersion()).thenReturn(version);

        String[] invalidNumericTypes = {"7", "8", "9"};
        for (String type : invalidNumericTypes) {
            ByteBuf buffer = Unpooled.copiedBuffer(type + "data", CharsetUtil.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> decoder.decodePackets(buffer, clientHead));
            buffer.release();
        }
    }

    private static void assertExpectedParsingException(Exception exception) {
        assertTrue(exception instanceof IOException
                        || exception instanceof IllegalArgumentException
                        || exception instanceof IllegalStateException,
                () -> "Unexpected exception type: " + exception.getClass().getName());
    }
}
