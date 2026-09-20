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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true
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

    @JsonProperty("type")
    public abstract String getType();

    /**
     * Returns the partition routing key for this event message when the event store
     * operates in {@link EventStoreMode#PARTITIONED_CHANNEL}.
     * <p>
     * For room-scoped events (e.g. {@link JoinMessage}, {@link LeaveMessage},
     * {@link DispatchMessage}), this returns the target room name so all events for
     * the same room land on the same partition in strict FIFO order.
     * <p>
     * For non-room events (e.g. {@link ConnectMessage}, {@link DisconnectMessage}),
     * this returns {@code null}, directing the message to a dedicated control / lifecycle partition.
     *
     * @return the partition routing key, or {@code null} if not partitioned by room
     */
    @JsonIgnore
    public String getPartitionKey() {
        return null;
    }
}
