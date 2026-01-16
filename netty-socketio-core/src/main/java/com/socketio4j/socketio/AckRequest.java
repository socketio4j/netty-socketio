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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.socketio4j.socketio.listener.DataListener;
import com.socketio4j.socketio.protocol.Packet;
import com.socketio4j.socketio.protocol.PacketType;

/**
 * Ack request received from Socket.IO client.
 * You can always check is it <code>true</code> through
 * {@link #isAckRequested()} method.
 *
 * You can call {@link #sendAckData} methods only during
 * {@link DataListener#onData} invocation. If {@link #sendAckData}
 * not called it will be invoked with empty arguments right after
 * {@link DataListener#onData} method execution by server.
 *
 * This object is NOT actual anymore if {@link #sendAckData} was
 * executed or {@link DataListener#onData} invocation finished.
 *
 */
public class AckRequest {

    private final Packet originalPacket;
    private final SocketIOClient client;
    private final AtomicBoolean sent = new AtomicBoolean();

    public boolean isSent() {
        return sent.get();
    }

    public AckRequest(Packet originalPacket, SocketIOClient client) {
        this.originalPacket = originalPacket;
        this.client = client;
    }

    /**
     * Check whether ack request was made
     *
     * @return true if ack requested by client
     */
    public boolean isAckRequested() {
        return originalPacket.isAckRequested();
    }

    /**
     * Send ack data to client.
     * Can be invoked only once during {@link DataListener#onData}
     * method invocation.
     *
     * @param objs - ack data objects
     */
    public void sendAckData(Object... objs) {
        List<Object> args = Arrays.asList(objs);
        sendAckData(args);
    }

    /**
     * Send ack data to client.
     * Can be invoked only once during {@link DataListener#onData}
     * method invocation.
     *
     * @param objs - ack data object list
     */
    public void sendAckData(List<Object> objs) {
        if (!isAckRequested() || !sent.compareAndSet(false, true)) {
            return;
        }
        Packet ackPacket = new Packet(PacketType.MESSAGE, client.getEngineIOVersion());
        ackPacket.setSubType(PacketType.ACK);
        ackPacket.setAckId(originalPacket.getAckId());
        ackPacket.setData(objs);
        client.send(ackPacket);
    }

}
