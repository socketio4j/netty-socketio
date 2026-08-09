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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Ack callback Tests")
class AckCallbackTest {

    @Nested
    @DisplayName("AckCallback Tests")
    class AckCallbackTests {

        @Test
        @DisplayName("Should default to no timeout and expose the result class")
        void shouldDefaultToNoTimeout() {
            AckCallback<String> callback = new AckCallback<String>(String.class) {
                @Override
                public void onSuccess(String result) {
                }
            };

            assertThat(callback.getTimeout()).isEqualTo(-1);
            assertThat(callback.getResultClass()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("Should keep the configured timeout and ignore onTimeout by default")
        void shouldKeepConfiguredTimeout() {
            AckCallback<String> callback = new AckCallback<String>(String.class, 30) {
                @Override
                public void onSuccess(String result) {
                }
            };

            callback.onTimeout();

            assertThat(callback.getTimeout()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("VoidAckCallback Tests")
    class VoidAckCallbackTests {

        @Test
        @DisplayName("Should delegate onSuccess to the no argument variant")
        void shouldDelegateOnSuccess() {
            AtomicInteger invocations = new AtomicInteger();
            VoidAckCallback callback = new VoidAckCallback() {
                @Override
                protected void onSuccess() {
                    invocations.incrementAndGet();
                }
            };

            callback.onSuccess(null);

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(callback.getResultClass()).isEqualTo(Void.class);
            assertThat(callback.getTimeout()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should keep the configured timeout")
        void shouldKeepConfiguredTimeout() {
            VoidAckCallback callback = new VoidAckCallback(15) {
                @Override
                protected void onSuccess() {
                }
            };

            assertThat(callback.getTimeout()).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("MultiTypeAckCallback Tests")
    class MultiTypeAckCallbackTests {

        @Test
        @DisplayName("Should expose the argument classes")
        void shouldExposeResultClasses() {
            MultiTypeAckCallback callback = new MultiTypeAckCallback(String.class, Integer.class) {
                @Override
                public void onSuccess(MultiTypeArgs result) {
                }
            };

            assertThat(callback.getResultClasses()).containsExactly(String.class, Integer.class);
            assertThat(callback.getResultClass()).isEqualTo(MultiTypeArgs.class);
        }
    }

    @Nested
    @DisplayName("MultiTypeArgs Tests")
    class MultiTypeArgsTests {

        @Test
        @DisplayName("Should expose size, emptiness and the backing list")
        void shouldExposeSizeAndArgs() {
            List<Object> args = new ArrayList<>();
            args.add("first");
            args.add(2);
            MultiTypeArgs multiTypeArgs = new MultiTypeArgs(args);

            assertThat(multiTypeArgs.size()).isEqualTo(2);
            assertThat(multiTypeArgs.isEmpty()).isFalse();
            assertThat(multiTypeArgs.getArgs()).isSameAs(args);
            assertThat(multiTypeArgs).containsExactly("first", 2);
        }

        @Test
        @DisplayName("Should be empty for an empty argument list")
        void shouldBeEmptyForEmptyList() {
            MultiTypeArgs multiTypeArgs = new MultiTypeArgs(new ArrayList<>());

            assertThat(multiTypeArgs.isEmpty()).isTrue();
            assertThat(multiTypeArgs.size()).isZero();
        }

        @Test
        @DisplayName("Should return null instead of throwing for out of bounds indexes")
        void shouldReturnNullForOutOfBoundsIndex() {
            List<Object> args = new ArrayList<>();
            args.add("only");
            MultiTypeArgs multiTypeArgs = new MultiTypeArgs(args);

            assertThat(multiTypeArgs.<String>first()).isEqualTo("only");
            assertThat(multiTypeArgs.<String>second()).isNull();
            assertThat(multiTypeArgs.<String>get(10)).isNull();
        }
    }

    @Nested
    @DisplayName("BroadcastAckCallback Tests")
    class BroadcastAckCallbackTests {

        @Test
        @DisplayName("Should notify all success once every client acknowledged after the loop finished")
        void shouldNotifyAllSuccessAfterLoopFinished() {
            List<SocketIOClient> successClients = new ArrayList<>();
            AtomicInteger allSuccessInvocations = new AtomicInteger();
            BroadcastAckCallback<String> callback = new BroadcastAckCallback<String>(String.class) {
                @Override
                protected void onClientSuccess(SocketIOClient client, String result) {
                    successClients.add(client);
                }

                @Override
                protected void onAllSuccess() {
                    allSuccessInvocations.incrementAndGet();
                }
            };

            SocketIOClient firstClient = mock(SocketIOClient.class);
            SocketIOClient secondClient = mock(SocketIOClient.class);
            AckCallback<String> first = callback.createClientCallback(firstClient);
            AckCallback<String> second = callback.createClientCallback(secondClient);

            first.onSuccess("one");
            assertThat(allSuccessInvocations.get()).isZero();

            callback.loopFinished();
            assertThat(allSuccessInvocations.get()).isZero();

            second.onSuccess("two");

            assertThat(successClients).containsExactly(firstClient, secondClient);
            assertThat(allSuccessInvocations.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should notify all success immediately when there is no client to wait for")
        void shouldNotifyAllSuccessWithoutClients() {
            AtomicInteger allSuccessInvocations = new AtomicInteger();
            BroadcastAckCallback<String> callback = new BroadcastAckCallback<String>(String.class, 10) {
                @Override
                protected void onAllSuccess() {
                    allSuccessInvocations.incrementAndGet();
                }
            };

            callback.loopFinished();
            callback.loopFinished();

            assertThat(allSuccessInvocations.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should propagate the timeout to the client callbacks")
        void shouldPropagateTimeoutToClientCallbacks() {
            List<SocketIOClient> timedOutClients = new ArrayList<>();
            BroadcastAckCallback<String> callback = new BroadcastAckCallback<String>(String.class, 25) {
                @Override
                protected void onClientTimeout(SocketIOClient client) {
                    timedOutClients.add(client);
                }
            };

            SocketIOClient client = mock(SocketIOClient.class);
            AckCallback<String> clientCallback = callback.createClientCallback(client);
            clientCallback.onTimeout();

            assertThat(clientCallback.getTimeout()).isEqualTo(25);
            assertThat(clientCallback.getResultClass()).isEqualTo(String.class);
            assertThat(timedOutClients).containsExactly(client);
        }

        @Test
        @DisplayName("Should not notify all success while a client ack is still pending")
        void shouldNotNotifyAllSuccessWhileAckPending() {
            AtomicInteger allSuccessInvocations = new AtomicInteger();
            BroadcastAckCallback<String> callback = new BroadcastAckCallback<String>(String.class) {
                @Override
                protected void onAllSuccess() {
                    allSuccessInvocations.incrementAndGet();
                }
            };

            callback.createClientCallback(mock(SocketIOClient.class));
            callback.loopFinished();

            assertThat(allSuccessInvocations.get()).isZero();
        }
    }
}
