package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.SocketTimeoutException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** AF_UNIX Winsock transport. The socket is handed to QEMU with WSADuplicateSocketW. */
final class WindowsSocket implements AutoCloseable {
    private static final SymbolLookup WS=SymbolLookup.libraryLookup("ws2_32",Arena.global());
    private static MethodHandle function(String n,FunctionDescriptor d){return Linker.nativeLinker().downcallHandle(WS.find(n).orElseThrow(),d);}
    private static final MethodHandle SOCKET=function("WSASocketW",FunctionDescriptor.of(JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT)),
        BIND=function("bind",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT)),
        CONNECT=function("connect",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT)),
        LISTEN=function("listen",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT)),
        ACCEPT=function("accept",FunctionDescriptor.of(JAVA_LONG,JAVA_LONG,ADDRESS,ADDRESS)),
        RECV=function("recv",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT,JAVA_INT)),
        SEND=function("send",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT,JAVA_INT)),
        OPTION=function("setsockopt",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_INT,ADDRESS,JAVA_INT)),
        ERROR=function("WSAGetLastError",FunctionDescriptor.of(JAVA_INT)),
        SHARE=function("WSADuplicateSocketW",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT,ADDRESS)),
        SHUTDOWN=function("shutdown",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT)),
        CLOSE=function("closesocket",FunctionDescriptor.of(JAVA_INT,JAVA_LONG));
    static {try(var arena=Arena.ofConfined()){int error=(int)function("WSAStartup",FunctionDescriptor.of(JAVA_INT,JAVA_SHORT,ADDRESS)).invokeExact((short)0x202,arena.allocate(408,8));if(error!=0)throw new IOException("WSAStartup: "+error);}catch(Throwable e){throw new ExceptionInInitializerError(e);}}
    private static IOException error()throws Throwable {int code=(int)ERROR.invokeExact();return code==10060?new SocketTimeoutException("Winsock timeout"):new IOException("Winsock error "+code);}
    private static IOException failure(Throwable e){return e instanceof IOException io?io:new IOException("Winsock call failed",e);}
    private final long handle;private final Object reads=new Object(),writes=new Object();private volatile boolean closed;
    private WindowsSocket(long handle)throws IOException {this.handle=handle;try(var arena=Arena.ofConfined()){Win32Handles.nonInheritable(handle);var timeout=arena.allocate(JAVA_INT);timeout.set(JAVA_INT,0,250);if((int)OPTION.invokeExact(handle,0xffff,0x1006,timeout,4)!=0)throw error();timeout.set(JAVA_INT,0,3000);if((int)OPTION.invokeExact(handle,0xffff,0x1005,timeout,4)!=0)throw error();}catch(Throwable e){close();throw failure(e);}}
    private static long create()throws Throwable{long h=(long)SOCKET.invokeExact(1,1,0,MemorySegment.NULL,0,0x81);if(h==-1)throw error();return h;}
    static WindowsSocket[] pair(Path directory)throws IOException {
        Path path=directory.resolve("db-"+UUID.randomUUID().toString().substring(0,12));byte[] raw=path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
        if(raw.length>=108)throw new IOException("Windows local socket path exceeds 107 bytes");long server=-1,client=-1,accepted=-1;boolean bound=false;WindowsSocket a=null;
        try(var arena=Arena.ofConfined()) {
            var address=arena.allocate(110,2);address.set(JAVA_SHORT,0,(short)1);MemorySegment.copy(raw,0,address,JAVA_BYTE,2,raw.length);
            server=create();if((int)BIND.invokeExact(server,address,110)!=0)throw error();bound=true;if((int)LISTEN.invokeExact(server,1)!=0)throw error();
            client=create();if((int)CONNECT.invokeExact(client,address,110)!=0)throw error();accepted=(long)ACCEPT.invokeExact(server,MemorySegment.NULL,MemorySegment.NULL);if(accepted==-1)throw error();
            long local=client;client=-1;a=new WindowsSocket(local);long remote=accepted;accepted=-1;var b=new WindowsSocket(remote);return new WindowsSocket[]{a,b};
        }catch(Throwable e){if(a!=null)a.close();throw failure(e);}finally{closeRaw(server);closeRaw(client);closeRaw(accepted);if(bound)Files.deleteIfExists(path);}
    }
    byte[] share(long pid)throws IOException {
        if(pid<=0||pid>0xffffffffL)throw new IOException("Invalid QEMU PID");
        try(var arena=Arena.ofConfined()){var info=arena.allocate(628,4);if((int)SHARE.invokeExact(handle,(int)pid,info)!=0)throw error();return info.toArray(JAVA_BYTE);}catch(Throwable e){throw failure(e);}
    }
    InputStream input(){return new InputStream(){@Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int offset,int length)throws IOException {Objects.checkFromIndexSize(offset,length,b.length);if(length==0)return 0;
            synchronized(reads){if(closed)throw new EOFException("Winsock closed");try(var arena=Arena.ofConfined()){int size=Math.min(length,256*1024);var buffer=arena.allocate(size);int n=(int)RECV.invokeExact(handle,buffer,size,0);if(n<0)throw error();if(n==0)return -1;MemorySegment.copy(buffer,JAVA_BYTE,0,b,offset,n);return n;}catch(Throwable e){throw failure(e);}}}};}
    OutputStream output(){return new OutputStream(){@Override public void write(int b)throws IOException{write(new byte[]{(byte)b});}
        @Override public void write(byte[] b,int offset,int length)throws IOException {Objects.checkFromIndexSize(offset,length,b.length);synchronized(writes){if(closed)throw new EOFException("Winsock closed");
            try(var arena=Arena.ofConfined()){var buffer=arena.allocate(Math.min(Math.max(1,length),256*1024));while(length>0){int size=(int)Math.min(length,buffer.byteSize());MemorySegment.copy(b,offset,buffer,JAVA_BYTE,0,size);int n=(int)SEND.invokeExact(handle,buffer,size,0);if(n<=0)throw error();offset+=n;length-=n;}}catch(Throwable e){throw failure(e);}}}};}
    void disconnect(){if(!closed)try{int ignored=(int)SHUTDOWN.invokeExact(handle,2);}catch(Throwable ignored){}close();}
    private static void closeRaw(long handle){if(handle!=-1)try{int ignored=(int)CLOSE.invokeExact(handle);}catch(Throwable ignored){}}
    @Override public void close(){synchronized(reads){synchronized(writes){if(closed)return;closed=true;closeRaw(handle);}}}
}
