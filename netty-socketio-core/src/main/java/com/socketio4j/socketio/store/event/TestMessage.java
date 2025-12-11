package com.socketio4j.socketio.store.event;

import java.io.Serializable;

/**
 * @author https://github.com/sanjomo
 * @date 10/12/25 2:47 pm
 */
public class TestMessage extends EventMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private String content;

    public TestMessage() {
        // Default constructor required for serialization
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "TestMessage{content='" + content + "', nodeId=" + getNodeId() + "}";
    }
}