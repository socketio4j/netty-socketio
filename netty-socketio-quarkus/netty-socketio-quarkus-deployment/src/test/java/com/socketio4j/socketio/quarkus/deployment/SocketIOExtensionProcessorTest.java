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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.annotation.OnConnect;
import com.socketio4j.socketio.annotation.OnDisconnect;
import com.socketio4j.socketio.annotation.OnEvent;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocketIOExtensionProcessorTest {
    static class ConnectListener { @OnConnect void connect() { } }
    static class DisconnectListener { @OnDisconnect void disconnect() { } }
    static class EventListener { @OnEvent("event") void event() { } }
    static class DerivedListener extends DisconnectListener { }
    interface ParentListener { @OnEvent("event") void event(); }
    interface ChildListener extends ParentListener { }
    static class InterfaceListener implements ChildListener { public void event() { } }
    static class PlainBean { void method() { } }

    @Test
    void retainsOnlyClassBeansWithListenerMethodsInTheirHierarchy() throws IOException {
        Index index = Index.of(ConnectListener.class, DisconnectListener.class, EventListener.class,
                DerivedListener.class, ParentListener.class, ChildListener.class, InterfaceListener.class, PlainBean.class);
        List<UnremovableBeanBuildItem> items = new ArrayList<>();
        new SocketIOExtensionProcessor().unremovableBeans(new CombinedIndexBuildItem(index, index), items::add);
        UnremovableBeanBuildItem item = items.get(0);

        for (Class<?> type : new Class<?>[] { ConnectListener.class, DisconnectListener.class,
                EventListener.class, DerivedListener.class, InterfaceListener.class }) {
            BeanInfo bean = mock(BeanInfo.class);
            when(bean.isClassBean()).thenReturn(true);
            when(bean.getImplClazz()).thenReturn(index.getClassByName(type));
            assertTrue(item.getPredicate().test(bean), type.getName());
        }
        BeanInfo plain = mock(BeanInfo.class);
        when(plain.isClassBean()).thenReturn(true);
        when(plain.getImplClazz()).thenReturn(index.getClassByName(PlainBean.class));
        assertFalse(item.getPredicate().test(plain));

        BeanInfo producer = mock(BeanInfo.class);
        when(producer.getImplClazz()).thenReturn(index.getClassByName(ConnectListener.class));
        assertFalse(item.getPredicate().test(producer));
    }
}
