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
package com.socketio4j.socketio.quarkus.deployment;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.annotation.OnConnect;
import com.socketio4j.socketio.annotation.OnDisconnect;
import com.socketio4j.socketio.annotation.OnEvent;
import com.socketio4j.socketio.quarkus.config.DefaultSocketIOBeans;
import com.socketio4j.socketio.quarkus.lifecycle.SocketIOServerLifecycle;
import com.socketio4j.socketio.quarkus.recorder.NettySocketIOConfigRecorder;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.runtime.RuntimeValue;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Socket.IO Quarkus extension processor.
 */
class SocketIOExtensionProcessor {

    private static final String FEATURE = "socketio-auto-registration";
    private static final DotName ON_CONNECT = DotName.createSimple(OnConnect.class.getName());
    private static final DotName ON_DISCONNECT = DotName.createSimple(OnDisconnect.class.getName());
    private static final DotName ON_EVENT = DotName.createSimple(OnEvent.class.getName());

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Register default Socket.IO beans.
     * @return additional bean build item
     */
    @BuildStep
    AdditionalBeanBuildItem defaultSocketIOBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(
                        DefaultSocketIOBeans.class
                )
                .setUnremovable()
                .build();
    }

    /**
     * Register Socket.IO annotations for reflection.
     * @return reflective class build item
     */
    @BuildStep
    ReflectiveClassBuildItem reflection() {
        return ReflectiveClassBuildItem.builder(
                OnConnect.class,
                OnDisconnect.class,
                OnEvent.class
        ).methods().fields().build();
    }

    /**
     * Ensure class beans with listener methods are not removed by ArC.
     * @param index combined index for resolving the class hierarchy
     * @param producer build producer for unremovable beans
     */
    @BuildStep
    void unremovableBeans(CombinedIndexBuildItem index, BuildProducer<UnremovableBeanBuildItem> producer) {
        IndexView classes = index.getComputingIndex();
        producer.produce(new UnremovableBeanBuildItem(bean -> bean.isClassBean()
                && hasListenerMethods(bean.getImplClazz(), classes)));
    }

    static boolean hasListenerMethods(ClassInfo clazz, IndexView index) {
        if (clazz == null) {
            return false;
        }
        for (MethodInfo method : clazz.methods()) {
            if (method.hasAnnotation(ON_CONNECT) || method.hasAnnotation(ON_DISCONNECT)
                    || method.hasAnnotation(ON_EVENT)) {
                return true;
            }
        }
        for (DotName iface : clazz.interfaceNames()) {
            if (hasListenerMethods(index.getClassByName(iface), index)) {
                return true;
            }
        }
        return clazz.superName() != null && hasListenerMethods(index.getClassByName(clazz.superName()), index);
    }

    /**
     * Create SocketIOServer bean.
     * @param nettySocketIOConfigRecorder config recorder
     * @param syntheticBeanBuildItemProducer synthetic bean producer
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void createSocketIOServer(
            NettySocketIOConfigRecorder nettySocketIOConfigRecorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemProducer
    ) {
        RuntimeValue<SocketIOServer> socketIOServer = nettySocketIOConfigRecorder.createSocketIOServer();

        syntheticBeanBuildItemProducer.produce(
                SyntheticBeanBuildItem.configure(SocketIOServer.class)
                        .scope(ApplicationScoped.class)
                        .runtimeValue(socketIOServer)
                        .setRuntimeInit()
                        .done()
        );
    }

    /**
     * Register SocketIOServerLifecycle bean.
     * @return additional bean build item
     */
    @BuildStep
    AdditionalBeanBuildItem socketIOServerLifecycle() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(SocketIOServerLifecycle.class)
                .setUnremovable()
                .build();
    }
}
