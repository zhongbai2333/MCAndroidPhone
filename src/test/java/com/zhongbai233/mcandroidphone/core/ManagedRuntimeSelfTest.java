package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Java-only lifecycle regression, including real isolated native-process trees. */
public final class ManagedRuntimeSelfTest {
    private static final String RESOURCE="/mcandroidphone/runtime/native-guard.jar";
    private static Path root;
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static String java(){return Path.of(System.getProperty("java.home"),System.getProperty("os.name").startsWith("Windows")?"bin/java.exe":"bin/java").toString();}
    private static List<String> command(String...args){var c=new ArrayList<>(List.of(java(),"--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("java.class.path"),ManagedRuntimeSelfTest.class.getName()));c.addAll(List.of(args));return c;}
    private static ManagedRuntime create(Path game,Map<String,String> extra){var options=new HashMap<String,String>(Map.of("backend","pattern","root",root.toString(),"width","320","height","480"));options.putAll(extra);return new ManagedRuntime(game,options,()->ManagedRuntimeSelfTest.class.getResourceAsStream(RESOURCE));}
    private static Frame frame(PhoneConnection client)throws Exception {long end=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<end){Frame f=client.pollFrame();if(f!=null)return f;Thread.sleep(10);}throw new AssertionError("No frame: "+client.status());}
    private static void marker(Path path,String value)throws IOException {Path temporary=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temporary,value);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE);}
    private static void waitFile(Path path,Process child)throws Exception {long end=System.nanoTime()+15_000_000_000L;while(!Files.isRegularFile(path)){require(child.isAlive(),"Fixture exited: "+path);require(System.nanoTime()<end,"Fixture timeout: "+path);Thread.sleep(20);}}
    private static void stopped(Collection<Long> ids)throws Exception {long end=System.nanoTime()+12_000_000_000L;while(System.nanoTime()<end){if(ids.stream().noneMatch(id->ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false)))return;Thread.sleep(50);}throw new AssertionError("Orphaned owned processes: "+ids);}
    private static OwnedProcess tree(ManagedRuntime runtime,Path directory)throws Exception {
        return runtime.spawn("fixture",command("tree",directory.toString()),Map.of(),true);
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("sleep")){Thread.sleep(120_000);return;}
        if(args.length>0&&args[0].equals("tree")) {
            Path folder=Path.of(args[1]);var grandchild=new ProcessBuilder(command("sleep")).inheritIO().start();
            marker(folder.resolve("grandchild.pid"),Long.toString(grandchild.pid()));Thread.sleep(120_000);return;
        }
        if(args.length>0&&args[0].equals("owner")) {
            root=Path.of(args[1]);Path game=Path.of(args[2]);var runtime=create(game,Map.of());runtime.start().get(15,TimeUnit.SECONDS);
            var child=tree(runtime,game);waitFile(game.resolve("grandchild.pid"),child.guardian);
            marker(game.resolve("owned.json"),Json.write(List.of(child.guardian.pid(),child.childPid,Long.parseLong(Files.readString(game.resolve("grandchild.pid"))))));
            // The guardian's watchdog must notice a killed owner even while video stdin remains open.
            Thread.sleep(120_000);return;
        }
        root=Path.of(args[0]).toAbsolutePath();Path game=Files.createTempDirectory("phone-java-runtime-test-");
        System.out.println("Java runtime evidence: "+game);
        // Exercise the real allocator, including hosts whose dynamic range starts at 1024.
        for(boolean vnc:List.of(false,true))for(int attempt=0;attempt<5;attempt++) {
            int port=ManagedRuntime.port(vnc);
            require(port>=(vnc?5900:1)&&port<=65535,"Invalid native listen port: "+port);
            try(var listener=new java.net.ServerSocket(port,1,java.net.InetAddress.getByName("127.0.0.1"));
                var client=new java.net.Socket("127.0.0.1",port);var accepted=listener.accept()) {
                client.getOutputStream().write(71);require(accepted.getInputStream().read()==71,"Native loopback port unreachable");
            }
        }
        System.out.println("NATIVE_IPV4_VNC_QMP_PORTS_OK");
        JavaProtocolSelfTest.run();
        var runtime=create(game.resolve("normal"),Map.of());
        try {
            var first=runtime.start();require(first==runtime.start(),"Duplicate start changed attempt");first.get(15,TimeUnit.SECONDS);
            try(var connection=runtime.connect()) {
                connection.start();long epoch=connection.connectionEpoch();
                try(var held=frame(connection)){require(held.width()==320&&held.height()==480,"Frame dimensions");byte sample=held.pixels().get(0);
                    connection.touch("DOWN",.2,.8);connection.touch("UP",.2,.8);connection.key("APP_SWITCH");connection.text("Java运行时");
                    Thread.sleep(250);require(held.pixels().get(0)==sample,"Leased buffer was overwritten");}
                try(var replacement=runtime.connect()){require(!connection.connected(),"Old view still connected");require(epoch!=replacement.connectionEpoch(),"Reconnect reused epoch");try(var f=frame(replacement)){require(f.pixels().isReadOnly(),"Mutable frame view");}}
            }
            Path fixture=Files.createDirectories(game.resolve("normal-tree"));var child=tree(runtime,fixture);waitFile(fixture.resolve("grandchild.pid"),child.guardian);
            var owned=List.of(child.guardian.pid(),child.childPid,Long.parseLong(Files.readString(fixture.resolve("grandchild.pid"))));
            var blocked=runtime.spawn("blocked-input",command("sleep"),Map.of(),true);
            var write=new CompletableFuture<Void>();Thread.ofPlatform().daemon().start(()->{try{blocked.input().write(new byte[32*1024*1024]);write.complete(null);}catch(IOException e){write.complete(null);}});
            Thread.sleep(100);require(!write.isDone(),"Fixture must block the native stdin pipe");
            runtime.close();runtime.awaitStopped(Duration.ofSeconds(20));require(runtime.state()==ManagedRuntime.State.STOPPED,runtime.status());stopped(owned);
            write.get(3,TimeUnit.SECONDS);stopped(List.of(blocked.guardian.pid(),blocked.childPid));
        }finally{runtime.close();runtime.awaitStopped(Duration.ofSeconds(20));}
        System.out.println("JAVA_RUNTIME_NORMAL_RECONNECT_TREE_CLEANUP_OK");
        var cancelled=create(game.resolve("cancelled"),Map.of());cancelled.close();require(cancelled.start().isCompletedExceptionally(),"Closed runtime started");require(cancelled.sessionDirectory()==null,"Cancelled created session");
        for(int i=0;i<10;i++){var immediate=create(game.resolve("cancel-"+i),Map.of());immediate.start();immediate.close();immediate.awaitStopped(Duration.ofSeconds(5));require(immediate.state()==ManagedRuntime.State.STOPPED,"Cancellation stuck");}
        var failed=create(game.resolve("failure"),Map.of("backend","qemu","display","vnc","bios","true","qemu",game.resolve("missing-qemu").toString()));
        try{failed.start().get(15,TimeUnit.SECONDS);throw new AssertionError("Missing QEMU accepted");}catch(ExecutionException expected){}finally{failed.close();failed.awaitStopped(Duration.ofSeconds(10));}
        require(failed.state()==ManagedRuntime.State.FAILED,"Missing failure state");
        System.out.println("JAVA_RUNTIME_CANCEL_FAILURE_OK");
        Path crash=Files.createDirectories(game.resolve("crash"));var owner=new ProcessBuilder(command("owner",root.toString(),crash.toString())).redirectErrorStream(true).redirectOutput(crash.resolve("owner.log").toFile()).start();
        try {
            waitFile(crash.resolve("owned.json"),owner);var owned=((List<?>)Json.parse(Files.readString(crash.resolve("owned.json")))).stream().map(v->((Number)v).longValue()).toList();
            owner.destroyForcibly();require(owner.waitFor(10,TimeUnit.SECONDS),"Owner still running");stopped(owned);
        }finally{if(owner.isAlive())owner.destroyForcibly().waitFor(10,TimeUnit.SECONDS);}
        System.out.println("JAVA_RUNTIME_OWNER_SIGKILL_CLEANUP_OK");
        System.out.println("MANAGED_RUNTIME_SELF_TEST_OK");
    }
}
