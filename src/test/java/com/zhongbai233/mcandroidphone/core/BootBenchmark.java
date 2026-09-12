package com.zhongbai233.mcandroidphone.core;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded boot timing using production transport; GPU leases are drained, not rendered. */
public final class BootBenchmark {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("BootBenchmark <runtime.properties> <new-evidence-directory>");
        Path evidence=Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        if(Files.exists(evidence.resolve("result.json")))throw new IllegalArgumentException("Evidence directory already contains a result");
        var p=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]))){p.load(reader);}
        var options=new HashMap<String,String>();p.forEach((k,v)->options.put((String)k,(String)v));
        boolean initialize=Boolean.parseBoolean(options.remove("benchmarkInitialize"));
        options.put("storage",initialize?"persistent":"snapshot");
        if(initialize)options.put("deviceId",UUID.randomUUID().toString());
        var result=new LinkedHashMap<String,Object>();
        result.put("gpuRendered",false);result.put("storage",options.get("storage"));
        if(initialize)result.put("newDeviceId",options.get("deviceId"));
        long start=System.nanoTime(),deadline=start+TimeUnit.SECONDS.toNanos(200),nextRead=0;
        var runtime=new ManagedRuntime(evidence.resolve("game"),options,()->BootBenchmark.class.getResourceAsStream("/mcandroidphone/runtime/native-guard.jar"));
        PhoneConnection connection=null;boolean boot=false;long frames=0;
        try {
            runtime.start().get(60,TimeUnit.SECONDS);
            result.put("runtimeReadyMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            result.put("session",runtime.sessionDirectory().toString());
            connection=runtime.connect();connection.start();
            while(System.nanoTime()<deadline) {
                if(runtime.state()==ManagedRuntime.State.FAILED)throw new IllegalStateException(runtime.status());
                boolean frame=false;
                try(var gpu=connection.pollGpuFrame()){if(gpu!=null)frame=true;}
                try(var cpu=connection.pollFrame()){if(cpu!=null)frame=true;}
                if(frame){frames++;result.putIfAbsent("firstTransportFrameMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
                if(System.nanoTime()>=nextRead){
                    nextRead=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);
                    Path serial=runtime.sessionDirectory().resolve("qemu-serial.log");
                    if(Files.exists(serial)&&Files.size(serial)>64L*1024*1024)throw new IllegalStateException("Boot log exceeds 64 MiB");
                    String log=Files.exists(serial)?new String(Files.readAllBytes(serial),java.nio.charset.StandardCharsets.UTF_8):"";
                    if(log.contains("Linux version"))result.putIfAbsent("kernelObservedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
                    if((log.contains("processing action (sys.boot_completed=1)")||log.contains("MCANDROIDPHONE_BOOT_COMPLETED"))){
                        boot=true;result.putIfAbsent("bootCompletedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
                    }
                    Files.writeString(evidence.resolve("progress.json"),Json.write(result));
                }
                if(frames>=10&&(boot||Boolean.parseBoolean(options.getOrDefault("benchmarkNoBootLog","false"))))break;
                Thread.sleep(5);
            }
            result.put("frames",frames);result.put("bootCompleted",boot);
            if((!boot&&!Boolean.parseBoolean(options.getOrDefault("benchmarkNoBootLog","false")))||frames<10)throw new IllegalStateException("Boot/frame deadline exceeded");
        } finally {
            if(connection!=null)connection.close();runtime.close();runtime.awaitStopped(Duration.ofSeconds(25));
            result.put("finalState",runtime.state().name());
            if(runtime.sessionDirectory()!=null&&Files.exists(runtime.sessionDirectory().resolve("session.json")))
                result.put("shutdownOutcome",Json.object(Files.readString(runtime.sessionDirectory().resolve("session.json"))).get("shutdownOutcome"));
            Files.writeString(evidence.resolve("result.json"),Json.write(result));
            System.out.println(Json.write(result));
            if(initialize&&!"guest-confirmed".equals(result.get("shutdownOutcome")))throw new IllegalStateException("New test device did not shut down cleanly");
            if(runtime.state()!=ManagedRuntime.State.STOPPED)throw new IllegalStateException("Runtime cleanup failed: "+runtime.status());
        }
    }
}