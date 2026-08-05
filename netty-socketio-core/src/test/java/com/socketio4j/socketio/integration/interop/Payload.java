package com.socketio4j.socketio.integration.interop;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Payload {
    @JsonProperty("name")
    public String name;

    @JsonProperty("value")
    public int value;

    public Payload() {}

    public Payload(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
