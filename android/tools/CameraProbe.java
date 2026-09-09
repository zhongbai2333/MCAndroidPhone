package com.zhongbai233.mcandroidphone.guest;

import java.io.*;
import java.net.*;
import java.lang.reflect.*;

/** Development receiver: proves Android Bitmap decoding, not Camera2/HAL registration. */
public final class CameraProbe {
    public static void main(String[] args)throws Exception {
        if(args.length<3||args.length>5)throw new IllegalArgumentException("host port output-directory [rear|front|alternate] [seconds]");
        String mode=args.length>3?args[3]:"rear";
        if(!mode.equals("rear")&&!mode.equals("front")&&!mode.equals("alternate"))throw new IllegalArgumentException("Unknown lens: "+mode);
        int seconds=args.length>4?Integer.parseInt(args[4]):120;
        if(seconds<1||seconds>600)throw new IllegalArgumentException("Seconds must be 1..600");
        File folder=new File(args[2]);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create output directory");
        Class<?> factory=Class.forName("android.graphics.BitmapFactory"),bitmap=Class.forName("android.graphics.Bitmap");
        Method decode=factory.getMethod("decodeByteArray",byte[].class,int.class,int.class),width=bitmap.getMethod("getWidth"),height=bitmap.getMethod("getHeight"),pixel=bitmap.getMethod("getPixel",int.class,int.class),recycle=bitmap.getMethod("recycle");
        long started=System.nanoTime(),end=started+seconds*1_000_000_000L,previous=0;int frames=0,rear=0,front=0,lastLens=0;
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(args[0],Integer.parseInt(args[1])),5000);socket.setSoTimeout(5000);
            DataInputStream in=new DataInputStream(socket.getInputStream());DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeInt(0x4d435043);out.writeInt(1);out.flush();
            while(System.nanoTime()<end) {
                int lens=mode.equals("front")?2:mode.equals("alternate")?(int)((System.nanoTime()-started)/10_000_000_000L%2)+1:1;
                out.writeInt(lens);out.flush();int length=in.readInt();if(length==0){Thread.sleep(100);continue;}
                if(length<32||length>28+2*1024*1024)throw new IOException("Invalid camera length");
                if(in.readInt()!=0x4d435043||in.readInt()!=1)throw new IOException("Camera protocol mismatch");long sequence=in.readLong();
                int w=in.readInt(),h=in.readInt(),size=in.readInt();if(size!=length-28||w<2||h<2||w>640||h>640||sequence<previous)throw new IOException("Invalid camera frame");
                byte[] jpeg=new byte[size];in.readFully(jpeg);Object image=decode.invoke(null,jpeg,0,jpeg.length);if(image==null)throw new IOException("Android JPEG decode failed");
                try {
                    if((Integer)width.invoke(image)!=w||(Integer)height.invoke(image)!=h)throw new IOException("Decoded dimensions differ");
                    int center=(Integer)pixel.invoke(image,w/2,h/2);
                    if(sequence!=previous){frames++;if(lens==1)rear++;else front++;if(lens!=lastLens||frames%25==0){try(FileOutputStream file=new FileOutputStream(new File(folder,(lens==1?"rear":"front")+"-"+sequence+".jpg"))){file.write(jpeg);}System.out.println("ANDROID_CAMERA_FRAME_OK sequence="+sequence+" lens="+lens+" width="+w+" height="+h+" center="+Integer.toHexString(center)+" decoded="+frames);}lastLens=lens;}
                }finally{recycle.invoke(image);}previous=sequence;Thread.sleep(100);
            }
        }catch(EOFException e){if(frames==0)throw e;}
        if(frames==0||(mode.equals("alternate")&&(rear==0||front==0)))throw new IOException("Missing Android-decoded lens frames");System.out.println("ANDROID_CAMERA_PROBE_OK frames="+frames+" rear="+rear+" front="+front+" source=handset-world-pass HAL=false");
    }
}
