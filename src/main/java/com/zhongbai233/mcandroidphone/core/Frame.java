package com.zhongbai233.mcandroidphone.core;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** An owned, tightly packed NV12 BT.709 limited-range frame. Close after GPU upload. */
public final class Frame implements AutoCloseable {
    private final ByteBuffer pixels;
    private final int width, height;
    private final long sequence;
    private final long connectionEpoch;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    Frame(ByteBuffer pixels, int width, int height, long sequence, Runnable release) {
        this(pixels, width, height, sequence, 0, release);
    }

    Frame(ByteBuffer pixels, int width, int height, long sequence, long connectionEpoch, Runnable release) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.sequence = sequence;
        this.connectionEpoch = connectionEpoch;
        this.release = release;
    }

    public ByteBuffer pixels() {
        if (closed.get()) throw new IllegalStateException("Frame already released");
        return pixels.asReadOnlyBuffer();
    }

    public int width() { return width; }
    public int height() { return height; }
    public long sequence() { return sequence; }
    /** Immutable session identity, even if the client reconnects while this frame is retained. */
    public long connectionEpoch() { return connectionEpoch; }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }
}
