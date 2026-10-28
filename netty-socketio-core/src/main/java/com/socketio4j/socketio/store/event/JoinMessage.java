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
package com.socketio4j.socketio.store.event;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

public class JoinMessage extends EventMessage {

    private static final long serialVersionUID = -944515928988033174L;

    private @Nullable UUID sessionId;
    private @Nullable String namespace;
    private @Nullable String room;

    public JoinMessage() {
    }

    public JoinMessage(@Nullable UUID id, @Nullable String room, @Nullable String namespace) {
        super();
        this.sessionId = id;
        this.room = room;
        this.namespace = namespace;
    }

    public @Nullable String getNamespace() {
        return namespace;
    }

    public @Nullable UUID getSessionId() {
        return sessionId;
    }

    public @Nullable String getRoom() {
        return room;
    }
    @Override
    public String getType() {
        return EventType.JOIN.name();
    }

}
