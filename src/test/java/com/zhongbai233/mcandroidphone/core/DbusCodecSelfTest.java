package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Golden vectors were produced by the original Python D-Bus implementation; no Python needed to run. */
final class DbusCodecSelfTest {
    private static void require(boolean ok,String message){ManagedRuntimeSelfTest.require(ok,message);}
    private static void golden(String signature,List<?> values,String encoded)throws Exception {require(Arrays.equals(DbusCodec.values(signature,values,0),Base64.getDecoder().decode(encoded)),"D-Bus ABI mismatch: "+signature);}
    static void run()throws Exception {
        golden("utdd",List.of(2L,0L,123.5,456.25),"AgAAAAAAAAAAAAAAAAAAAAAAAAAA4F5AAAAAAACEfEA=");
        golden("a{sv}",List.of(Map.of("Interfaces",new DbusCodec.Variant("as",List.of("org.qemu.Display1.Listener.Win32.Map")))),"QQAAAAAAAAAKAAAASW50ZXJmYWNlcwACYXMAACkAAAAkAAAAb3JnLnFlbXUuRGlzcGxheTEuTGlzdGVuZXIuV2luMzIuTWFwAA==");
        golden("tuubuuuu",List.of(123456L,1920L,1080L,true,0L,0L,1080L,1920L),"QOIBAAAAAACABwAAOAQAAAEAAAAAAAAAAAAAADgEAACABwAA");
        byte[] pixels=new byte[16];for(int i=0;i<16;i++)pixels[i]=(byte)i;
        golden("uiay",List.of(0x20020888L,-123L,pixels),"iAgCIIX///8QAAAAAAECAwQFBgcICQoLDA0ODw==");
        golden("a(uv)",List.of(List.of(List.of(1L,new DbusCodec.Variant("s","中文😀")),List.of(2L,new DbusCodec.Variant("t",Long.MIN_VALUE+1)))),"KAAAAAAAAAABAAAAAXMAAAoAAADkuK3mlofwn5iAAAACAAAAAXQAAAEAAAAAAACA");
        byte[] message=Base64.getDecoder().decode("bAEAAR8AAAAFAAAAZwAAAAEBbwAbAAAAL29yZy9xZW11L0Rpc3BsYXkxL0xpc3RlbmVyAAAAAAACAXMAHwAAAG9yZy5mcmVlZGVza3RvcC5EQnVzLlByb3BlcnRpZXMAAwFzAAYAAABHZXRBbGwAAAgBZwABcwAAGgAAAG9yZy5xZW11LkRpc3BsYXkxLkxpc3RlbmVyAA==");
        var headers=new LinkedHashMap<Integer,DbusCodec.Variant>();headers.put(1,new DbusCodec.Variant("o","/org/qemu/Display1/Listener"));headers.put(2,new DbusCodec.Variant("s","org.freedesktop.DBus.Properties"));headers.put(3,new DbusCodec.Variant("s","GetAll"));
        require(Arrays.equals(message,DbusCodec.encode(1,5,headers,"s",List.of("org.qemu.Display1.Listener"))),"D-Bus header ABI mismatch");
        var decoded=DbusCodec.decode(Arrays.copyOf(message,16),Arrays.copyOfRange(message,16,message.length));require(decoded.path().equals("/org/qemu/Display1/Listener")&&decoded.body().equals(List.of("org.qemu.Display1.Listener")),"D-Bus message decode");
        for(String sig:List.of("a","(","{u}","z","a".repeat(34)+"s"))try{DbusCodec.types(sig);throw new AssertionError("Invalid signature accepted");}catch(IOException expected){}
        byte[] bad=Arrays.copyOf(message,16);Arrays.fill(bad,4,8,(byte)255);try{DbusCodec.remaining(bad);throw new AssertionError("Oversize D-Bus accepted");}catch(IOException expected){}
        byte[] data=new byte[6*4*4];for(int row=0;row<4;row++)Arrays.fill(data,row*24,row*24+16,(byte)(row+1));
        try(var surface=new DbusSurface(0,0,4,4,24,0x20020888L,"rgb",data)) {
            byte[] compact=surface.snapshot();require(compact.length==64&&compact[16]==2&&compact[48]==4,"Padded surface stride");
            surface.update(1,1,2,2,8,0x20020888L,new byte[16],"rgb");require(surface.snapshot()[20]==0&&surface.snapshot()[16]==2,"Dirty update damaged neighboring pixels");
            try{surface.update(3,0,2,2,8,0x20020888L,new byte[16],"rgb");throw new AssertionError("Out of bounds dirty rectangle");}catch(IOException expected){}
        }
        peer();if(System.getProperty("os.name").startsWith("Windows"))windows();
        System.out.println("JAVA_DBUS_GOLDEN_PEER_SURFACE_TESTS_OK");
    }
    private static DbusCodec.Message receive(InputStream input)throws Exception {byte[] fixed=input.readNBytes(16);return DbusCodec.decode(fixed,input.readNBytes(DbusCodec.remaining(fixed)));}
    private static void auth(InputStream input,OutputStream output)throws Exception {
        var line=new ByteArrayOutputStream();int b;while((b=input.read())!='\n'){if(b<0)throw new EOFException();line.write(b);}
        require(line.toString(StandardCharsets.US_ASCII).startsWith("\0AUTH ANONYMOUS"),"Peer authentication");output.write("OK 01234567890123456789012345678901\r\n".getBytes(StandardCharsets.US_ASCII));output.flush();
        require(new String(input.readNBytes(7),StandardCharsets.US_ASCII).equals("BEGIN\r\n"),"Peer BEGIN");
    }
    private static void peer()throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var fixture=CompletableFuture.runAsync(()->{try(var socket=server.accept()) {
                socket.setSoTimeout(5000);var input=socket.getInputStream();var output=socket.getOutputStream();auth(input,output);var call=receive(input);
                output.write(DbusCodec.encode(1,10,Map.of(1,new DbusCodec.Variant("o","/test"),2,new DbusCodec.Variant("s","org.test.Callback"),3,new DbusCodec.Variant("s","Echo")),"s",List.of("hello")));output.flush();
                var callback=receive(input);require(callback.kind()==2&&callback.headers().get(5).equals(10L)&&callback.body().equals(List.of("hello")),"Method callback reply");
                output.write(DbusCodec.encode(2,11,Map.of(5,new DbusCodec.Variant("u",call.serial())),"a{sv}",List.of(Map.of("Ready",new DbusCodec.Variant("b",true)))));output.flush();
            }catch(Exception e){throw new CompletionException(e);}});
            try(var socket=new Socket("127.0.0.1",server.getLocalPort());var peer=new DbusPeer(socket.getInputStream(),socket.getOutputStream(),socket,m->new DbusPeer.Reply("s",m.body()))) {
                socket.setSoTimeout(250);peer.authenticate();var body=peer.call("/test","org.test.Control","Ready","",List.of());require(((Map<?,?>)body.getFirst()).get("Ready").equals(new DbusCodec.Variant("b",true)),"Concurrent method/reply dispatch");
            }fixture.get(5,TimeUnit.SECONDS);
        }
    }
    private static void windows()throws Exception {
        var pair=WindowsSocket.pair(Path.of(System.getProperty("java.io.tmpdir")));
        try(var a=pair[0];var b=pair[1]){require(b.share(ProcessHandle.current().pid()).length==628,"WSAPROTOCOL_INFOW ABI");a.output().write(new byte[]{23,45,67});require(Arrays.equals(b.input().readNBytes(3),new byte[]{23,45,67}),"Winsock AF_UNIX stream");}
        System.out.println("WINDOWS_AF_UNIX_NATIVE_OK");
    }
}
