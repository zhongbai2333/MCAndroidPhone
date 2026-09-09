package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A private QEMU peer with separate listener/control sockets and bounded synchronous calls. */
final class DbusPeer implements AutoCloseable {
    record Reply(String signature,List<?> args){static Reply empty(){return new Reply("",List.of());}}
    interface Handler {Reply handle(DbusCodec.Message message)throws Exception;}
    private final InputStream input;private final OutputStream output;private final AutoCloseable transport;private final Handler handler;
    private final Object writeLock=new Object();private final AtomicLong serial=new AtomicLong();
    private final ConcurrentMap<Long,CompletableFuture<DbusCodec.Message>> pending=new ConcurrentHashMap<>();
    private Thread reader;volatile boolean closed;volatile Throwable failure;
    DbusPeer(InputStream input,OutputStream output,AutoCloseable transport,Handler handler){this.input=input;this.output=output;this.transport=transport;this.handler=handler;}
    private void send(byte[] data)throws IOException {synchronized(writeLock){if(closed)throw new EOFException("D-Bus closed");output.write(data);output.flush();}}
    private long next(){return serial.updateAndGet(n->n%0xffffffffL+1);}
    void authenticate()throws Exception {
        send("\0AUTH ANONYMOUS 6d63616e64726f696470686f6e65\r\n".getBytes(StandardCharsets.US_ASCII));
        var line=new ByteArrayOutputStream();long end=System.nanoTime()+3_000_000_000L;
        while(true){if(line.size()>8192||System.nanoTime()>end)throw new IOException("D-Bus authentication limit");int b;
            try{b=input.read();}catch(SocketTimeoutException e){continue;}if(b<0)throw new EOFException("D-Bus authentication EOF");line.write(b);if(b=='\n')break;}
        if(!line.toString(StandardCharsets.US_ASCII).startsWith("OK "))throw new IOException("D-Bus authentication rejected");
        send("BEGIN\r\n".getBytes(StandardCharsets.US_ASCII));reader=Thread.ofPlatform().daemon().name("androidphone-dbus-peer").start(this::readLoop);
    }
    List<Object> call(String path,String iface,String member,String signature,List<?> args)throws Exception {
        if(Thread.currentThread()==reader)throw new IllegalStateException("D-Bus handler cannot call itself");
        long id=next();var waiter=new CompletableFuture<DbusCodec.Message>();pending.put(id,waiter);
        try {send(DbusCodec.encode(1,id,Map.of(1,new DbusCodec.Variant("o",path),2,new DbusCodec.Variant("s",iface),3,new DbusCodec.Variant("s",member)),signature,args));
            var response=waiter.get(3,TimeUnit.SECONDS);if(response.kind()==3)throw new IOException("D-Bus error: "+response.body());return response.body();
        }catch(TimeoutException e){close();throw new IOException("D-Bus call timeout: "+member,e);}finally{pending.remove(id);}
    }
    private byte[] receive(int count,boolean idle)throws IOException {
        byte[] bytes=new byte[count];int at=0;long end=System.nanoTime()+3_000_000_000L;
        while(at<count){if(closed)throw new EOFException("D-Bus closed");
            try{int n=input.read(bytes,at,Math.min(count-at,256*1024));if(n<0)throw new EOFException("D-Bus disconnected");at+=n;}
            catch(SocketTimeoutException e){if(idle&&at==0){end=System.nanoTime()+3_000_000_000L;continue;}if(System.nanoTime()>end)throw e;}
            if(at<count&&System.nanoTime()>end)throw new SocketTimeoutException("D-Bus partial message timeout");}
        return bytes;
    }
    private void readLoop() {
        try{while(!closed){byte[] fixed=receive(16,true);var message=DbusCodec.decode(fixed,receive(DbusCodec.remaining(fixed),false));
            if(message.kind()==2||message.kind()==3){Object id=message.headers().get(5);var waiter=pending.get(id);if(waiter!=null)waiter.complete(message);}
            else if(message.kind()==1){
                try{Reply result;if(message.iface().equals("org.freedesktop.DBus.Peer")&&message.member().equals("Ping"))result=Reply.empty();
                    else if(handler!=null)result=handler.handle(message);else throw new IOException("Unsupported D-Bus method");
                    if((message.flags()&1)==0)send(DbusCodec.encode(2,next(),Map.of(5,new DbusCodec.Variant("u",message.serial())),result.signature,result.args));
                }catch(Exception e){if((message.flags()&1)==0)send(DbusCodec.encode(3,next(),Map.of(4,new DbusCodec.Variant("s","org.qemu.Client.Error"),5,new DbusCodec.Variant("u",message.serial())),"s",List.of(e.toString())));}
            }
        }}catch(Throwable e){if(!closed)failure=e;}finally{close();}
    }
    void check()throws IOException{if(failure!=null||closed)throw new IOException("D-Bus connection failed",failure);}
    @Override public void close(){closed=true;try{transport.close();}catch(Exception ignored){}for(var future:pending.values())future.completeExceptionally(new EOFException("D-Bus closed"));}
    void join() {if(reader!=null&&Thread.currentThread()!=reader)try{reader.join(6000);if(reader.isAlive())throw new IllegalStateException("D-Bus handler still owns a resource");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
