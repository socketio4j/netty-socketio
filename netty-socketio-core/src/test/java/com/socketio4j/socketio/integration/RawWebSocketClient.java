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
package com.socketio4j.socketio.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Raw wire peer: unlike Socket.IO clients it can deliberately withhold heartbeats. */
public final class RawWebSocketClient implements AutoCloseable {
    private final OkHttpClient http = new OkHttpClient();
    private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final BlockingQueue<byte[]> binaryFrames = new LinkedBlockingQueue<>();
    private final java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
    private final WebSocket socket;
    private final String sessionId;

    public RawWebSocketClient(int port, boolean respondToPing) throws Exception {
        this(port, respondToPing, null);
    }

    public RawWebSocketClient(int port, boolean respondToPing, String existingSession) throws Exception {
        socket = http.newWebSocket(new Request.Builder()
                .url("ws://127.0.0.1:" + port + "/socket.io/?EIO=4&transport=websocket"
                        + (existingSession == null ? "" : "&sid=" + existingSession)).build(),
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket peer, Response response) {
                        opened.countDown();
                    }

                    @Override
                    public void onMessage(WebSocket peer, ByteString bytes) {
                        binaryFrames.add(bytes.toByteArray());
                    }

                    @Override
                    public void onMessage(WebSocket peer, String text) {
                        frames.add(text);
                        if (respondToPing && "2".equals(text)) {
                            if (!peer.send("3")) {
                                failure.compareAndSet(null, new IllegalStateException("PONG write rejected"));
                            }
                        }
                    }

                    @Override
                    public void onFailure(WebSocket peer, Throwable error, Response response) {
                        failure.compareAndSet(null, error);
                    }
                });
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS), "Raw WebSocket must actually open");
            if (existingSession == null) {
                String open = awaitFrame(frame -> frame.startsWith("0{"));
                sessionId = new ObjectMapper().readTree(open.substring(1)).get("sid").asText();
                send("40");
                awaitFrame(frame -> frame.startsWith("40{"));
            } else {
                sessionId = existingSession;
            }
        } catch (Exception | AssertionError error) {
            close();
            throw error;
        }
    }

    public byte[] awaitBinaryFrame() throws InterruptedException {
        byte[] bytes = binaryFrames.poll(5, TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertNotNull(bytes, "Expected a real binary frame");
        return bytes;
    }

    public String sessionId() {
        return sessionId;
    }

    public void send(String frame) {
        assertTrue(socket.send(frame), "Raw peer rejected text write: " + frame);
    }

    public void send(byte[] frame) {
        assertTrue(socket.send(ByteString.of(frame)), "Raw peer rejected binary write");
    }

    public String awaitFrame(Predicate<String> expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> observed = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            String frame = frames.poll(50, TimeUnit.MILLISECONDS);
            if (frame != null) {
                observed.add(frame);
                if (expected.test(frame)) {
                    return frame;
                }
            }
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Raw peer failed; frames=" + observed, error);
            }
        }
        throw new AssertionError("Expected wire frame was not received; frames=" + observed);
    }

    public void assertHealthExchange(String nonce) throws Exception {
        send("427[\"health\",\"" + nonce + "\"]");
        assertTrue(awaitFrame(frame -> frame.startsWith("437")).equals("437[\"" + nonce + "\"]"),
                "Health ACK must preserve id and payload");
    }

    @Override
    public void close() {
        socket.cancel();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
    }
}
