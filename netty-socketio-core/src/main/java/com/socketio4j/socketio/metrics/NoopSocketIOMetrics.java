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
package com.socketio4j.socketio.metrics;

final class NoopSocketIOMetrics implements SocketIOMetrics {

    static final NoopSocketIOMetrics INSTANCE = new NoopSocketIOMetrics();

    private NoopSocketIOMetrics() {}


    @Override
    public void eventReceived(String namespace) {

    }

    @Override
    public void eventHandled(String namespace, long durationNanos) {

    }

    @Override
    public void eventFailed(String namespace) {

    }

    @Override
    public void eventSent(String namespace, int recipients) {

    }

    @Override
    public void unknownEventReceived(String namespace) {

    }

    @Override
    public void unknownEventNames(String namespace, String eventName) {

    }

    @Override
    public void ackSent(String namespace, long latencyNanos) {

    }

    @Override
    public void ackMissing(String namespace) {

    }

    @Override
    public void connect(String namespace) {

    }

    @Override
    public void disconnect(String namespace) {

    }

    @Override
    public void roomJoin(String namespace) {

    }

    @Override
    public void roomLeave(String namespace) {

    }


}
