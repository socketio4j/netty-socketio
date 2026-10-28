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
package com.socketio4j.socketio.ack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.AckCallback;
import com.socketio4j.socketio.MultiTypeAckCallback;
import com.socketio4j.socketio.MultiTypeArgs;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.handler.ClientHead;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;
import com.socketio4j.socketio.scheduler.CancelableScheduler;
import com.socketio4j.socketio.scheduler.SchedulerKey;

import io.netty.channel.ChannelHandlerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AckManager Tests")
class AckManagerTest {

    /**
     * Scheduler recording every interaction and allowing manual firing of
     * scheduled callbacks.
     */
    private static class RecordingScheduler implements CancelableScheduler {

        private final List<SchedulerKey> scheduled = new ArrayList<>();
        private final List<SchedulerKey> cancelled = new ArrayList<>();
        private final List<Runnable> callbacks = new ArrayList<>();

        @Override
        public void update(ChannelHandlerContext ctx) {
        }

        @Override
        public void cancel(SchedulerKey key) {
            cancelled.add(key);
        }

        @Override
        public void scheduleCallback(SchedulerKey key, Runnable runnable, long delay, TimeUnit unit) {
            scheduled.add(key);
            callbacks.add(runnable);
        }

        @Override
        public void schedule(Runnable runnable, long delay, TimeUnit unit) {
        }

        @Override
        public void schedule(SchedulerKey key, Runnable runnable, long delay, TimeUnit unit) {
        }

        @Override
        public void shutdown() {
        }

        void fireAll() {
            for (Runnable callback : new ArrayList<>(callbacks)) {
                callback.run();
            }
        }
    }

    private RecordingScheduler scheduler;
    private AckManager ackManager;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingScheduler();
        ackManager = new AckManager(scheduler);
        sessionId = UUID.randomUUID();
    }

    private static Packet ackPacket(long ackId, List<Object> data) {
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.ACK);
        packet.setAckId(ackId);
        packet.setData(data);
        return packet;
    }

    private SocketIOClient client() {
        SocketIOClient client = mock(SocketIOClient.class);
        when(client.getSessionId()).thenReturn(sessionId);
        return client;
    }

    private static AckCallback<String> callback(AtomicReference<String> result, int timeout) {
        return new AckCallback<String>(String.class, timeout) {
            @Override
            public void onSuccess(String value) {
                result.set(value);
            }
        };
    }

    @Test
    @DisplayName("Should register callbacks with incrementing indexes starting from 1")
    void shouldRegisterCallbacksWithIncrementingIndexes() {
        AtomicReference<String> result = new AtomicReference<>();

        assertThat(ackManager.registerAck(sessionId, callback(result, -1))).isEqualTo(1);
        assertThat(ackManager.registerAck(sessionId, callback(result, -1))).isEqualTo(2);
    }

    @Test
    @DisplayName("Should return registered callback by index and null for unknown index")
    void shouldReturnRegisteredCallback() {
        AtomicReference<String> result = new AtomicReference<>();
        AckCallback<String> callback = callback(result, -1);

        long index = ackManager.registerAck(sessionId, callback);

        assertThat(ackManager.getCallback(sessionId, index)).isSameAs(callback);
        assertThat(ackManager.getCallback(sessionId, index + 1)).isNull();
        assertThat(ackManager.getCallback(UUID.randomUUID(), index)).isNull();
    }

    @Test
    @DisplayName("Should start ack index at the initialized value")
    void shouldStartAckIndexAtInitializedValue() {
        ackManager.initAckIndex(sessionId, 5);

        AtomicReference<String> result = new AtomicReference<>();
        assertThat(ackManager.registerAck(sessionId, callback(result, -1))).isEqualTo(6);
    }

    @Test
    @DisplayName("Should keep the first initialized ack index")
    void shouldKeepFirstInitializedAckIndex() {
        ackManager.initAckIndex(sessionId, 5);
        ackManager.initAckIndex(sessionId, 100);

        AtomicReference<String> result = new AtomicReference<>();
        assertThat(ackManager.registerAck(sessionId, callback(result, -1))).isEqualTo(6);
    }

    @Test
    @DisplayName("Should not schedule a timeout for callbacks without timeout")
    void shouldNotScheduleTimeoutWhenTimeoutIsNotSet() {
        ackManager.registerAck(sessionId, callback(new AtomicReference<>(), -1));

        assertThat(scheduler.scheduled).isEmpty();
    }

    @Test
    @DisplayName("Should schedule an ack timeout for callbacks with timeout")
    void shouldScheduleTimeoutWhenTimeoutIsSet() {
        long index = ackManager.registerAck(sessionId, callback(new AtomicReference<>(), 10));

        assertThat(scheduler.scheduled)
                .containsExactly(new AckSchedulerKey(SchedulerKey.Type.ACK_TIMEOUT, sessionId, index));
    }

    @Test
    @DisplayName("Should pass the first ack argument to a single type callback")
    void shouldPassFirstArgumentToCallback() {
        AtomicReference<String> result = new AtomicReference<>();
        long index = ackManager.registerAck(sessionId, callback(result, -1));

        ackManager.onAck(client(), ackPacket(index, Collections.singletonList("data")));

        assertThat(result.get()).isEqualTo("data");
        assertThat(ackManager.getCallback(sessionId, index)).isNull();
    }

    @Test
    @DisplayName("Should pass null to a single type callback when ack has no arguments")
    void shouldPassNullWhenAckHasNoArguments() {
        AtomicReference<String> result = new AtomicReference<>("unset");
        long index = ackManager.registerAck(sessionId, callback(result, -1));

        ackManager.onAck(client(), ackPacket(index, Collections.emptyList()));

        assertThat(result.get()).isNull();
    }

    @Test
    @DisplayName("Should pass all ack arguments to a multi type callback")
    void shouldPassAllArgumentsToMultiTypeCallback() {
        AtomicReference<MultiTypeArgs> result = new AtomicReference<>();
        MultiTypeAckCallback callback = new MultiTypeAckCallback(String.class, Integer.class) {
            @Override
            public void onSuccess(MultiTypeArgs args) {
                result.set(args);
            }
        };
        long index = ackManager.registerAck(sessionId, callback);

        ackManager.onAck(client(), ackPacket(index, Arrays.asList("first", 2)));

        assertThat(result.get().getArgs()).containsExactly("first", 2);
    }

    @Test
    @DisplayName("Should cancel the scheduled timeout when the ack is received")
    void shouldCancelTimeoutOnAck() {
        long index = ackManager.registerAck(sessionId, callback(new AtomicReference<>(), 10));

        ackManager.onAck(client(), ackPacket(index, Collections.singletonList("data")));

        assertThat(scheduler.cancelled)
                .containsExactly(new AckSchedulerKey(SchedulerKey.Type.ACK_TIMEOUT, sessionId, index));
    }

    @Test
    @DisplayName("Should ignore acks without a registered callback")
    void shouldIgnoreUnknownAck() {
        ackManager.onAck(client(), ackPacket(42, Collections.singletonList("data")));

        assertThat(scheduler.cancelled).hasSize(1);
    }

    @Test
    @DisplayName("Should invoke onTimeout only once when the scheduled timeout fires")
    void shouldInvokeTimeoutCallbackOnce() {
        AtomicReference<Integer> timeouts = new AtomicReference<>(0);
        AckCallback<String> callback = new AckCallback<String>(String.class, 1) {
            @Override
            public void onSuccess(String result) {
            }

            @Override
            public void onTimeout() {
                timeouts.set(timeouts.get() + 1);
            }
        };
        long index = ackManager.registerAck(sessionId, callback);

        scheduler.fireAll();
        scheduler.fireAll();

        assertThat(timeouts.get()).isEqualTo(1);
        assertThat(ackManager.getCallback(sessionId, index)).isNull();
    }

    @Test
    @DisplayName("Should time out pending callbacks on disconnect")
    void shouldTimeoutPendingCallbacksOnDisconnect() {
        AtomicReference<Boolean> timedOut = new AtomicReference<>(false);
        AckCallback<String> callback = new AckCallback<String>(String.class, 10) {
            @Override
            public void onSuccess(String result) {
            }

            @Override
            public void onTimeout() {
                timedOut.set(true);
            }
        };
        long index = ackManager.registerAck(sessionId, callback);

        ClientHead clientHead = mock(ClientHead.class);
        when(clientHead.getSessionId()).thenReturn(sessionId);
        ackManager.onDisconnect(clientHead);

        assertThat(timedOut.get()).isTrue();
        assertThat(scheduler.cancelled)
                .contains(new AckSchedulerKey(SchedulerKey.Type.ACK_TIMEOUT, sessionId, index));
        assertThat(ackManager.getCallback(sessionId, index)).isNull();
    }

    @Test
    @DisplayName("Should do nothing on disconnect of an unknown client")
    void shouldDoNothingOnDisconnectOfUnknownClient() {
        ClientHead clientHead = mock(ClientHead.class);
        when(clientHead.getSessionId()).thenReturn(UUID.randomUUID());

        ackManager.onDisconnect(clientHead);

        assertThat(scheduler.cancelled).isEmpty();
    }
}
