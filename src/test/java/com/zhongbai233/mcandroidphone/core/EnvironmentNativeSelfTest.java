package com.zhongbai233.mcandroidphone.core;

import com.zhongbai233.mcandroidphone.environment.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Java-to-C++ wire compatibility, stale-state rejection and native receiver lifecycle. */
public final class EnvironmentNativeSelfTest {
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("mcphone-native-environment-");Path state=dir.resolve("environment.bin");
        Process child=new ProcessBuilder(args[0],"--stdio",state.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var inspectors=new java.util.ArrayList<Process>();
        var read=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var in=new DataInputStream(child.getInputStream());
            int magic=read.submit(in::readInt).get(3,TimeUnit.SECONDS);
            if(magic!=EnvironmentPacket.MAGIC||in.readInt()!=1)throw new AssertionError("Native handshake");
            // macOS may spend >500 ms verifying a newly compiled executable on first launch.
            // Start the consumer before publishing; the production stale deadline stays strict.
            var inspect=new ProcessBuilder(args[1],state.toString(),"--wait").redirectErrorStream(true).start();
            inspectors.add(inspect);
            var inspection=new BufferedReader(new InputStreamReader(inspect.getInputStream()));
            if(!"READY".equals(read.submit(inspection::readLine).get(5,TimeUnit.SECONDS)))throw new AssertionError("Inspector startup");
            var sample=new EnvironmentSampler().sample(1_000_000_000L,"minecraft:overworld",120,163,-240,0,0,0,0,0,15,true);
            child.getOutputStream().write(sample.encode());child.getOutputStream().flush();
            if(read.submit(in::readLong).get(3,TimeUnit.SECONDS)!=sample.sequence)throw new AssertionError("Native ACK");
            inspect.getOutputStream().write(1);inspect.getOutputStream().flush();
            String result=read.submit(inspection::readLine).get(3,TimeUnit.SECONDS);
            if(!inspect.waitFor(3,TimeUnit.SECONDS)||inspect.exitValue()!=0||!result.equals("1 minecraft:overworld 120 9.80665 1"))throw new AssertionError(result);
            Thread.sleep(600);
            var stale=new ProcessBuilder(args[1],state.toString()).start();
            inspectors.add(stale);
            if(!stale.waitFor(3,TimeUnit.SECONDS)||stale.exitValue()!=3)throw new AssertionError("Native stale state accepted");
            child.getOutputStream().write(new byte[]{0x7f,0x7f,0x7f,0x7f});child.getOutputStream().flush();
            if(!child.waitFor(3,TimeUnit.SECONDS)||Files.exists(state))throw new AssertionError("Malformed packet/cleanup");
            System.out.println("ENVIRONMENT_JAVA_CPP_WIRE_AND_STALE_STATE_OK");
        }finally{for(var p:inspectors){if(p.isAlive())p.destroyForcibly();p.waitFor(3,TimeUnit.SECONDS);}child.destroyForcibly();child.waitFor(3,TimeUnit.SECONDS);read.shutdownNow();Files.deleteIfExists(state);Files.deleteIfExists(dir);}
    }
}
