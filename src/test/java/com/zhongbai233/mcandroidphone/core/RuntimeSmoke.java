package com.zhongbai233.mcandroidphone.core;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real Java-managed native runtime -> in-process NV12, without a Minecraft installation. */
public final class RuntimeSmoke {
    public static void main(String[] args) throws Exception {
        var options=new HashMap<String,String>();
        options.put("root",args[0]);
        for(int i=1;i<args.length;i++){int split=args[i].indexOf('=');if(split<1)throw new IllegalArgumentException(args[i]);
            options.put(args[i].substring(0,split),args[i].substring(split+1));}
        int expectedWidth=Integer.parseInt(options.getOrDefault("expectedWidth","0"));options.remove("expectedWidth");
        int expectedHeight=Integer.parseInt(options.getOrDefault("expectedHeight","0"));options.remove("expectedHeight");
        int warmup=Integer.parseInt(options.getOrDefault("warmupSeconds","0"));options.remove("warmupSeconds");
        Path game=Files.createTempDirectory("phone-native-smoke-");
        var runtime=new ManagedRuntime(game,options,()->RuntimeSmoke.class.getResourceAsStream("/mcandroidphone/runtime/native-guard.jar"));
        System.out.println("Runtime smoke evidence: "+game);
        try {
            runtime.start().get(115,TimeUnit.SECONDS);
            try(var client=runtime.connect()) {
                client.start();long start=System.nanoTime(),end=start+TimeUnit.SECONDS.toNanos(150);int frames=0;
                while(System.nanoTime()<end) {
                    if(runtime.state()==ManagedRuntime.State.FAILED)throw new IllegalStateException(runtime.status());
                    try(var f=client.pollFrame()) {
                        if(f!=null && (expectedWidth==0 || f.width()==expectedWidth) && (expectedHeight==0 || f.height()==expectedHeight)
                                && ++frames>=3 && System.nanoTime()-start>=TimeUnit.SECONDS.toNanos(warmup)) {
                            Path capture=game.resolve("frame.nv12");
                            try(var output=java.nio.channels.FileChannel.open(capture,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                                var pixels=f.pixels();while(pixels.hasRemaining())output.write(pixels);
                            }
                            System.out.println("RUNTIME_FRAME_OK: "+f.width()+"x"+f.height()+" frames="+frames+" session="+runtime.sessionDirectory()+" diagnostic="+capture);return;
                        }
                    }
                    Thread.sleep(10);
                }
                throw new IllegalStateException("No complete native frames: "+client.status());
            }
        } finally {runtime.close();runtime.awaitStopped(Duration.ofSeconds(20));if(runtime.state()!=ManagedRuntime.State.STOPPED)throw new IllegalStateException("Runtime did not stop cleanly: "+runtime.status());}
    }
}
