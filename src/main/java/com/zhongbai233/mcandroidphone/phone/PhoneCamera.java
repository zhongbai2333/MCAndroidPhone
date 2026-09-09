package com.zhongbai233.mcandroidphone.phone;

import com.zhongbai233.mcandroidphone.core.ManagedRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.CameraType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import com.mojang.logging.LogUtils;

/** Independent handset world pass with bounded asynchronous readback and JPEG encoding. */
final class PhoneCamera {
    private static final AtomicBoolean busy=new AtomicBoolean();
    private static final ExecutorService encoder=Executors.newSingleThreadExecutor(r->Thread.ofPlatform().daemon().name("androidphone-camera-encode").unstarted(r));
    private static long lastCapture,sequence;
    private static final Matrix4f handset=new Matrix4f();
    private static long poseTime;
    private static PhoneWorldCamera world;
    private static boolean copyPending,closing;
    static void handset(Matrix4fc transform){handset.set(transform);poseTime=System.nanoTime();}
    static void close(){closing=true;poseTime=0;if(!copyPending)dispose();}
    private static void dispose(){if(world!=null){world.close();world=null;}}
    static void capture(Minecraft mc,ManagedRuntime runtime,boolean held) {
        if(runtime==null){close();return;}
        if(!held||mc.level==null||mc.isPaused()||mc.options.getCameraType()!=CameraType.FIRST_PERSON){runtime.invalidateCamera();return;}
        long now=System.nanoTime();var request=runtime.cameraRequest();if(request==null||poseTime==0||now-poseTime>500_000_000L||now-lastCapture<100_000_000L||!busy.compareAndSet(false,true))return;
        lastCapture=now;long id=++sequence;
        try {
            closing=false;if(world==null)world=new PhoneWorldCamera();var target=world.render(mc,handset,request.facing());copyPending=true;
            Screenshot.takeScreenshot(target,image->{copyPending=false;if(closing)dispose();encoder.execute(()->{
                try(image) {
                    int sw=image.getWidth(),sh=image.getHeight();double scale=Math.min(1,640.0/Math.max(sw,sh));int w=Math.max(2,(int)(sw*scale)),h=Math.max(2,(int)(sh*scale));
                    var rgb=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++)rgb.setRGB(x,y,image.getPixel(Math.min(sw-1,x*sw/w),Math.min(sh-1,y*sh/h)));
                    var bytes=new ByteArrayOutputStream();if(!ImageIO.write(rgb,"jpeg",bytes))throw new IllegalStateException("JPEG encoder missing");
                    runtime.publishCamera(request,id,w,h,bytes.toByteArray());
                }catch(Exception e){runtime.invalidateCamera();LogUtils.getLogger().error("Phone camera encode failed",e);}finally{busy.set(false);}
            });});
        }catch(RuntimeException e){copyPending=false;busy.set(false);runtime.invalidateCamera();LogUtils.getLogger().error("Phone camera render failed",e);close();}
    }
}
