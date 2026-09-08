package com.zhongbai233.mcandroidphone.core;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One QEMU update lease; release GPU mutex before close/ACK. No pixel buffers. */
public final class GpuFrame implements AutoCloseable {
    public record Notice(long sequence, long handle, int textureWidth, int textureHeight,
                         boolean topDown, int x, int y, int width, int height, long timestamp) {}
    private final Notice notice;
    private final long localHandle, epoch;
    private final Runnable acknowledge;
    private final AtomicBoolean closed = new AtomicBoolean();

    GpuFrame(Notice notice, long localHandle, long epoch, Runnable acknowledge) {
        this.notice = notice; this.localHandle = localHandle; this.epoch = epoch; this.acknowledge = acknowledge;
    }
    public Notice notice() { return notice; }
    public long handle() {
        if (closed.get()) throw new IllegalStateException("GPU lease closed");
        return localHandle;
    }
    public long connectionEpoch() { return epoch; }
    public static Notice parse(String line, long previous) throws IOException {
        try {
            String[] p = line.split("\t", -1);
            if (p.length != 11 || !p[0].equals("GPUFRAME")) throw new IllegalArgumentException();
            long seq=Long.parseLong(p[1]), handle=Long.parseLong(p[2]);
            int tw=Integer.parseInt(p[3]), th=Integer.parseInt(p[4]), top=Integer.parseInt(p[5]);
            int x=Integer.parseInt(p[6]), y=Integer.parseInt(p[7]), w=Integer.parseInt(p[8]), h=Integer.parseInt(p[9]);
            long timestamp=Long.parseLong(p[10]);
            if (seq<=previous || handle<=0 || tw<2 || tw>4096 || th<2 || th>4096 || top<0 || top>1 ||
                x<0 || y<0 || w<2 || h<2 || (long)x+w>tw || (long)y+h>th || timestamp<0)
                throw new IllegalArgumentException();
            return new Notice(seq,handle,tw,th,top==1,x,y,w,h,timestamp);
        } catch (RuntimeException error) { throw new IOException("Invalid GPU texture descriptor", error); }
    }
    @Override public void close() {
        if (closed.compareAndSet(false,true)) {
            try { Win32Handles.close(localHandle); }
            finally { acknowledge.run(); }
        }
    }
}
