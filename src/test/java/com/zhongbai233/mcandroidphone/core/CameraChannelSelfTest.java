package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public final class CameraChannelSelfTest {
    public static void main(String[] args)throws Exception {
        try(var channel=new CameraChannel();var socket=new Socket("127.0.0.1",channel.port())) {
            socket.setSoTimeout(4000);var in=new DataInputStream(socket.getInputStream());var out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(CameraChannel.MAGIC);out.writeInt(1);out.writeInt(1);out.flush();
            if(in.readInt()!=0||!channel.demanded())throw new AssertionError("Empty pull/demand");
            byte[] jpeg={(byte)255,(byte)216,(byte)255,(byte)217};channel.publish(7,320,240,jpeg);jpeg[0]=0;
            out.writeInt(1);out.flush();if(in.readInt()!=32||in.readInt()!=CameraChannel.MAGIC||in.readInt()!=1||in.readLong()!=7||in.readInt()!=320||in.readInt()!=240||in.readInt()!=4||in.readUnsignedByte()!=255)throw new AssertionError("Frame layout or ownership");in.readNBytes(3);
            Thread.sleep(600);out.writeInt(1);out.flush();if(in.readInt()!=0)throw new AssertionError("Stale image replayed");
            var oldRequest=channel.request();channel.publish(8,320,240,jpeg);channel.invalidate();out.writeInt(1);out.flush();if(in.readInt()!=0)throw new AssertionError("Invalidated image replayed");
            channel.publish(oldRequest,9,320,240,jpeg);out.writeInt(1);out.flush();if(in.readInt()!=0)throw new AssertionError("Late encoding revived invalidated camera");
            var rear=channel.request();out.writeInt(2);out.flush();if(in.readInt()!=0)throw new AssertionError("Rear frame leaked into front lens");
            var front=channel.request();if(front.facing()!=2||front.generation()==rear.generation())throw new AssertionError("Lens generation unchanged");
            channel.publish(rear,10,320,240,jpeg);out.writeInt(2);out.flush();if(in.readInt()!=0)throw new AssertionError("Late rear frame accepted");
            channel.publish(front,11,320,240,jpeg);out.writeInt(2);out.flush();if(in.readInt()!=32)throw new AssertionError("Front frame missing");in.readNBytes(32);
            out.writeInt(1);out.flush();if(in.readInt()!=0)throw new AssertionError("Front frame leaked into rear lens");
            channel.publish(front,12,320,240,jpeg);out.writeInt(1);out.flush();if(in.readInt()!=0)throw new AssertionError("Late front frame accepted");
            long start=System.nanoTime();channel.close();if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(3))throw new AssertionError("Shutdown blocked");
        }
        System.out.println("CAMERA_CHANNEL_TESTS_OK: pull, ownership, dimensions, stale, invalidation, lens switching, late encoding, shutdown");
    }
}
