package com.zhongbai233.mcandroidphone.core;

/** A view of a device. Closing a view releases input/display, not the running device. */
public interface PhoneConnection extends AutoCloseable {
    void start();
    boolean connected();
    String status();
    long connectionEpoch();
    Frame pollFrame();
    default boolean gpuTransport(){return false;}
    default GpuFrame pollGpuFrame(){return null;}
    void touch(String phase,double u,double v);
    void key(String key);
    void text(String text);
    @Override void close();
}
