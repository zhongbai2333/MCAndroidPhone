package com.zhongbai233.mcandroidphone.core;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Real bundled Python children; no simulator, Minecraft, network download or external launcher. */
public final class ManagedRuntimeSelfTest {
    private static final String RESOURCE="/mcandroidphone/runtime/bridge.zip";
    private static Path python,root;
    private static ManagedRuntime create(Path game,Map<String,String> extra) {
        var options=new HashMap<String,String>();
        options.put("backend","pattern");options.put("python",python.toString());options.put("root",root.toString());
        options.putAll(extra);
        return new ManagedRuntime(game,options,()->ManagedRuntimeSelfTest.class.getResourceAsStream(RESOURCE));
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void frame(Path config) throws Exception {
        try(var client=new BridgeClient(config)) {
            client.start();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<end) {
                try(var f=client.pollFrame()) {if(f!=null){require(f.width()==1080 && f.height()==1920,"Wrong native frame");return;}}
                Thread.sleep(20);
            }
            throw new AssertionError("Managed bridge produced no frame: "+client.status());
        }
    }
    private static Set<Long> pids(Path session) throws Exception {
        String text=Files.readString(session.resolve("session.json"));
        var found=new HashSet<Long>();var matcher=Pattern.compile("\"(?:pid|childPid|supervisorPid)\"\\s*:\\s*(\\d+)").matcher(text);
        while(matcher.find())found.add(Long.parseLong(matcher.group(1)));
        require(!found.isEmpty(),"Missing process manifest");return found;
    }
    private static void stopped(Set<Long> pids) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<end) {
            if(pids.stream().noneMatch(id->ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false)))return;
            Thread.sleep(50);
        }
        throw new AssertionError("Orphaned owned processes: "+pids);
    }
    public static void main(String[] args) throws Exception {
        python=Path.of(args[0]).toAbsolutePath();root=Path.of(args[1]).toAbsolutePath();
        if(args.length>2) {
            Path game=Path.of(args[2]);var runtime=create(game,Map.of());
            frame(runtime.start().get(30,TimeUnit.SECONDS));
            Files.writeString(game.resolve("child-ready.txt"),runtime.sessionDirectory().toString());
            Thread.sleep(120_000);runtime.close();return;
        }
        Path game=Files.createTempDirectory("phone-runtime-selftest-");
        System.out.println("Runtime self-test evidence: "+game);
        var runtime=create(game.resolve("normal"),Map.of());
        Set<Long> owned;
        try {
            var first=runtime.start();require(first==runtime.start(),"Duplicate start must reuse one attempt");
            frame(first.get(30,TimeUnit.SECONDS));owned=pids(runtime.sessionDirectory());
        }finally{runtime.close();runtime.awaitStopped(Duration.ofSeconds(10));}
        require(runtime.state()==ManagedRuntime.State.STOPPED,"Normal close state");stopped(owned);
        try(var entries=Files.list(runtime.sessionDirectory().resolve("bridge"))) {
            require(entries.noneMatch(path->path.toString().endsWith(".nv12")),"Volatile mmap leaked after close");
        }
        System.out.println("RUNTIME_NORMAL_CLOSE_OK: frame received; all owned children stopped");

        var cancelled=create(game.resolve("cancelled"),Map.of());cancelled.close();
        require(cancelled.start().isCompletedExceptionally(),"Closed runtime must not start");
        require(cancelled.sessionDirectory()==null,"Cancelled start created a process session");
        var immediate=create(game.resolve("immediate"),Map.of());immediate.start();immediate.close();immediate.awaitStopped(Duration.ofSeconds(10));
        require(immediate.state()==ManagedRuntime.State.STOPPED,"Cancellation during start must finish");
        System.out.println("RUNTIME_CANCEL_OK");

        var failed=create(game.resolve("failure"),Map.of("backend","qemu","qemu",game.resolve("missing-qemu.exe").toString()));
        try {
            try{failed.start().get(30,TimeUnit.SECONDS);throw new AssertionError("Missing QEMU accepted");}
            catch(ExecutionException expected){require(failed.state()==ManagedRuntime.State.FAILED,"Failure status not published");}
        }finally{failed.close();failed.awaitStopped(Duration.ofSeconds(10));}
        stopped(pids(failed.sessionDirectory()));System.out.println("RUNTIME_FAILURE_CLEANUP_OK");

        Path childGame=Files.createDirectories(game.resolve("parent-crash"));
        Path java=Path.of(System.getProperty("java.home"),System.getProperty("os.name").startsWith("Windows")?"bin/java.exe":"bin/java");
        var child=new ProcessBuilder(java.toString(),"--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("java.class.path"),
            ManagedRuntimeSelfTest.class.getName(),python.toString(),root.toString(),childGame.toString())
            .redirectErrorStream(true).redirectOutput(childGame.resolve("jvm.log").toFile()).start();
        Set<Long> crashOwned=Set.of();
        try {
            Path marker=childGame.resolve("child-ready.txt");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(!Files.exists(marker)){require(child.isAlive(),"Child JVM failed");require(System.nanoTime()<end,"Child startup timeout");Thread.sleep(50);}
            crashOwned=pids(Path.of(Files.readString(marker)));
            // Only terminate the JVM created above; no shutdown hook runs on this forced exit.
            child.destroyForcibly();require(child.waitFor(10,TimeUnit.SECONDS),"Child JVM did not exit");
            stopped(crashOwned);
        }finally{if(child.isAlive())child.destroyForcibly().waitFor(10,TimeUnit.SECONDS);}
        System.out.println("RUNTIME_PARENT_CRASH_OK: EOF lease cleaned up supervisor, bridge and descendants");
        System.out.println("MANAGED_RUNTIME_SELF_TEST_OK");
    }
}
