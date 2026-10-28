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

import org.jboss.jandex.DotName;

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
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.runtime.RuntimeValue;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Socket.IO Quarkus extension processor.
 */
class SocketIOExtensionProcessor {

    private static final String FEATURE = "socketio-auto-registration";

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
     * Ensure beans containing Socket.IO annotations are not stripped by ArC dead code elimination.
     * @param producer build producer for unremovable beans
     */
    @BuildStep
    void unremovableBeans(BuildProducer<UnremovableBeanBuildItem> producer) {
        producer.produce(UnremovableBeanBuildItem.targetWithAnnotation(DotName.createSimple(OnConnect.class.getName())));
        producer.produce(UnremovableBeanBuildItem.targetWithAnnotation(DotName.createSimple(OnDisconnect.class.getName())));
        producer.produce(UnremovableBeanBuildItem.targetWithAnnotation(DotName.createSimple(OnEvent.class.getName())));
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
