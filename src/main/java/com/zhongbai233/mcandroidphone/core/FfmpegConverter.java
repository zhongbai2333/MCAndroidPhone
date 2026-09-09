package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.util.*;

/** One frame in flight. Native pipes are interrupted by terminating their owned guardian. */
final class FfmpegConverter implements AutoCloseable {
    private final OwnedProcess process;
    private final LocalDevice device;
    private final long generation;
    private final int inputBytes;
    private final Object completed=new Object();
    private final Thread reader;
    private volatile boolean closed;
    private volatile Throwable failure;
    private volatile long submittedAt;
    private long submitted,received;
    FfmpegConverter(ManagedRuntime runtime,RuntimeConfig config,LocalDevice device,int w,int h,long generation)throws Exception {
        this(runtime,config,device,w,h,generation,config.get("colorOrder","rgb").equals("rgb")?"bgr0":"rgb0");
    }
    FfmpegConverter(ManagedRuntime runtime,RuntimeConfig config,LocalDevice device,int w,int h,long generation,String format)throws Exception {
        this.device=device;this.generation=generation;inputBytes=w*h*4;
        if(!Set.of("rgb","bgr").contains(config.get("colorOrder","rgb")))throw new IllegalArgumentException("Invalid colorOrder");
        if(!Set.of("bgr0","rgb0","bgra","rgba").contains(format))throw new IllegalArgumentException("Invalid pixel format");
        process=runtime.spawn("ffmpeg",List.of(config.executable("ffmpeg","ffmpeg").toString(),"-hide_banner","-loglevel","error",
            "-nostdin","-threads","1","-filter_threads","1","-f","rawvideo","-pixel_format",format,"-video_size",w+"x"+h,
            "-framerate","30","-probesize","32","-analyzeduration","0","-i","pipe:0","-an",
            "-vf","scale=in_range=full:out_range=tv:out_color_matrix=bt709","-threads","1","-pix_fmt","nv12",
            "-color_range","tv","-colorspace","bt709","-f","rawvideo","-flush_packets","1","pipe:1"),Map.of(),true);
        reader=Thread.ofPlatform().daemon().name("androidphone-nv12").start(()->{
            byte[] frame=new byte[w*h*3/2];
            try {while(!closed){int count=0;while(count<frame.length){int n=process.output().read(frame,count,frame.length-count);if(n<0)throw new EOFException("FFmpeg ended: "+process.tail());count+=n;}
                device.publish(frame,generation);synchronized(completed){received++;completed.notifyAll();}}}
            catch(Throwable e){if(!closed)failure=e;synchronized(completed){completed.notifyAll();}}
        });
    }
    void submit(byte[] bgrx)throws Exception {
        if(bgrx.length!=inputBytes)throw new IllegalArgumentException("FFmpeg input dimensions changed");
        check();submittedAt=System.nanoTime();long expected=++submitted;
        try {
            process.input().write(bgrx);process.input().flush();
            synchronized(completed){while(received<expected&&!closed){check();completed.wait(100);}}
            if(closed)throw new EOFException("Converter stopped");
        }finally{submittedAt=0;}
    }
    void check()throws IOException {
        if(failure!=null)throw new IOException("FFmpeg conversion failed",failure);
        if(!closed&&!process.alive())throw new IOException("FFmpeg exited: "+process.tail());
        // A newly extracted macOS binary may spend several seconds in system signature verification.
        if(submittedAt!=0&&System.nanoTime()-submittedAt>(received==0?30_000_000_000L:5_000_000_000L))throw new IOException("FFmpeg frame timeout");
    }
    @Override public void close(){closed=true;process.close();synchronized(completed){completed.notifyAll();}
        if(Thread.currentThread()!=reader)try{reader.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
