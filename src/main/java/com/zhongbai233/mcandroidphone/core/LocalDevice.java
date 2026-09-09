package com.zhongbai233.mcandroidphone.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** In-process frame ownership. Four reusable direct buffers, one cached frame, one active view.
 * No local bridge socket/mmap and no game-thread native input calls.
 */
final class LocalDevice implements AutoCloseable {
    private static final AtomicLong EPOCHS=new AtomicLong();
    final Object inputLock=new Object();
    private final Object framesLock=new Object();
    private final ArrayBlockingQueue<Runnable> input=new ArrayBlockingQueue<>(128);
    private final Thread inputWorker;
    private ArrayBlockingQueue<ByteBuffer> pool;
    private Lease cached;
    private volatile View active;
    volatile DeviceBackend backend;
    volatile boolean closed;
    volatile Throwable failure;
    volatile long connections;
    volatile long generation,epoch=EPOCHS.incrementAndGet();
    private int width,height;
    private long sequence;
    LocalDevice() {
        inputWorker=Thread.ofPlatform().daemon().name("androidphone-local-input").start(()->{
            while(!closed)try {Runnable work=input.poll(250,TimeUnit.MILLISECONDS);if(work!=null)work.run();}
            catch(InterruptedException e){break;}
        });
    }
    long resize(int w,int h)throws Exception {
        return resize(w,h,true);
    }
    long resize(int w,int h,boolean cpu)throws Exception {
        if(cpu)RfbClient.dimensions(w,h);else if(w<2||h<2||w>4096||h>4096)throw new IllegalArgumentException("GPU dimensions");
        synchronized(inputLock) {
            if(closed)throw new IllegalStateException("Device closed");
            if(backend!=null)backend.release();
            synchronized(framesLock) {
                generation++;epoch=EPOCHS.incrementAndGet();
                if(active!=null)active.clear();if(cached!=null){cached.release();cached=null;}
                width=w;height=h;pool=new ArrayBlockingQueue<>(4);
                for(int i=0;cpu&&i<4;i++)pool.add(ByteBuffer.allocateDirect(w*h*3/2));
                return generation;
            }
        }
    }
    void publish(byte[] pixels,long sourceGeneration) {
        synchronized(framesLock) {
            if(closed||sourceGeneration!=generation)return;
            if(pixels.length!=width*height*3/2)throw new IllegalArgumentException("NV12 byte count mismatch");
            ByteBuffer buffer=pool.poll();if(buffer==null)return;
            buffer.clear().put(pixels).flip();Lease next=new Lease(buffer,pool,width,height,++sequence);
            Lease previous=cached;cached=next;if(previous!=null)previous.release();
            View view=active;if(view!=null)view.accept(next.frame(epoch));
        }
    }
    void publishGpu(GpuFrame frame) {
        synchronized(framesLock){View view=active;if(closed||view==null)frame.close();else view.acceptGpu(frame);}
    }
    PhoneConnection connect() {
        synchronized(inputLock) {
            if(closed)throw new IllegalStateException("Device closed");
            View previous=active;if(previous!=null)previous.close();
            synchronized(framesLock) {
                epoch=EPOCHS.incrementAndGet();View next=new View();active=next;connections++;
                if(cached!=null)next.accept(cached.frame(epoch));return next;
            }
        }
    }
    private void releaseAsync() {
        input.clear();input.offer(()->{synchronized(inputLock){try{if(backend!=null)backend.release();}catch(Exception e){failure=e;}}});
    }
    private void detach(View view) {synchronized(inputLock){if(active==view){active=null;releaseAsync();}}}
    @Override public void close() {
        closed=true;input.clear();inputWorker.interrupt();
        View view=active;if(view!=null)view.close();
        try{if(backend!=null)backend.close();}
        finally{synchronized(framesLock){if(cached!=null){cached.release();cached=null;}pool=null;}}
    }
    private static final class Lease {
        final ByteBuffer pixels;final ArrayBlockingQueue<ByteBuffer> pool;final int w,h;final long sequence;
        final AtomicInteger refs=new AtomicInteger(1);
        Lease(ByteBuffer pixels,ArrayBlockingQueue<ByteBuffer> pool,int w,int h,long sequence){this.pixels=pixels;this.pool=pool;this.w=w;this.h=h;this.sequence=sequence;}
        Frame frame(long epoch){refs.incrementAndGet();return new Frame(pixels,w,h,sequence,epoch,this::release);}
        void release(){if(refs.decrementAndGet()==0)pool.offer(pixels);}
    }
    private final class View implements PhoneConnection {
        private final LatestFrame latest=new LatestFrame();
        private final AtomicReference<GpuFrame> gpu=new AtomicReference<>();
        private volatile boolean detached;
        synchronized void accept(Frame frame){if(detached)frame.close();else latest.publish(frame);}
        synchronized void acceptGpu(GpuFrame frame){if(detached)frame.close();else{GpuFrame old=gpu.getAndSet(frame);if(old!=null)old.close();}}
        synchronized void clear(){latest.close();GpuFrame old=gpu.getAndSet(null);if(old!=null)old.close();}
        @Override public void start(){if(detached)throw new IllegalStateException("Connection closed");}
        @Override public boolean connected(){return !detached&&!closed&&failure==null;}
        @Override public String status(){return failure!=null?"Device failed: "+failure.getMessage():connected()?"Connected "+width+"x"+height+" Java runtime":"Stopped";}
        @Override public long connectionEpoch(){return epoch;}
        @Override public Frame pollFrame(){return latest.poll();}
        @Override public boolean gpuTransport(){return backend!=null&&backend.gpu();}
        @Override public GpuFrame pollGpuFrame(){return gpu.getAndSet(null);}
        private void enqueue(Action action) {
            long expected=epoch;
            if(detached||closed)return;
            if(!input.offer(()->{synchronized(inputLock){if(active!=this||detached||closed||expected!=epoch)return;
                try{action.run();}catch(Exception e){failure=e;close();}}}))close();
        }
        @Override public void touch(String phase,double u,double v){
            if(!Set.of("DOWN","MOVE","UP").contains(phase)||!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>1||v<0||v>1)throw new IllegalArgumentException("Invalid touch");
            enqueue(()->backend.touch(phase,u,v));
        }
        @Override public void key(String value){if(!Set.of("BACK","HOME","APP_SWITCH").contains(value))throw new IllegalArgumentException("Invalid navigation key");enqueue(()->backend.key(value));}
        @Override public void text(String value){if(value.getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("Text exceeds 4096 UTF-8 bytes");enqueue(()->backend.text(value));}
        @Override public void close(){synchronized(this){if(detached)return;detached=true;clear();}detach(this);}
    }
    private interface Action {void run()throws Exception;}
}
