package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A native process behind a private Java guardian; only owned processes are ever signalled. */
final class OwnedProcess implements AutoCloseable {
    final Process guardian;
    final Path record,log;
    final String name;
    final List<String> nativeCommand;
    volatile long childPid;
    OwnedProcess(Path helper,Path session,String name,List<String> nativeCommand,Map<String,String> environment,boolean pipe)throws IOException {
        this.name=name;this.nativeCommand=List.copyOf(nativeCommand);record=session.resolve(name+".process.json");log=session.resolve(name+".log");
        Path java=Path.of(System.getProperty("java.home"),System.getProperty("os.name").startsWith("Windows")?"bin/java.exe":"bin/java");
        var command=new ArrayList<String>(List.of(java.toString(),"-Xms8m","-Xmx32m","-XX:+UseSerialGC","--enable-native-access=ALL-UNNAMED",
            "-cp",helper.toString(),NativeGuard.class.getName(),Long.toString(ProcessHandle.current().pid()),record.toString(),"--"));
        command.addAll(nativeCommand);
        var builder=new ProcessBuilder(command).directory(session.toFile()).redirectError(log.toFile());
        builder.environment().putAll(environment);
        // Do not inherit Java injection options into the private helper JVM.
        for(String key:List.of("JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS"))builder.environment().remove(key);
        if(!pipe)builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        guardian=builder.start();
    }
    void release()throws IOException {guardian.getOutputStream().write('G');guardian.getOutputStream().flush();}
    void awaitStarted()throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            if(Files.isRegularFile(record)) {
                var data=Json.object(Files.readString(record));
                if(((Number)data.get("wrapperPid")).longValue()!=guardian.pid())throw new IOException("Wrong guardian identity");
                childPid=((Number)data.get("childPid")).longValue();
                if(childPid<=0||childPid==guardian.pid())throw new IOException("Native launch failed: "+tail());
                return;
            }
            if(!guardian.isAlive())throw new IOException("Guardian exited before launch: "+tail());
            Thread.sleep(20);
        }
        throw new IOException("Native launch timed out: "+name);
    }
    boolean alive(){return guardian.isAlive();}
    InputStream output(){return guardian.getInputStream();}
    OutputStream input(){return guardian.getOutputStream();}
    String tail(){try(var in=Files.newInputStream(log)){long size=Files.size(log);in.skipNBytes(Math.max(0,size-4096));return new String(in.readNBytes(4096),java.nio.charset.StandardCharsets.UTF_8);}catch(IOException e){return log.toString();}}
    Map<String,Object> state() {
        var result=new LinkedHashMap<String,Object>();result.put("name",name);result.put("pid",guardian.pid());result.put("childPid",childPid);
        result.put("alive",alive());result.put("command",nativeCommand);
        try{if(Files.isRegularFile(record))result.put("native",Json.object(Files.readString(record)));}catch(IOException ignored){}
        return result;
    }
    @Override public void close() {
        // Terminate the guardian, whose hook/job closes the entire reserved native group.
        // Closing a blocked Java ProcessPipeOutputStream first could deadlock the game shutdown.
        if(guardian.isAlive())guardian.destroy();
        try {
            if(!guardian.waitFor(8,TimeUnit.SECONDS))throw new IllegalStateException("Guardian did not clean up "+name);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted native cleanup",e);}
        try{guardian.getOutputStream().close();}catch(IOException ignored){}
        try{guardian.getInputStream().close();}catch(IOException ignored){}
    }
}
