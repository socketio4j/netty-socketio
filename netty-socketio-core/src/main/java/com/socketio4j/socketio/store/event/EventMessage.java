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

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = DisconnectMessage.class, name = "DISCONNECT"),
        @JsonSubTypes.Type(value = ConnectMessage.class, name = "CONNECT"),
        @JsonSubTypes.Type(value = BulkJoinMessage.class, name = "BULK_JOIN"),
        @JsonSubTypes.Type(value = BulkLeaveMessage.class, name = "BULK_LEAVE"),
        @JsonSubTypes.Type(value = DispatchMessage.class, name = "DISPATCH"),
        @JsonSubTypes.Type(value = JoinMessage.class, name = "JOIN"),
        @JsonSubTypes.Type(value = LeaveMessage.class, name = "LEAVE")
})
public abstract class EventMessage implements Serializable {

    private static final long serialVersionUID = -8789343104393884987L;

    private Long nodeId;

    private String offset;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getOffset() {
        return offset;
    }

    public void setOffset(String offset) {
        this.offset = offset;
    }

    public abstract String getType();
}
