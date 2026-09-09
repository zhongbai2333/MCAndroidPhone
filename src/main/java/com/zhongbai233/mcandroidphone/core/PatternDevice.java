package com.zhongbai233.mcandroidphone.core;

import java.util.Arrays;

/** The same deliberately synthetic diagnostic pattern as the original bridge. */
final class PatternDevice implements DeviceBackend {
    private final LocalDevice device;private final int w,h;private final byte[] base;
    private volatile boolean stopping,pressed;private volatile double u=.5,v=.5;private volatile int page,textLength;
    private Thread worker;
    PatternDevice(LocalDevice device,int w,int h) {
        RfbClient.dimensions(w,h);this.device=device;this.w=w;this.h=h;base=new byte[w*h*3/2];
        Arrays.fill(base,w*h,base.length,(byte)128);
        for(int y=0;y<h;y++)Arrays.fill(base,y*w,(y+1)*w,(byte)(40+60*y/h));
        int[][] colors={{90,220},{170,70},{210,150}};
        for(int i=0;i<3;i++){int x0=i*w/3/2*2,x1=(i+1)*w/3/2*2;
            for(int y=h/8;y<h/3;y++)Arrays.fill(base,y*w+x0,y*w+x1,(byte)(130+i*25));
            for(int y=h/8/2;y<h/3/2;y++)for(int x=x0;x<x1;x+=2){base[w*h+y*w+x]=(byte)colors[i][0];base[w*h+y*w+x+1]=(byte)colors[i][1];}}
    }
    @Override public void start()throws Exception {
        long generation=device.resize(w,h);
        worker=Thread.ofPlatform().daemon().name("androidphone-pattern").start(()->{
            int tick=0;try{while(!stopping){device.publish(frame(tick++),generation);Thread.sleep(33);}}catch(InterruptedException ignored){}catch(Throwable e){device.failure=e;}
        });
    }
    byte[] frame(int tick) {
        byte[] data=base.clone();int radius=Math.max(2,Math.min(w,h)/40),px=(int)Math.round(u*(w-1)),py=(int)Math.round(v*(h-1));
        for(int y=Math.max(0,py-radius);y<Math.min(h,py+radius+1);y++)Arrays.fill(data,y*w+Math.max(0,px-radius),y*w+Math.min(w,px+radius+1),(byte)(pressed?235:190));
        for(int y=h*3/4;y<h*4/5;y++)Arrays.fill(data,y*w,(y+1)*w,(byte)(70+page*50));
        for(int y=h*5/6;y<h;y++)data[y*w+tick%w]=(byte)235;
        for(int y=h*4/5;y<h*5/6;y++)Arrays.fill(data,y*w,y*w+Math.min(w,textLength*Math.max(1,w/32)),(byte)220);
        return data;
    }
    @Override public void check(){if(device.failure!=null)throw new IllegalStateException("Pattern failed",device.failure);}
    @Override public void touch(String phase,double u,double v){this.u=u;this.v=v;pressed=!phase.equals("UP");}
    @Override public void key(String value){page=value.equals("HOME")?0:Math.floorMod(page+(value.equals("APP_SWITCH")?1:-1),3);}
    @Override public void text(String value){textLength=value.codePointCount(0,value.length());}
    @Override public void release(){pressed=false;}
    @Override public void close(){stopping=true;if(worker!=null)worker.interrupt();}
}
