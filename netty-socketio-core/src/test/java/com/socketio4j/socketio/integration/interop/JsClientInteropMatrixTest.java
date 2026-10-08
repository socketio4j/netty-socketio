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

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsClientInteropMatrixTest {

    @Test
    void defaultsToTheFourVersionSmokeMatrix() {
        assertEquals(JsClientInteropMatrix.SMOKE_VERSIONS,
                JsClientInteropMatrix.resolveVersions(null));
        assertEquals(JsClientInteropMatrix.FULL_VERSIONS,
                JsClientInteropMatrix.resolveVersions("full"));
    }

    @Test
    void resolvesTheFourVersionSmokeMatrix() {
        assertEquals(Arrays.asList("1.7.3", "2.5.0", "3.1.3", "4.8.3"),
                JsClientInteropMatrix.resolveVersions("smoke"));
    }

    @Test
    void acceptsAnExplicitSupportedSubsetInTheRequestedOrder() {
        assertEquals(Arrays.asList("4.8.3", "1.7.3"),
                JsClientInteropMatrix.resolveVersions("4.8.3, 1.7.3"));
    }

    @Test
    void rejectsEmptyUnknownAndDuplicateSelections() {
        assertThrows(IllegalArgumentException.class,
                () -> JsClientInteropMatrix.resolveVersions("1.7.3,"));
        assertThrows(IllegalArgumentException.class,
                () -> JsClientInteropMatrix.resolveVersions("4.9.0"));
        assertThrows(IllegalArgumentException.class,
                () -> JsClientInteropMatrix.resolveVersions("4.8.3,4.8.3"));
    }
}
