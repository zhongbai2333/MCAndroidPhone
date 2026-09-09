package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.util.*;

/** QEMU display/input controlled directly by the game JVM. */
final class QemuDevice implements DeviceBackend {
    private final ManagedRuntime runtime;private final RuntimeConfig config;private final LocalDevice device;
    private final int vnc,qmp;private final String input;
    private RfbClient rfb;private volatile FfmpegConverter converter;private Thread worker;
    private volatile boolean closed;private volatile Throwable failure;
    private boolean contact;private int tracking=-1;
    QemuDevice(ManagedRuntime runtime,RuntimeConfig config,LocalDevice device,int vnc,int qmp,String input){this.runtime=runtime;this.config=config;this.device=device;this.vnc=vnc;this.qmp=qmp;this.input=input;}
    private void resize(int w,int h)throws Exception {
        synchronized(device.inputLock){long generation=device.resize(w,h);
            FfmpegConverter old=converter;converter=null;if(old!=null)old.close();
            converter=new FfmpegConverter(runtime,config,device,w,h,generation);}
    }
    @Override public void start()throws Exception {
        rfb=new RfbClient(vnc,this::resize,device.inputLock);
        worker=Thread.ofPlatform().daemon().name("androidphone-qemu-display").start(()->{
            boolean incremental=false;
            try{while(!closed){long start=System.nanoTime();rfb.request(incremental);var update=rfb.update(this::resize,true);
                if(update.changed())converter.submit(rfb.pixels);incremental=!update.resized();
                long delay=33_333_333L-(System.nanoTime()-start);if(delay>0)Thread.sleep(java.time.Duration.ofNanos(delay));}}
            catch(Throwable e){if(!closed){failure=e;device.failure=e;}}
        });
    }
    @Override public void check()throws Exception {if(failure!=null)throw new IOException("QEMU display failed",failure);var active=converter;if(active!=null)active.check();}
    private Object command(String name,Map<String,?> args)throws IOException {try(var controller=new QmpClient(qmp,2000)){return controller.execute(name,args);}}
    private Map<String,Object> mt(String phase,String axis,int value){return Map.of("type","mtt","data",Map.of("type",phase,"slot",0,"tracking-id",phase.equals("end")?-1:tracking,"axis",axis,"value",value));}
    @Override public void touch(String phase,double u,double v)throws Exception {
        if(input.equals("mouse")){rfb.touch(phase,u,v);return;}
        if(phase.equals("UP")){release();return;}
        if(phase.equals("DOWN")){if(contact)throw new IOException("Touch already down");tracking=(tracking+1)%65536;contact=true;}
        else if(!contact)throw new IOException("Touch MOVE requires DOWN");
        var events=new ArrayList<Map<String,Object>>();events.add(mt(phase.equals("DOWN")?"begin":"update","x",0));
        if(phase.equals("DOWN"))events.add(Map.of("type","btn","data",Map.of("button","touch","down",true)));
        events.add(mt("data","x",(int)Math.round(u*32767)));events.add(mt("data","y",(int)Math.round(v*32767)));
        command("input-send-event",Map.of("device","phone-display","events",events));
    }
    @Override public void key(String value)throws Exception {
        List<String> codes=switch(value){case "BACK"->List.of("ac_back");case "HOME"->List.of("ac_home");case "APP_SWITCH"->List.of("alt","tab");default->throw new IllegalArgumentException(value);};
        command("send-key",Map.of("keys",codes.stream().map(s->Map.of("type","qcode","data",s)).toList(),"hold-time",80));
    }
    @Override public void text(String value)throws Exception {
        if(value.length()>256||value.chars().anyMatch(c->c<32||c>=127))throw new IllegalArgumentException("QEMU text accepts at most 256 printable ASCII characters");
        for(int c:value.chars().toArray())rfb.chord(c);
    }
    @Override public void release()throws IOException {
        if(contact){command("input-send-event",Map.of("device","phone-display","events",List.of(mt("end","x",0),Map.of("type","btn","data",Map.of("button","touch","down",false)))));contact=false;}
        if(rfb!=null)rfb.release();
    }
    @Override public void close(){closed=true;synchronized(device.inputLock){try{release();}catch(IOException ignored){}
        if(rfb!=null)rfb.close();var active=converter;converter=null;if(active!=null)active.close();}
        if(worker!=null&&Thread.currentThread()!=worker)try{worker.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
