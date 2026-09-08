package com.zhongbai233.mcandroidphone.core;

import java.util.concurrent.atomic.AtomicReference;

/** A single frame mailbox. Producer owns frames until publish, consumer until close. */
final class LatestFrame implements AutoCloseable {
    private final AtomicReference<Frame> pending = new AtomicReference<>();

    void publish(Frame frame) {
        Frame old = pending.getAndSet(frame);
        if (old != null) old.close();
    }

    Frame poll() { return pending.getAndSet(null); }

    @Override public void close() {
        Frame old = poll();
        if (old != null) old.close();
    }
}
