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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive test suite for EngineIOVersion enum
 */
public class EngineIOVersionTest extends BaseProtocolTest {

    @Test
    public void testVersionValues() {
        // Test all version values
        assertEquals("2", EngineIOVersion.V2.getValue());
        assertEquals("3", EngineIOVersion.V3.getValue());
        assertEquals("4", EngineIOVersion.V4.getValue());
    }

    @Test
    public void testFromValueWithValidVersions() {
        // Test fromValue with valid version strings
        assertEquals(EngineIOVersion.V2, EngineIOVersion.fromValue("2"));
        assertEquals(EngineIOVersion.V3, EngineIOVersion.fromValue("3"));
        assertEquals(EngineIOVersion.V4, EngineIOVersion.fromValue("4"));
    }

    @Test
    public void testSupportedHandshakeVersions() {
        assertTrue(EngineIOVersion.isSupported("2"));
        assertTrue(EngineIOVersion.isSupported("3"));
        assertTrue(EngineIOVersion.isSupported("4"));
        assertTrue(!EngineIOVersion.isSupported("5"));
        assertTrue(!EngineIOVersion.isSupported(null));
    }

    @Test
    public void testFromValueWithInvalidVersions() {
        // Test fromValue with invalid version strings (defaults to V4)
        assertEquals(EngineIOVersion.V4, EngineIOVersion.fromValue("1"));
        assertEquals(EngineIOVersion.V4, EngineIOVersion.fromValue("5"));
        assertEquals(EngineIOVersion.V4, EngineIOVersion.fromValue("invalid"));
        assertEquals(EngineIOVersion.V4, EngineIOVersion.fromValue(""));
        assertEquals(EngineIOVersion.V4, EngineIOVersion.fromValue(null));
    }

    @Test
    public void testFromValueWithCaseSensitivity() {
        // Test fromValue fallback to V4 for non-matching case
        assertEquals(EngineIOVersion.V4, EngineIOVersion.fromValue("V2"));
        assertEquals(EngineIOVersion.V4, EngineIOVersion.fromValue("v2"));
    }

    @Test
    public void testEIOConstant() {
        // Test EIO constant
        assertEquals("EIO", EngineIOVersion.EIO);
    }

    @Test
    public void testVersionMapping() {
        // Test that all versions are properly mapped
        assertNotNull(EngineIOVersion.fromValue("2"));
        assertNotNull(EngineIOVersion.fromValue("3"));
        assertNotNull(EngineIOVersion.fromValue("4"));
        
        // Verify the mapping is consistent
        assertSame(EngineIOVersion.V2, EngineIOVersion.fromValue("2"));
        assertSame(EngineIOVersion.V3, EngineIOVersion.fromValue("3"));
        assertSame(EngineIOVersion.V4, EngineIOVersion.fromValue("4"));
    }

    @Test
    public void testVersionComparison() {
        // Test version comparison logic if needed
        assertNotEquals(EngineIOVersion.V2, EngineIOVersion.V3);
        assertNotEquals(EngineIOVersion.V3, EngineIOVersion.V4);
        assertNotEquals(EngineIOVersion.V2, EngineIOVersion.V4);
    }

    @Test
    public void testUnknownVersionFallbackBehavior() {
        // Test unknown version fallback behavior to V4
        EngineIOVersion unknown = EngineIOVersion.fromValue("999");
        assertEquals(EngineIOVersion.V4, unknown);
    }

    @Test
    public void testVersionStringRepresentation() {
        // Test string representation of versions
        assertTrue(EngineIOVersion.V2.getValue().matches("\\d+"));
        assertTrue(EngineIOVersion.V3.getValue().matches("\\d+"));
        assertTrue(EngineIOVersion.V4.getValue().matches("\\d+"));
    }

    @Test
    public void testVersionUniqueness() {
        // Test that all versions have unique values
        assertNotEquals(EngineIOVersion.V2.getValue(), EngineIOVersion.V3.getValue());
        assertNotEquals(EngineIOVersion.V3.getValue(), EngineIOVersion.V4.getValue());
        assertNotEquals(EngineIOVersion.V2.getValue(), EngineIOVersion.V4.getValue());
    }
}
