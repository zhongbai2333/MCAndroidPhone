package com.zhongbai233.mcandroidphone.core;

import com.zhongbai233.mcandroidphone.environment.EnvironmentPacket;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.atomic.AtomicReference;

/** Dedicated QEMU virtserial channel. One latest sample, bounded writes, no game-thread I/O. */
final class EnvironmentChannel implements AutoCloseable {
    static final String PORT_NAME="com.mcandroidphone.environment";
    private record Sample(EnvironmentPacket packet,long received){}
    private final AtomicReference<Sample> latest=new AtomicReference<>();
    private final ServerSocketChannel server;
    private final Thread worker;
    private volatile boolean closed;
    private volatile SocketChannel peer;
    private volatile String status="waiting for Android environment receiver";
    EnvironmentChannel()throws IOException {
        server=ServerSocketChannel.open();server.bind(new InetSocketAddress("127.0.0.1",0));server.configureBlocking(false);
        worker=Thread.ofPlatform().daemon().name("androidphone-environment").start(this::serve);
    }
    int port()throws IOException{return ((InetSocketAddress)server.getLocalAddress()).getPort();}
    String status(){return status;}
    void publish(EnvironmentPacket packet){if(!closed)latest.set(new Sample(packet,System.nanoTime()));}
    void invalidate(){latest.updateAndGet(s->s==null?null:new Sample(s.packet().unavailable(),System.nanoTime()));}
    private void transfer(SocketChannel socket,ByteBuffer bytes,boolean write)throws IOException,InterruptedException {
        long deadline=System.nanoTime()+2_000_000_000L;
        while(bytes.hasRemaining()&&!closed) {
            int n=write?socket.write(bytes):socket.read(bytes);
            if(n<0)throw new EOFException();
            if(System.nanoTime()>deadline)throw new SocketTimeoutException("Environment receiver timeout");
            if(n==0)Thread.sleep(5);
        }
        if(closed)throw new EOFException();
    }
    private void serve() {
        while(!closed)try {
            SocketChannel socket=server.accept();if(socket==null){Thread.sleep(20);continue;}
            peer=socket;
            try(socket) {
                socket.configureBlocking(false);socket.setOption(java.net.StandardSocketOptions.TCP_NODELAY,true);
                // Guest initiates after opening the named port. Do not flood an unprepared guest.
                ByteBuffer hello=ByteBuffer.allocate(8);transfer(socket,hello,false);hello.flip();
                if(hello.getInt()!=EnvironmentPacket.MAGIC||hello.getInt()!=EnvironmentPacket.VERSION)throw new IOException("Environment handshake mismatch");
                status="Android environment receiver connected";
                while(!closed) {
                    Sample current=latest.get();if(current==null){Thread.sleep(20);continue;}
                    EnvironmentPacket packet=System.nanoTime()-current.received()>500_000_000L?current.packet().unavailable():current.packet();
                    transfer(socket,ByteBuffer.wrap(packet.encode()),true);
                    ByteBuffer ack=ByteBuffer.allocate(8);transfer(socket,ack,false);ack.flip();
                    if(ack.getLong()!=packet.sequence)throw new IOException("Environment acknowledgement mismatch");
                    Thread.sleep(50);
                }
            }finally{peer=null;}
        }catch(Exception e){if(!closed){status="waiting for Android environment receiver: "+e.getClass().getSimpleName();try{Thread.sleep(100);}catch(InterruptedException ignored){}}}
    }
    @Override public void close(){closed=true;latest.set(null);try{server.close();}catch(IOException ignored){}SocketChannel socket=peer;if(socket!=null)try{socket.close();}catch(IOException ignored){}
        worker.interrupt();if(Thread.currentThread()!=worker)try{worker.join(2500);}catch(InterruptedException e){Thread.currentThread().interrupt();}status="stopped";}
}
