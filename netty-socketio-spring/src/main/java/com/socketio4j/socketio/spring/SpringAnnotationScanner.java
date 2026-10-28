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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.ReflectionUtils.MethodFilter;

import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.annotation.OnConnect;
import com.socketio4j.socketio.annotation.OnDisconnect;
import com.socketio4j.socketio.annotation.OnEvent;

public class SpringAnnotationScanner implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SpringAnnotationScanner.class);

    private static final Method AOP_GET_TARGET_CLASS;

    static {
        Method method = null;
        try {
            Class<?> aopUtilsClass = Class.forName("org.springframework.aop.support.AopUtils");
            method = aopUtilsClass.getMethod("getTargetClass", Object.class);
        } catch (Exception | LinkageError ignored) {
            // spring-aop not present on classpath
        }
        AOP_GET_TARGET_CLASS = method;
    }

    private final List<Class<? extends Annotation>> annotations =
            Arrays.asList(OnConnect.class, OnDisconnect.class, OnEvent.class);

    private final SocketIOServer socketIOServer;

    public SpringAnnotationScanner(SocketIOServer socketIOServer) {
        super();
        this.socketIOServer = socketIOServer;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean != null) {
            Class<?> targetClass = resolveTargetClass(bean);
            if (targetClass != null && hasSocketIOAnnotations(targetClass)) {
                socketIOServer.addListeners(bean, targetClass);
                log.info("{} bean listeners added", beanName);
            }
        }
        return bean;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    private Class<?> resolveTargetClass(Object bean) {
        if (AOP_GET_TARGET_CLASS != null) {
            try {
                return (Class<?>) AOP_GET_TARGET_CLASS.invoke(null, bean);
            } catch (Exception | LinkageError ignored) {
                // fall through to getUserClass
            }
        }
        Class<?> userClass = ClassUtils.getUserClass(bean);
        if (Proxy.isProxyClass(userClass)) {
            for (Class<?> ifc : userClass.getInterfaces()) {
                if (hasSocketIOAnnotations(ifc)) {
                    return ifc;
                }
            }
        }
        return userClass;
    }

    private boolean hasSocketIOAnnotations(Class<?> clazz) {
        final AtomicBoolean hasAnnotation = new AtomicBoolean();
        ReflectionUtils.doWithMethods(clazz,
                new MethodCallback() {
                    @Override
                    public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
                        hasAnnotation.set(true);
                    }
                },
                new MethodFilter() {
                    @Override
                    public boolean matches(Method method) {
                        for (Class<? extends Annotation> annotationClass : annotations) {
                            if (method.isAnnotationPresent(annotationClass)) {
                                return true;
                            }
                        }
                        return false;
                    }
                });
        return hasAnnotation.get();
    }
}
