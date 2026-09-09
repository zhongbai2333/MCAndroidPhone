package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Windows direct D-Bus input, shared CPU surfaces, and D3D11 update leases. */
final class QemuDbusDevice implements DeviceBackend {
    static final String DISPLAY="org.qemu.Display1.",CONSOLE="/org/qemu/Display1/Console_0",LISTENER="/org/qemu/Display1/Listener";
    static final String MAP=DISPLAY+"Listener.Win32.Map",D3D=DISPLAY+"Listener.Win32.D3d11";
    private final ManagedRuntime runtime;private final RuntimeConfig config;private final LocalDevice device;private final long qemuPid;private final int qmp;private final String input,order;private final boolean gpu;
    private final Object surfaceLock=new Object(),listenerLock=new Object();private final Set<Integer> keys=new LinkedHashSet<>();
    private volatile DbusPeer control,listener;private volatile boolean closed,reconnecting;private volatile Throwable failure;private volatile FfmpegConverter converter;
    private DbusSurface surface;private boolean dirty;private Thread worker;private volatile int width,height;private String pixelFormat;
    private GpuFrame.Notice scanout;private long sequence;private boolean touching;private double x,y;
    QemuDbusDevice(ManagedRuntime runtime,RuntimeConfig config,LocalDevice device,long pid,int qmp,String input){this.runtime=runtime;this.config=config;this.device=device;qemuPid=pid;this.qmp=qmp;this.input=input;gpu=config.gpu().equals("virgl");order=config.get("colorOrder","rgb");}
    @Override public boolean gpu(){return gpu;}
    private DbusPeer peer(WindowsSocket socket,DbusPeer.Handler handler){return new DbusPeer(socket.input(),socket.output(),socket::disconnect,handler);}
    private void registerListener()throws Exception {
        synchronized(listenerLock) {
            if(closed)return;reconnecting=true;
            try {
                var old=listener;if(old!=null){old.close();old.join();}listener=null;closeScanout();
                var sockets=WindowsSocket.pair(Path.of(System.getProperty("java.io.tmpdir")));listener=peer(sockets[0],this::message);
                try(var remote=sockets[1]) {control.call(CONSOLE,DISPLAY+"Console","RegisterListener","ay",List.of(remote.share(qemuPid)));}
                listener.authenticate();
            }finally{reconnecting=false;}
        }
    }
    @Override public void start()throws Exception {
        var sockets=WindowsSocket.pair(Path.of(System.getProperty("java.io.tmpdir")));control=peer(sockets[0],null);
        try(var remote=sockets[1];var qmpClient=new QmpClient(qmp,3000)) {
            qmpClient.execute("get-win32-socket",Map.of("info",Base64.getEncoder().encodeToString(remote.share(qemuPid)),"fdname","phone-dbus"));
            qmpClient.execute("add_client",Map.of("protocol","@dbus-display","fdname","phone-dbus"));
        }
        control.authenticate();registerListener();
        worker=Thread.ofPlatform().daemon().name("androidphone-dbus-display").start(()->{
            long connections=device.connections;
            try{while(!closed){
                if(gpu){if(connections!=device.connections){connections=device.connections;registerListener();}Thread.sleep(50);continue;}
                byte[] pixels;int w,h;String format;
                synchronized(surfaceLock){if(!dirty){surfaceLock.wait(250);continue;}dirty=false;if(surface==null)continue;
                    w=surface.width;h=surface.height;format=surface.format;pixels=surface.snapshot();}
                if(converter==null||w!=width||h!=height||!format.equals(pixelFormat)) {
                    synchronized(device.inputLock){long generation=device.resize(w,h);var previous=converter;converter=null;if(previous!=null)previous.close();
                        if(closed)break;width=w;height=h;pixelFormat=format;converter=new FfmpegConverter(runtime,config,device,w,h,generation,format);}
                }
                var active=converter;if(active!=null)active.submit(pixels);Thread.sleep(33);
            }}catch(Throwable e){if(!closed){failure=e;device.failure=e;}}
        });
    }
    static long number(List<Object> body,int at)throws IOException {if(at>=body.size()||!(body.get(at) instanceof Long v))throw new IOException("D-Bus integer argument required");return v;}
    static int integer(List<Object> body,int at)throws IOException {long value=number(body,at);if(value<0||value>Integer.MAX_VALUE)throw new IOException("D-Bus dimension out of range");return (int)value;}
    private static void count(List<?> body,int n)throws IOException{if(body.size()!=n)throw new IOException("D-Bus argument count mismatch");}
    private DbusPeer.Reply message(DbusCodec.Message message)throws Exception {
        try{return handle(message);}catch(Exception e){failure=e;device.failure=e;throw e;}
    }
    private DbusPeer.Reply handle(DbusCodec.Message message)throws Exception {
        String iface=message.iface(),member=message.member();var b=message.body();
        if(!message.path().equals(LISTENER))throw new IOException("Unknown D-Bus listener path");
        if(iface.equals("org.freedesktop.DBus.Properties")) {
            var interfaces=new DbusCodec.Variant("as",gpu?List.of(D3D,MAP):List.of(MAP));
            if(member.equals("GetAll")&&b.size()==1)return new DbusPeer.Reply("a{sv}",List.of(b.getFirst().equals(DISPLAY+"Listener")?Map.of("Interfaces",interfaces):Map.of()));
            if(member.equals("Get")&&b.equals(List.of(DISPLAY+"Listener","Interfaces")))return new DbusPeer.Reply("v",List.of(interfaces));
            throw new IOException("Unknown listener property");
        }
        if(gpu&&iface.equals(D3D)&&member.equals("ScanoutTexture2d")) {
            GpuFrame.Notice next=null;long handle=0;
            try{handle=number(b,0);count(b,8);if(!(b.get(3) instanceof Boolean top))throw new IOException("GPU orientation boolean required");
                next=GpuFrame.parse("GPUFRAME\t1\t"+handle+"\t"+integer(b,1)+"\t"+integer(b,2)+"\t"+(top?1:0)+"\t"+integer(b,4)+"\t"+integer(b,5)+"\t"+integer(b,6)+"\t"+integer(b,7)+"\t0",0);
                synchronized(device.inputLock){if(width!=next.width()||height!=next.height()){device.resize(next.width(),next.height(),false);width=next.width();height=next.height();}}
            }catch(Exception e){if(handle>0)Win32Handles.close(handle);throw e;}
            closeScanout();scanout=next;return DbusPeer.Reply.empty();
        }
        if(gpu&&iface.equals(D3D)&&member.equals("UpdateTexture2d")) {
            count(b,4);var current=scanout;if(current==null)throw new IOException("GPU update without scanout");
            int x=integer(b,0),y=integer(b,1),w=integer(b,2),h=integer(b,3);if((long)x+w>current.width()||(long)y+h>current.height())throw new IOException("GPU dirty rectangle outside scanout");
            var done=new CompletableFuture<Void>();var notice=new GpuFrame.Notice(++sequence,current.handle(),current.textureWidth(),current.textureHeight(),current.topDown(),current.x(),current.y(),current.width(),current.height(),Math.max(0,System.nanoTime()));
            var frame=new GpuFrame(notice,Win32Handles.duplicateLocal(current.handle()),device.epoch,()->done.complete(null));
            device.publishGpu(frame);
            // QEMU relinquishes KeyedMutex(0) only during this call. Reply after renderer release.
            done.get(5,TimeUnit.SECONDS);return DbusPeer.Reply.empty();
        }
        if(gpu&&iface.equals(MAP)&&member.equals("ScanoutMap")){long handle=number(b,0);if(handle>0)Win32Handles.close(handle);return DbusPeer.Reply.empty();}
        if(gpu&&((iface.equals(MAP)&&member.equals("UpdateMap"))||(iface.equals(DISPLAY+"Listener")&&Set.of("Scanout","Update").contains(member))))return DbusPeer.Reply.empty();
        if(iface.equals(MAP)&&member.equals("ScanoutMap")) {
            count(b,6);var next=new DbusSurface(number(b,0),number(b,1),integer(b,2),integer(b,3),integer(b,4),number(b,5),order,null);surface(next);
        }else if(iface.equals(DISPLAY+"Listener")&&member.equals("Scanout")) {
            count(b,5);var next=new DbusSurface(0,0,integer(b,0),integer(b,1),integer(b,2),number(b,3),order,(byte[])b.get(4));surface(next);
        }else if(iface.equals(DISPLAY+"Listener")&&member.equals("Update")) {
            count(b,7);synchronized(surfaceLock){if(surface==null)throw new IOException("Pixel update without scanout");surface.update(integer(b,0),integer(b,1),integer(b,2),integer(b,3),integer(b,4),number(b,5),(byte[])b.get(6),order);dirty=true;surfaceLock.notifyAll();}
        }else if(iface.equals(MAP)&&member.equals("UpdateMap")) {
            count(b,4);synchronized(surfaceLock){if(surface==null)throw new IOException("Map update without scanout");surface.rectangle(integer(b,0),integer(b,1),integer(b,2),integer(b,3));dirty=true;surfaceLock.notifyAll();}
        }else if(iface.equals(DISPLAY+"Listener")&&member.equals("Disable")){closeScanout();surface(null);}
        else if(!iface.equals(DISPLAY+"Listener")||!Set.of("MouseSet","CursorDefine").contains(member))throw new IOException("Unsupported QEMU D-Bus method: "+iface+"."+member);
        return DbusPeer.Reply.empty();
    }
    private void surface(DbusSurface next){synchronized(surfaceLock){if(closed){if(next!=null)next.close();return;}var old=surface;surface=next;if(old!=null)old.close();dirty=true;surfaceLock.notifyAll();}}
    private void closeScanout(){if(scanout!=null){Win32Handles.close(scanout.handle());scanout=null;}}
    private void input(String iface,String method,String sig,List<?> args)throws Exception {control.call(CONSOLE,DISPLAY+iface,method,sig,args);}
    @Override public void touch(String phase,double u,double v)throws Exception {
        if(phase.equals("UP")){release();return;}if(phase.equals("DOWN")){if(touching)throw new IOException("Touch already down");touching=true;}else if(!touching)throw new IOException("Touch MOVE requires DOWN");
        x=u*Math.max(0,width-1);y=v*Math.max(0,height-1);
        if(input.equals("touchscreen"))input("MultiTouch","SendEvent","utdd",List.of(phase.equals("DOWN")?0:1,0,x,y));
        else {input("Mouse","SetAbsPosition","uu",List.of(Math.round(x),Math.round(y)));if(phase.equals("DOWN"))input("Mouse","Press","u",List.of(0));}
    }
    @Override public void key(String value)throws Exception {
        var codes=switch(value){case "BACK"->List.of("ac_back");case "HOME"->List.of("ac_home");case "APP_SWITCH"->List.of("alt","tab");default->throw new IllegalArgumentException(value);};
        try(var controller=new QmpClient(qmp,2000)){controller.execute("send-key",Map.of("keys",codes.stream().map(c->Map.of("type","qcode","data",c)).toList(),"hold-time",80));}
    }
    private static final Map<Character,Integer> ASCII=new HashMap<>();private static final String SHIFTED="!@#$%^&*()_+{}:\"~|<>?",PLAIN="1234567890-=[];'`\\,./";
    static {String[] rows={"1234567890-=","qwertyuiop[]","asdfghjkl;'","zxcvbnm,./"};int[] codes={2,16,30,44};for(int r=0;r<rows.length;r++)for(int c=0;c<rows[r].length();c++)ASCII.put(rows[r].charAt(c),codes[r]+c);ASCII.put('`',41);ASCII.put('\\',43);ASCII.put(' ',57);}
    @Override public void text(String value)throws Exception {
        if(value.length()>256||value.chars().anyMatch(c->c<32||c>=127))throw new IllegalArgumentException("QEMU text accepts at most 256 printable ASCII characters");
        for(char c:value.toCharArray()){int shifted=SHIFTED.indexOf(c);boolean shift=shifted>=0||Character.isUpperCase(c);char plain=shifted>=0?PLAIN.charAt(shifted):Character.toLowerCase(c);
            try{if(shift){keys.add(42);input("Keyboard","Press","u",List.of(42));}int code=ASCII.get(plain);keys.add(code);input("Keyboard","Press","u",List.of(code));}finally{releaseKeys();}}
    }
    private void releaseKeys()throws Exception {Exception error=null;for(int code:List.copyOf(keys).reversed())try{input("Keyboard","Release","u",List.of(code));keys.remove(code);}catch(Exception e){error=e;}if(error!=null)throw error;}
    @Override public void release()throws Exception {
        try{if(touching){if(input.equals("touchscreen")){input("MultiTouch","SendEvent","utdd",List.of(2,0,x,y));input("Mouse","Release","u",List.of(9));}else input("Mouse","Release","u",List.of(0));touching=false;}}
        finally{releaseKeys();}
    }
    @Override public void check()throws Exception {if(failure!=null)throw new IOException("D-Bus device failed",failure);var c=control;if(c!=null)c.check();if(!reconnecting){var l=listener;if(l!=null)l.check();}var active=converter;if(active!=null)active.check();}
    @Override public void close() {
        closed=true;synchronized(surfaceLock){surfaceLock.notifyAll();}
        synchronized(device.inputLock){try{release();}catch(Exception ignored){}}
        synchronized(listenerLock){if(listener!=null){listener.close();listener.join();}closeScanout();}
        if(control!=null){control.close();control.join();}var active=converter;converter=null;if(active!=null)active.close();
        if(worker!=null&&Thread.currentThread()!=worker)try{worker.join(6000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        synchronized(surfaceLock){if(surface!=null){surface.close();surface=null;}}
    }
}
