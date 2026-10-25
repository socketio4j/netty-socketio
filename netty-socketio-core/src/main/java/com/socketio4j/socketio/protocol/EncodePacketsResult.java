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
package com.socketio4j.socketio.protocol;

import com.socketio4j.socketio.annotation.Internal;

/**
 * @author https://github.com/sanjomo
 * @date 02/08/26 2:53 am
 */
@Internal
public final class EncodePacketsResult {
    
    private final boolean hasBinary;

    public EncodePacketsResult(boolean hasBinary) {
        this.hasBinary = hasBinary;
    }

    public boolean hasBinary() {
        return hasBinary;
    }
}