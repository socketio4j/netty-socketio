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
package com.socketio4j.socketio.test.spring.boot.starter.annotation;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.annotation.OnEvent;
import com.socketio4j.socketio.test.spring.boot.starter.BaseSpringApplicationTest;

import io.socket.client.IO;
import io.socket.client.Socket;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Test for Spring AOP proxied Socket.IO listener invocation")
@Import(AopProxiedListenerTest.TestAopConfig.class)
public class AopProxiedListenerTest extends BaseSpringApplicationTest {

    private static final int PORT = 0;

    @Autowired
    private SocketIOServer socketIOServer;

    @Autowired
    private ProxiedEventListener proxiedEventListener;

    private Socket socket;

    @DynamicPropertySource
    public static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("netty-socket-io.port", () -> PORT);
    }

    @BeforeEach
    public void setup() throws Exception {
        proxiedEventListener.reset();
        int boundPort = socketIOServer.getConfiguration().getPort();
        socket = IO.socket(
                String.format("http://localhost:%d", boundPort),
                IO.Options.builder().setForceNew(true).build()
        );
        socket.connect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> socket.connected());
    }

    @AfterEach
    public void tearDown() {
        if (socket != null && socket.connected()) {
            socket.disconnect();
        }
    }

    @Test
    @DisplayName("Verify that AOP interceptor advice executes when Socket.IO invokes event method on proxy")
    public void testAopProxyAdviceInvokedOnEvent() {
        socket.emit("aopEvent", "testPayload");

        await().atMost(5, TimeUnit.SECONDS).until(proxiedEventListener::isEventReceived);
        assertTrue(proxiedEventListener.isAdviceExecuted(),
                "Spring AOP advice must execute when listener method is invoked");
    }

    public static class ProxiedEventListener {
        private final AtomicBoolean eventReceived = new AtomicBoolean(false);
        private final AtomicBoolean adviceExecuted = new AtomicBoolean(false);

        @OnEvent("aopEvent")
        public void onAopEvent(SocketIOClient client, String data) {
            eventReceived.set(true);
        }

        public void markAdviceExecuted() {
            adviceExecuted.set(true);
        }

        public boolean isEventReceived() {
            return eventReceived.get();
        }

        public boolean isAdviceExecuted() {
            return adviceExecuted.get();
        }

        public void reset() {
            eventReceived.set(false);
            adviceExecuted.set(false);
        }
    }

    public static class TestAopConfig {

        @Bean
        public ProxiedEventListener proxiedEventListener() {
            return new ProxiedEventListener();
        }

        @Bean
        public static BeanPostProcessor aopProxyPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof ProxiedEventListener) {
                        ProxyFactory factory = new ProxyFactory(bean);
                        factory.setProxyTargetClass(true);
                        factory.addAdvice(new MethodInterceptor() {
                            @Override
                            public Object invoke(MethodInvocation invocation) throws Throwable {
                                ((ProxiedEventListener) invocation.getThis()).markAdviceExecuted();
                                return invocation.proceed();
                            }
                        });
                        return factory.getProxy();
                    }
                    return bean;
                }
            };
        }
    }
}
