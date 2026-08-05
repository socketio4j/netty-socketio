package com.socketio4j.socketio.integration.interop;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ObjectResponse {
    @JsonProperty("echo")
    public String echo;

    @JsonProperty("doubled")
    public int doubled;

    public ObjectResponse() {}

    public ObjectResponse(String echo, int doubled) {
        this.echo = echo;
        this.doubled = doubled;
    }

    public String getEcho() {
        return echo;
    }

    public void setEcho(String echo) {
        this.echo = echo;
    }

    public int getDoubled() {
        return doubled;
    }

    public void setDoubled(int doubled) {
        this.doubled = doubled;
    }
}
