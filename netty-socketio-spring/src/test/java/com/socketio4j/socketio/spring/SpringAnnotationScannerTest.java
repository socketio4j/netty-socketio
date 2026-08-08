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
package com.socketio4j.socketio.spring;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.annotation.OnConnect;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SpringAnnotationScannerTest {

    @Test
    void registersAnnotatedBeanWithItsRuntimeClass() {
        SocketIOServer server = mock(SocketIOServer.class);
        SpringAnnotationScanner scanner = new SpringAnnotationScanner(server);
        AnnotatedListener bean = new AnnotatedListener();

        assertSame(bean, scanner.postProcessBeforeInitialization(bean, "listener"));
        assertSame(bean, scanner.postProcessAfterInitialization(bean, "listener"));

        verify(server).addListeners(bean, AnnotatedListener.class);
    }

    @Test
    void ignoresBeansWithoutSocketIoListenerAnnotations() {
        SocketIOServer server = mock(SocketIOServer.class);
        SpringAnnotationScanner scanner = new SpringAnnotationScanner(server);
        Object bean = new Object();

        assertSame(bean, scanner.postProcessBeforeInitialization(bean, "plainBean"));
        assertSame(bean, scanner.postProcessAfterInitialization(bean, "plainBean"));

        verifyNoInteractions(server);
    }

    static class AnnotatedListener {

        @OnConnect
        void onConnect() {
        }
    }
}
