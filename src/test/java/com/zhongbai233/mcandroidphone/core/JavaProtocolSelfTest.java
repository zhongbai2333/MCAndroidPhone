package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class JavaProtocolSelfTest {
    private static void require(boolean ok,String message){ManagedRuntimeSelfTest.require(ok,message);}
    static void run()throws Exception {
        var map=Map.of("id",Long.MAX_VALUE,"text","中\n😀\\\"","array",List.of(true,1L));
        require(Json.object(Json.write(map)).equals(map),"JSON round trip");
        for(String invalid:List.of("{\"x\":1,\"x\":2}","[1,]","01","1e999","\"a\n\"","[".repeat(66)+"]".repeat(66))) {
            try{Json.parse(invalid);throw new AssertionError("Invalid JSON accepted: "+invalid);}catch(IOException expected){}
        }
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var fixture=CompletableFuture.runAsync(()->{try(var socket=server.accept()) {
                socket.setSoTimeout(5000);var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));var out=socket.getOutputStream();
                out.write("{\"QMP\":{}}\n".getBytes(StandardCharsets.UTF_8));out.flush();
                for(int i=0;i<2;i++){var request=Json.object(in.readLine());out.write(("{\"event\":\"STOP\"}\n{\"id\":999,\"return\":{}}\n"+Json.write(Map.of("id",request.get("id"),"return",Map.of("status","running")))+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}
            }catch(Exception e){throw new CompletionException(e);}});
            try(var qmp=new QmpClient(server.getLocalPort(),2000)){require(((Map<?,?>)qmp.execute("query-status",null)).get("status").equals("running"),"QMP event/ID isolation");}fixture.get(5,TimeUnit.SECONDS);
        }
        for(int version:List.of(3,7,8))rfb(version);
        localLeases();RuntimeConfigSelfTest.run();DbusCodecSelfTest.run();System.out.println("JAVA_JSON_QMP_RFB_LEASE_TESTS_OK");
    }
    private static void localLeases()throws Exception {
        try(var device=new LocalDevice()) {
            long generation=device.resize(4,4);try(var view=device.connect()) {
                byte[] first=new byte[24];Arrays.fill(first,(byte)23);device.publish(first,generation);
                try(var held=view.pollFrame()){require(held!=null,"Initial frame missing");
                    for(int i=0;i<12;i++){byte[] next=new byte[24];Arrays.fill(next,(byte)i);device.publish(next,generation);try(var frame=view.pollFrame()){require(frame!=null,"Frame pool exhausted");}}
                    require(held.pixels().get(0)==23,"Leased old frame corrupted");
                    long epoch=view.connectionEpoch(),next=device.resize(8,4);require(view.connectionEpoch()!=epoch,"Resize epoch unchanged");device.publish(first,generation);require(view.pollFrame()==null,"Stale resolution frame accepted");
                    device.publish(new byte[48],next);try(var frame=view.pollFrame()){require(frame.width()==8,"New dimensions missing");}
                }
            }
        }
    }
    private static void rfb(int version)throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var fixture=CompletableFuture.runAsync(()->{try(var socket=server.accept()) {
                socket.setSoTimeout(5000);var in=new DataInputStream(socket.getInputStream());var out=new DataOutputStream(socket.getOutputStream());
                out.writeBytes(String.format(Locale.ROOT,"RFB 003.%03d\n",version));out.flush();require(in.readNBytes(12).length==12,"RFB response");
                if(version==3)out.writeInt(1);else {out.write(new byte[]{1,1});out.flush();require(in.readUnsignedByte()==1,"RFB None selection");if(version==8)out.writeInt(0);}out.flush();in.readByte();
                out.writeShort(4);out.writeShort(4);out.write(new byte[16]);out.writeInt(4);out.writeBytes("test");out.flush();
                require(in.readNBytes(20).length==20,"Pixel format");require(in.readNBytes(16).length==16,"Encodings");
                in.readNBytes(10);out.write(new byte[]{0,0,0,1});rect(out,0,0,4,4,0);for(int i=0;i<16;i++)out.write(new byte[]{(byte)i,20,30,0});out.flush();
                in.readNBytes(10);out.write(new byte[]{0,0,0,1});rect(out,0,1,4,3,1);out.writeShort(0);out.writeShort(0);out.flush();
                in.readNBytes(10);out.write(new byte[]{0,0,0,1});rect(out,0,0,8,4,-223);out.flush();
                in.readNBytes(10);out.write(new byte[]{0,0,0,1});rect(out,7,0,2,2,0);out.flush();
            }catch(Exception e){throw new CompletionException(e);}});
            var sizes=new ArrayList<String>();RfbClient.Resize resize=(w,h)->sizes.add(w+"x"+h);
            try(var rfb=new RfbClient(server.getLocalPort(),resize)) {
                rfb.request(false);require(rfb.update(resize,false).changed(),"Raw not changed");require(rfb.pixels[60]==15,"Raw data mismatch");
                rfb.request(true);rfb.update(resize,false);require(rfb.pixels[48]==8&&rfb.pixels[16]==0,"Overlapping CopyRect corrupted");
                rfb.request(true);var resized=rfb.update(resize,false);require(resized.resized()&&!resized.changed()&&sizes.equals(List.of("4x4","8x4")),"DesktopSize state");
                rfb.request(false);try{rfb.update(resize,false);throw new AssertionError("Out of bounds accepted");}catch(IOException expected){}
            }fixture.get(5,TimeUnit.SECONDS);
        }
    }
    private static void rect(DataOutputStream out,int x,int y,int w,int h,int encoding)throws IOException {out.writeShort(x);out.writeShort(y);out.writeShort(w);out.writeShort(h);out.writeInt(encoding);}
}
