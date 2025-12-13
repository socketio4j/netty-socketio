package com.socketio4j.socketio.store.event;

/**
 * @author https://github.com/sanjomo
 * @date 13/12/25 11:48 am
 */
public final class ListenerRegistration<T extends EventMessage> {
    final EventListener<T> listener;
    final Class<T> clazz;

    public ListenerRegistration(EventListener<T> listener, Class<T> clazz) {
        this.listener = listener;
        this.clazz = clazz;
    }

    public EventListener<T> getListener() {
        return listener;
    }

    public Class<T> getClazz() {
        return clazz;
    }
}

