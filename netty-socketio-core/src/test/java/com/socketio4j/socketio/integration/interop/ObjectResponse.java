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
