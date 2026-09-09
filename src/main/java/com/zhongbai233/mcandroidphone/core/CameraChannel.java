package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.concurrent.atomic.AtomicReference;

/** Pull-driven camera transport, independent from Android display and environment telemetry. */
final class CameraChannel implements AutoCloseable {
    static final int MAGIC=0x4d435043,VERSION=1,MAX_FRAME=2*1024*1024;
    static final String PORT_NAME="com.mcandroidphone.camera";
    private record Frame(long sequence,long timestamp,int width,int height,byte[] jpeg,ManagedRuntime.CameraRequest request){}
    private final AtomicReference<Frame> latest=new AtomicReference<>();
    private final ServerSocketChannel server;
    private final Thread worker;
    private volatile SocketChannel peer;
    private volatile boolean closed;
    private volatile long requested;
    private long generation;
    private int facing=1;
    private volatile String status="waiting for Android camera receiver";
    CameraChannel()throws IOException {server=ServerSocketChannel.open();server.bind(new InetSocketAddress("127.0.0.1",0));server.configureBlocking(false);worker=Thread.ofPlatform().daemon().name("androidphone-camera").start(this::serve);}
    int port()throws IOException{return ((InetSocketAddress)server.getLocalAddress()).getPort();}
    boolean demanded(){return !closed&&requested!=0&&System.nanoTime()-requested<500_000_000L;}
    String status(){return status;}
    synchronized void invalidate(){latest.set(null);requested=0;generation++;}
    synchronized ManagedRuntime.CameraRequest request(){return demanded()?new ManagedRuntime.CameraRequest(generation,facing):null;}
    void publish(long sequence,int width,int height,byte[] jpeg) {
        var request=request();if(request!=null)publish(request,sequence,width,height,jpeg);
    }
    synchronized void publish(ManagedRuntime.CameraRequest request,long sequence,int width,int height,byte[] jpeg) {
        if(width<2||height<2||width>640||height>640||jpeg.length<4||jpeg.length>MAX_FRAME)throw new IllegalArgumentException("Invalid camera frame");
        if(sequence<1)throw new IllegalArgumentException("Invalid camera sequence");
        if(!closed&&request.generation()==generation&&request.facing()==facing)latest.set(new Frame(sequence,System.nanoTime(),width,height,jpeg.clone(),request));
    }
    private void transfer(SocketChannel socket,ByteBuffer b,boolean write)throws IOException,InterruptedException {
        long until=System.nanoTime()+2_000_000_000L;
        while(b.hasRemaining()&&!closed){int n=write?socket.write(b):socket.read(b);if(n<0)throw new EOFException();if(System.nanoTime()>until)throw new SocketTimeoutException("Camera receiver timeout");if(n==0)Thread.sleep(2);}if(closed)throw new EOFException();
    }
    private void serve() {
        while(!closed)try {
            var socket=server.accept();if(socket==null){Thread.sleep(20);continue;}peer=socket;
            try(socket) {
                socket.configureBlocking(false);socket.setOption(StandardSocketOptions.TCP_NODELAY,true);
                var hello=ByteBuffer.allocate(8);transfer(socket,hello,false);hello.flip();if(hello.getInt()!=MAGIC||hello.getInt()!=VERSION)throw new IOException("Camera handshake mismatch");
                status="Android camera receiver connected";
                while(!closed) {
                    // One request = one response. No unsolicited streaming or unbounded frame queue.
                    var request=ByteBuffer.allocate(4);transfer(socket,request,false);request.flip();int lens=request.getInt();if(lens!=1&&lens!=2)throw new IOException("Unsupported camera request");
                    Frame frame;
                    synchronized(this){if(lens!=facing){facing=lens;generation++;latest.set(null);}requested=System.nanoTime();frame=latest.get();}
                    if(frame==null||System.nanoTime()-frame.timestamp>500_000_000L)transfer(socket,ByteBuffer.allocate(4).putInt(0).flip(),true);
                    else {
                        var header=ByteBuffer.allocate(32).putInt(28+frame.jpeg.length).putInt(MAGIC).putInt(VERSION).putLong(frame.sequence).putInt(frame.width).putInt(frame.height).putInt(frame.jpeg.length).flip();
                        transfer(socket,header,true);transfer(socket,ByteBuffer.wrap(frame.jpeg),true);
                    }
                    Thread.sleep(100); // At most 10 frames/s, including a receiver that floods requests.
                }
            }finally{peer=null;invalidate();}
        }catch(Exception e){if(!closed){status="waiting for Android camera receiver: "+e.getClass().getSimpleName();try{Thread.sleep(100);}catch(InterruptedException ignored){}}}
    }
    @Override public void close(){closed=true;invalidate();try{server.close();}catch(IOException ignored){}var p=peer;if(p!=null)try{p.close();}catch(IOException ignored){}worker.interrupt();try{worker.join(2500);}catch(InterruptedException e){Thread.currentThread().interrupt();}status="stopped";}
}
