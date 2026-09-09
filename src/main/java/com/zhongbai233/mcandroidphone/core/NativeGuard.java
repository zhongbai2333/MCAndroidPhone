package com.zhongbai233.mcandroidphone.core;

import java.lang.foreign.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static java.lang.foreign.ValueLayout.*;

/** Private JVM guardian. Its own PID reserves the POSIX group until the final group kill.
 * Native stdin/stdout are inherited directly, with no Java video forwarding or pixel copies.
 * The real parent ProcessHandle is monitored independently of potentially blocked media pipes.
 */
public final class NativeGuard {
    private static final AtomicBoolean stopping=new AtomicBoolean();
    private static boolean contained,windows;
    private static MemorySegment job=MemorySegment.NULL;
    private static SymbolLookup symbols;
    private static Path record;
    private static long childPid;
    private static Object call(String name,FunctionDescriptor signature,Object...args)throws Throwable {
        return Linker.nativeLinker().downcallHandle(symbols.find(name).orElseThrow(),signature).invokeWithArguments(args);
    }
    private static void contain()throws Throwable {
        windows=System.getProperty("os.name").startsWith("Windows");
        if(windows) {
            symbols=SymbolLookup.libraryLookup("kernel32",Arena.global());
            job=(MemorySegment)call("CreateJobObjectW",FunctionDescriptor.of(ADDRESS,ADDRESS,ADDRESS),MemorySegment.NULL,MemorySegment.NULL);
            if(job.address()==0)throw new IllegalStateException("CreateJobObjectW failed");
            try(var arena=Arena.ofConfined()) {
                var limits=arena.allocate(144,8);limits.set(JAVA_INT,16,0x2000);
                int ok=(int)call("SetInformationJobObject",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,JAVA_INT),job,9,limits,144);
                if(ok==0)throw new IllegalStateException("SetInformationJobObject failed");
                // Only this dedicated guardian enters the job, before any child can exist.
                var self=(MemorySegment)call("GetCurrentProcess",FunctionDescriptor.of(ADDRESS));
                ok=(int)call("AssignProcessToJobObject",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS),job,self);
                if(ok==0)throw new IllegalStateException("Guardian job assignment failed");
            }
        }else {
            symbols=Linker.nativeLinker().defaultLookup();
            int id=(int)call("setsid",FunctionDescriptor.of(JAVA_INT));
            if(id!=ProcessHandle.current().pid())throw new IllegalStateException("Guardian could not reserve its process group");
        }
        contained=true;
    }
    private static synchronized void save(String state,Integer exit)throws Exception {
        if(record==null)return;
        var data=new LinkedHashMap<String,Object>();data.put("wrapperPid",ProcessHandle.current().pid());
        data.put("childPid",childPid);data.put("state",state);data.put("exitCode",exit);
        Path temporary=record.resolveSibling(record.getFileName()+".tmp");
        Files.writeString(temporary,Json.write(data));Files.move(temporary,record,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static synchronized void stopGroup() {
        if(!stopping.compareAndSet(false,true))return;
        try {
            if(!contained)return;
            if(windows)call("CloseHandle",FunctionDescriptor.of(JAVA_INT,ADDRESS),job);
            else call("kill",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_INT),-Math.toIntExact(ProcessHandle.current().pid()),9);
        }catch(Throwable e){e.printStackTrace(System.err);Runtime.getRuntime().halt(70);}
    }
    public static void main(String[] args)throws Exception {
        if(args.length<4||!args[2].equals("--"))throw new IllegalArgumentException("Expected ownerPid record -- executable args");
        var owner=ProcessHandle.current().parent().orElseThrow();
        if(owner.pid()!=Long.parseLong(args[0])||!owner.isAlive())throw new IllegalStateException("Guardian owner mismatch");
        record=Path.of(args[1]).toAbsolutePath();
        try {
            contain();
            Runtime.getRuntime().addShutdownHook(new Thread(NativeGuard::stopGroup,"native-group-cleanup"));
            // Parent registers this guardian before releasing G; closed gates never launch a native child.
            if(System.in.read()!='G')return;
            Thread.ofPlatform().daemon().name("native-owner-watch").start(()->{
                try{while(owner.isAlive())Thread.sleep(100);}catch(InterruptedException ignored){}
                stopGroup();
            });
            Process child;
            synchronized(NativeGuard.class) {
                if(stopping.get()||!owner.isAlive())return;
                child=new ProcessBuilder(Arrays.asList(args).subList(3,args.length)).inheritIO().start();
                childPid=child.pid();save("running",null);
            }
            int exit=child.waitFor();save("exited",exit);
        }catch(Throwable error) {
            error.printStackTrace(System.err);
            try{save("failed",1);}catch(Exception ignored){}
        }finally{stopGroup();}
    }
}
