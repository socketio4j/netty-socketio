package com.socketio4j.socketio.protocol;

/**
 * @author https://github.com/sanjomo
 * @date 02/08/26 2:53 am
 */
public final class EncodePacketsResult {
    private final boolean hasBinary;

    public EncodePacketsResult(boolean hasBinary) {
        this.hasBinary = hasBinary;
    }

    public boolean hasBinary() {
        return hasBinary;
    }
}