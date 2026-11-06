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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.annotation.OnConnect;
import com.socketio4j.socketio.annotation.OnDisconnect;
import com.socketio4j.socketio.annotation.OnEvent;

import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void handlesNullBeanGracefully() {
        SocketIOServer server = mock(SocketIOServer.class);
        SpringAnnotationScanner scanner = new SpringAnnotationScanner(server);

        assertNull(scanner.postProcessBeforeInitialization(null, "nullBean"));
        assertNull(scanner.postProcessAfterInitialization(null, "nullBean"));

        verifyNoInteractions(server);
    }

    @Test
    void registersDynamicProxyWithTargetInterfaceAnnotations() {
        SocketIOServer server = mock(SocketIOServer.class);
        SpringAnnotationScanner scanner = new SpringAnnotationScanner(server);

        AnnotatedInterface proxyInstance = (AnnotatedInterface) Proxy.newProxyInstance(
                AnnotatedInterface.class.getClassLoader(),
                new Class<?>[] {AnnotatedInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                }
        );

        assertSame(proxyInstance, scanner.postProcessBeforeInitialization(proxyInstance, "proxyBean"));
        assertSame(proxyInstance, scanner.postProcessAfterInitialization(proxyInstance, "proxyBean"));

        verify(server).addListeners(proxyInstance, AnnotatedInterface.class);
    }

    @Test
    void interleavedScanningDoesNotCrossContaminateOrLoseBeans() {
        SocketIOServer server = mock(SocketIOServer.class);
        SpringAnnotationScanner scanner = new SpringAnnotationScanner(server);

        AnnotatedListener bean1 = new AnnotatedListener();
        SubclassAnnotatedListener bean2 = new SubclassAnnotatedListener();

        // Simulate Spring container interleaving postProcessBeforeInitialization across beans
        scanner.postProcessBeforeInitialization(bean1, "bean1");
        scanner.postProcessBeforeInitialization(bean2, "bean2");

        // Then postProcessAfterInitialization in reverse or arbitrary order
        scanner.postProcessAfterInitialization(bean2, "bean2");
        scanner.postProcessAfterInitialization(bean1, "bean1");

        verify(server).addListeners(bean1, AnnotatedListener.class);
        verify(server).addListeners(bean2, SubclassAnnotatedListener.class);
    }

    public interface AnnotatedInterface {

        @OnEvent("ping")
        void onPing();
    }

    static class AnnotatedListener {

        @OnConnect
        void onConnect() {
        }
    }

    static class SubclassAnnotatedListener {

        @OnDisconnect
        void onDisconnect() {
        }
    }
}
