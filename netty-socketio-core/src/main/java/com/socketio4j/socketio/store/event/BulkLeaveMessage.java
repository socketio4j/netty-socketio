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

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public class BulkLeaveMessage extends EventMessage {

    private static final long serialVersionUID = 4084111480148299012L;

    private @Nullable UUID sessionId;
    private @Nullable String namespace;
    private @Nullable Set<String> rooms;

    public BulkLeaveMessage() {
    }

    public BulkLeaveMessage(@Nullable UUID id, @Nullable Set<String> rooms, @Nullable String namespace) {
        super();
        this.sessionId = id;
        this.rooms = rooms;
        this.namespace = namespace;
    }

    public @Nullable String getNamespace() {
        return namespace;
    }

    public @Nullable UUID getSessionId() {
        return sessionId;
    }

    public @Nullable Set<String> getRooms() {
        return rooms;
    }
    @Override
    public String getType() {
        return EventType.BULK_LEAVE.name();
    }

}
