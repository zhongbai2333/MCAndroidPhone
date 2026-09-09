package com.zhongbai233.mcandroidphone.core;

import java.nio.file.*;
import java.nio.ByteBuffer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Manual acceptance: load the bundled runtime from the classpath into a NEW game directory. */
public final class FactoryAndroidTest {
    private static int channel(double value){return Math.max(0,Math.min(255,(int)Math.round(value)));}
    private static void capture(Frame frame,Path file)throws Exception {
        int w=frame.width(),h=frame.height();ByteBuffer pixels=frame.pixels();var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double l=(Byte.toUnsignedInt(pixels.get(y*w+x))-16)*255.0/219;
            int uv=w*h+(y/2)*w+(x/2)*2;double u=(Byte.toUnsignedInt(pixels.get(uv))-128)*255.0/224,v=(Byte.toUnsignedInt(pixels.get(uv+1))-128)*255.0/224;
            image.setRGB(x,y,(channel(l+1.5748*v)<<16)|(channel(l-.187324*u-.468124*v)<<8)|channel(l+1.8556*u));
        }
        ImageIO.write(image,"png",file.toFile());
    }

    public static void main(String[] args)throws Exception {
        Path evidence=Path.of(args[0]).toAbsolutePath();Files.createDirectory(evidence);
        TrimmedAndroidTest.evidence=evidence;TrimmedAndroidTest.adb=Path.of(args[1]).toAbsolutePath();
        TrimmedAndroidTest.server=TrimmedAndroidTest.port();int guest=TrimmedAndroidTest.port();
        Path game=args.length>2?Path.of(args[2]).toAbsolutePath():evidence.resolve("game");
        var runtime=new ManagedRuntime(game,Map.of("deviceId",UUID.randomUUID().toString(),"adbPort",""+guest),()->FactoryAndroidTest.class.getResourceAsStream("/mcandroidphone/runtime/native-guard.jar"));
        PhoneConnection connection=null;Frame latest=null;
        try {
            long began=System.nanoTime();runtime.start().get(180,TimeUnit.SECONDS);long readyMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
            Files.writeString(evidence.resolve("startup.json"),Json.write(Map.of("runtimeReadyMs",readyMs)));
            System.out.println("FACTORY_RUNTIME_READY_MS "+readyMs);connection=runtime.connect();connection.start();
            // A clean user image may not expose ADB. Capture the production render for manual inspection.
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(70),nextCapture=0;int frames=0;
            while(System.nanoTime()<until) {
                Frame f=connection.pollFrame();
                if(f!=null){if(latest!=null)latest.close();latest=f;frames++;
                    if(System.nanoTime()>=nextCapture){capture(latest,evidence.resolve("factory-render.png"));nextCapture=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);}}

                if(runtime.state()==ManagedRuntime.State.FAILED)throw new IllegalStateException(runtime.status());
                Thread.sleep(50);
            }
            if(latest!=null)capture(latest,evidence.resolve("factory-render.png"));
            TrimmedAndroidTest.require(frames>30,"Insufficient factory render frames");
            System.out.println("FACTORY_RENDER_CAPTURED frames="+frames+" session="+runtime.sessionDirectory()+"; inspect factory-render.png before claiming Android startup");

        } finally {
            if(latest!=null)latest.close();if(connection!=null)connection.close();runtime.close();runtime.awaitStopped(Duration.ofSeconds(30));
            TrimmedAndroidTest.require(runtime.state()==ManagedRuntime.State.STOPPED,"Factory runtime did not stop");
            var session=Json.object(Files.readString(runtime.sessionDirectory().resolve("session.json")));
            System.out.println("FACTORY_SHUTDOWN_OUTCOME "+session.get("shutdownOutcome"));
            System.out.println(TrimmedAndroidTest.adb(0,"kill-server"));
        }
    }
}
