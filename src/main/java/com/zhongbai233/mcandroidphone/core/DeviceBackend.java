package com.zhongbai233.mcandroidphone.core;

interface DeviceBackend extends AutoCloseable {
    void start()throws Exception;
    void check()throws Exception;
    void touch(String phase,double u,double v)throws Exception;
    void key(String value)throws Exception;
    void text(String value)throws Exception;
    void release()throws Exception;
    default boolean gpu(){return false;}
    @Override void close();
}
