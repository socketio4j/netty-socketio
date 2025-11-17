package com.corundumstudio.socketio.nativeio;

public enum TransportType {
    AUTO, // select the best available, io_uring -> epoll -> kqueue -> nio
    NIO, // JVM default
    EPOLL, // Linux
    KQUEUE, // BSD / macOS
    IO_URING; // Linux 5.1+, recommended production version 5.15+ (LTS)
}
