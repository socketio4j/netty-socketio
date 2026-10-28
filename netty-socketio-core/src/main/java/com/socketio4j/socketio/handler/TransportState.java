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
package com.socketio4j.socketio.handler;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.socketio4j.socketio.annotation.Internal;
import com.socketio4j.socketio.protocol.Packet;

import io.netty.channel.Channel;

@Internal
public class TransportState {

    private static final class CountingPacketQueue extends ConcurrentLinkedQueue<Packet> {

        private static final long serialVersionUID = 1L;

        private final AtomicInteger count = new AtomicInteger();

        @Override
        public boolean offer(Packet packet) {
            if (super.offer(packet)) {
                count.incrementAndGet();
                return true;
            }
            return false;
        }

        @Override
        public boolean add(Packet packet) {
            return offer(packet);
        }

        @Override
        public Packet poll() {
            Packet packet = super.poll();
            if (packet != null) {
                count.decrementAndGet();
            }
            return packet;
        }

        @Override
        public boolean remove(Object o) {
            if (super.remove(o)) {
                count.decrementAndGet();
                return true;
            }
            return false;
        }

        @Override
        public boolean removeIf(Predicate<? super Packet> filter) {
            return super.removeIf(packet -> {
                if (filter.test(packet)) {
                    count.decrementAndGet();
                    return true;
                }
                return false;
            });
        }

        @Override
        public void clear() {
            Packet item = poll();
            while (item != null) {
                item = poll();
            }
        }

        @Override
        public int size() {
            return Math.max(0, count.get());
        }
    }

    private volatile Queue<Packet> packetsQueue = new CountingPacketQueue();
    private final AtomicReference<Channel> channel = new AtomicReference<>();

    public void setPacketsQueue(Queue<Packet> packetsQueue) {
        if (packetsQueue == null) {
            this.packetsQueue = new CountingPacketQueue();
        } else {
            this.packetsQueue = packetsQueue;
        }
    }

    public Queue<Packet> getPacketsQueue() {
        return packetsQueue;
    }

    public void clearPacketsQueue() {
        packetsQueue.clear();
    }

    public Channel getChannel() {
        return channel.get();
    }

    public Channel update(Channel channel) {
        return this.channel.getAndSet(channel);
    }

    public boolean compareAndSet(Channel expected, Channel updated) {
        return channel.compareAndSet(expected, updated);
    }

}
