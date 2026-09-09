package com.zhongbai233.mcandroidphone.core;

import com.zhongbai233.mcandroidphone.environment.*;
import java.io.*;
import java.net.*;
import java.util.Arrays;

public final class EnvironmentSelfTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void near(double value,double expected){check(Math.abs(value-expected)<1e-6,value+" != "+expected);}
    private static EnvironmentPacket sample(EnvironmentSampler s,long now,double x,double y,double z,double yaw,double roll,boolean active){
        return s.sample(now,"minecraft:overworld",x,y,z,yaw,0,roll,0,0,15,active);
    }
    public static void main(String[] args)throws Exception {
        var s=new EnvironmentSampler();long t=1_000_000_000L;
        var upright=sample(s,t,0,63,0,0,0,true);
        near(upright.ax,0);near(upright.ay,9.80665);near(upright.az,0);near(upright.pressure,1013.25);
        check(upright.discontinuity&&upright.locationValid,"Initial origin");
        var landscape=sample(s,t+50_000_000L,0,63,0,0,-90,true);
        near(landscape.ax,-9.80665);near(landscape.ay,0);near(landscape.gz,-Math.PI/.1);
        var east=sample(s,t+100_000_000L,.1,63,0,0,-90,true);near(east.speed,2);near(east.bearing,90);check(east.longitude>0,"East longitude");
        var walking=sample(s,t+150_000_000L,.2,63,0,0,-90,true);near(walking.ax,-9.80665);near(walking.ay,0);
        var teleported=sample(s,t+200_000_000L,1000,163,0,0,0,true);check(teleported.discontinuity&&teleported.pressure<1013.25,"Teleport must reset derivatives");near(teleported.speed,0);
        var paused=sample(s,t+250_000_000L,1000,163,0,0,0,false);check(!paused.available&&!paused.locationValid,"Pause invalidates location");
        var nether=s.sample(t+300_000_000L,"minecraft:the_nether",0,63,0,0,0,0,0,0,0,true);check(!nether.locationValid&&nether.discontinuity,"Dimensions remain distinct");
        var round=EnvironmentPacket.read(new ByteArrayInputStream(landscape.encode()));check(Arrays.equals(round.values(),landscape.values())&&round.sequence==landscape.sequence,"Wire round trip");
        byte[] corrupt=landscape.encode();corrupt[7]=0;
        try{EnvironmentPacket.read(new ByteArrayInputStream(corrupt));throw new AssertionError("Bad magic accepted");}catch(IOException expected){}
        try{EnvironmentPacket.read(new ByteArrayInputStream(new byte[]{0x7f,0x7f,0x7f,0x7f}));throw new AssertionError("Unbounded allocation");}catch(IOException expected){}
        try{new EnvironmentPacket(1,0,true,true,false,"../bad space",upright.values());throw new AssertionError("Bad dimension");}catch(IllegalArgumentException expected){}
        try(var channel=new EnvironmentChannel()) {
            int port=channel.port();check(port>1024,"Ephemeral endpoint");
            try(var socket=new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(4000);var output=new DataOutputStream(socket.getOutputStream());
                output.writeInt(EnvironmentPacket.MAGIC);output.writeInt(EnvironmentPacket.VERSION);output.flush();
                channel.publish(upright);EnvironmentPacket received=EnvironmentPacket.read(socket.getInputStream());check(received.available,"Live sample");output.writeLong(received.sequence);output.flush();
                channel.invalidate();received=EnvironmentPacket.read(socket.getInputStream());check(!received.available,"Disconnect must invalidate sample");output.writeLong(received.sequence);output.flush();
                for(int i=0;i<10000;i++)channel.publish(landscape); // latest-only, never an unbounded queue
                long deadline=System.nanoTime()+2_000_000_000L;
                do{received=EnvironmentPacket.read(socket.getInputStream());output.writeLong(received.sequence);output.flush();}while(received.sequence!=landscape.sequence&&System.nanoTime()<deadline);
                check(received.sequence==landscape.sequence,"Latest wins");
                do{received=EnvironmentPacket.read(socket.getInputStream());output.writeLong(received.sequence);output.flush();}while(received.available&&System.nanoTime()<deadline);
                check(!received.available,"Stale data must expire");
                // Closing a stalled peer cannot hold shutdown hostage.
                long start=System.nanoTime();channel.close();check(System.nanoTime()-start<1_000_000_000L,"Bounded shutdown");
            }
        }
        System.out.println("ENVIRONMENT_WIRE_SI_ORIENTATION_LIFECYCLE_OK");
    }
}
