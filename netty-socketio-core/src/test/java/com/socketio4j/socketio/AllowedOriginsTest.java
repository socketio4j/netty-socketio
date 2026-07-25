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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Allowed origins matching")
class AllowedOriginsTest {

    private final Configuration configuration = new Configuration();

    @Test
    @DisplayName("Should allow any origin when no allow list is configured")
    void shouldAllowAnyOriginByDefault() {
        assertThat(configuration.isOriginAllowed("http://evil.example")).isTrue();
        assertThat(configuration.isOriginAllowed(null)).isTrue();
    }

    @Test
    @DisplayName("Should match exact origins only")
    void shouldMatchExactOrigins() {
        configuration.setAllowedOrigins(Collections.singleton("https://app.example.com"));

        assertThat(configuration.isOriginAllowed("https://app.example.com")).isTrue();
        assertThat(configuration.isOriginAllowed("http://app.example.com")).isFalse();
        assertThat(configuration.isOriginAllowed("https://app.example.com:8080")).isFalse();
        assertThat(configuration.isOriginAllowed("https://evil.example")).isFalse();
        assertThat(configuration.isOriginAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("Should match wildcard subdomain patterns")
    void shouldMatchWildcardSubdomains() {
        configuration.setAllowedOrigins(new HashSet<>(Arrays.asList(
                "https://*.example.com", "https://*.example2.com")));

        assertThat(configuration.isOriginAllowed("https://app.example.com")).isTrue();
        assertThat(configuration.isOriginAllowed("https://a.b.example.com")).isTrue();
        assertThat(configuration.isOriginAllowed("https://app.example2.com")).isTrue();

        assertThat(configuration.isOriginAllowed("https://example.com")).isFalse();
        assertThat(configuration.isOriginAllowed("http://app.example.com")).isFalse();
        assertThat(configuration.isOriginAllowed("https://example.com.evil.test")).isFalse();
        assertThat(configuration.isOriginAllowed("https://app.example.com.evil.test")).isFalse();
    }

    @Test
    @DisplayName("Should match wildcard ports")
    void shouldMatchWildcardPorts() {
        configuration.setAllowedOrigins(Collections.singleton("http://localhost:*"));

        assertThat(configuration.isOriginAllowed("http://localhost:3000")).isTrue();
        assertThat(configuration.isOriginAllowed("http://localhost:8080")).isTrue();
        assertThat(configuration.isOriginAllowed("http://localhost")).isFalse();
        assertThat(configuration.isOriginAllowed("http://evil.test:3000")).isFalse();
    }

    @Test
    @DisplayName("Should reset patterns when the allow list is cleared")
    void shouldResetPatterns() {
        configuration.setAllowedOrigins(Collections.singleton("https://*.example.com"));
        configuration.setAllowedOrigins(Collections.emptySet());

        assertThat(configuration.getAllowedOrigins()).isEmpty();
        assertThat(configuration.isOriginAllowed("https://evil.example")).isTrue();
    }
}
