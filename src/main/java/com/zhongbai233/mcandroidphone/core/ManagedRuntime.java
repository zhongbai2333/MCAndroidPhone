package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Asynchronous owner of one bundled runtime supervisor. No Minecraft or shell dependency. */
public final class ManagedRuntime implements AutoCloseable {
    public enum State { NEW, STARTING, READY, FAILED, STOPPING, STOPPED }
    private final Path gameDirectory;
    private final Map<String,String> overrides;
    private final Supplier<InputStream> bundle;
    private final CompletableFuture<Path> ready=new CompletableFuture<>();
    private volatile State state=State.NEW;
    private volatile String message="尚未启动";
    private volatile Path session;
    private volatile Process process;
    private volatile boolean stop;
    private Thread worker;
    private final Thread hook;

    public ManagedRuntime(Path gameDirectory,Map<String,String> overrides,Supplier<InputStream> bundle) {
        this.gameDirectory=gameDirectory.toAbsolutePath().normalize();
        this.overrides=Map.copyOf(overrides);this.bundle=bundle;
        hook=new Thread(()->{close();awaitStopped(Duration.ofSeconds(8));},"androidphone-runtime-shutdown");
    }
    public synchronized CompletableFuture<Path> start() {
        if(state!=State.NEW)return ready;
        if(stop){ready.completeExceptionally(new CancellationException("Runtime closed"));return ready;}
        state=State.STARTING;message="正在启动安卓运行环境…";
        Runtime.getRuntime().addShutdownHook(hook);
        worker=Thread.ofPlatform().daemon().name("androidphone-runtime").start(this::run);
        return ready;
    }
    public State state(){return state;}
    public String status(){return message;}
    public Path sessionDirectory(){return session;}
    private void run() {
        try {
            Properties settings=settings();
            Path root=Path.of(settings.getProperty("root")).toAbsolutePath().normalize();
            Path python=findPython(root,settings.getProperty("python",""));
            if(stop)throw new CancellationException();
            session=Files.createDirectories(gameDirectory.resolve("mcandroidphone/sessions").resolve(UUID.randomUUID().toString()));
            Path archive=session.resolve("bridge.zip");
            try(InputStream input=bundle.get()) {
                if(input==null)throw new IOException("JAR 缺少内置 bridge.zip");
                byte[] bytes=input.readNBytes(8*1024*1024+1);
                if(bytes.length>8*1024*1024)throw new IOException("Bundled bridge exceeds limit");
                Files.write(archive,bytes,StandardOpenOption.CREATE_NEW);
            }
            Path request=session.resolve("runtime.properties");
            var lines=new ArrayList<String>();
            for(String key:settings.stringPropertyNames()) {
                String value=settings.getProperty(key);
                if(value.indexOf('\n')>=0||value.indexOf('\r')>=0||value.indexOf(0)>=0)throw new IOException("Invalid setting: "+key);
                lines.add(key+"="+value);
            }
            Files.write(request,lines,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            var builder=new ProcessBuilder(python.toString(),"-u","-m","mcandroid_bridge.managed",
                "--config",request.toString(),"--session",session.toString());
            builder.directory(session.toFile()).redirectErrorStream(true).redirectOutput(session.resolve("runtime.log").toFile());
            var env=builder.environment();env.put("PYTHONPATH",archive.toString());env.put("PYTHONUTF8","1");
            env.put("PYTHONNOUSERSITE","1");env.put("PYTHONUNBUFFERED","1");env.remove("PYTHONHOME");
            synchronized(this) {
                if(stop)throw new CancellationException();
                process=builder.start();
            }
            Path config=session.resolve("bridge/bridge.properties");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);
            while(!stop) {
                if(!process.isAlive())throw new IOException("运行环境已退出 ("+process.exitValue()+")。"+errorTail());
                if(Files.size(session.resolve("runtime.log"))>64L*1024*1024)throw new IOException("Runtime log size limit exceeded");
                if(state==State.STARTING && Files.isRegularFile(config)) {
                    // This is this attempt's unique discovery file. No stale file or probe connection is reused.
                    state=State.READY;message="安卓运行环境已启动";ready.complete(config);
                }
                if(state==State.STARTING && System.nanoTime()>deadline)throw new IOException("安卓运行环境启动超时。"+errorTail());
                Thread.sleep(100);
            }
        } catch(Exception error) {
            if(!stop) {state=State.FAILED;message=error.getMessage()==null?error.toString():error.getMessage();}
            ready.completeExceptionally(error);
        } finally {
            closePipe();
            Process child=process;
            if(child!=null)try {
                if(!child.waitFor(7,TimeUnit.SECONDS))child.destroyForcibly().waitFor(2,TimeUnit.SECONDS);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            if(state!=State.FAILED){state=State.STOPPED;message="安卓运行环境已关闭";}
            ready.completeExceptionally(new CancellationException("Runtime stopped"));
            try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored){}
        }
    }
    private Properties settings() throws IOException {
        Path file=gameDirectory.resolve("config/mcandroidphone-runtime.properties");
        Files.createDirectories(file.getParent());
        if(!Files.exists(file)) {
            String template="""
                # MC Android Phone: UTF-8; use forward slashes in paths.
                # Native runtimes and Android media remain external. JAR includes the bridge.
                backend=qemu
                gpu=virtio
                display=auto
                # guestArch defaults to native host: amd64 or arm64
                # ARM64 requires its own Android image/virt drivers; x86 images need guestArch=amd64 and TCG.
                # firmware=/path/to/edk2-aarch64-code.fd
                # kernelAppend=console=ttyAMA0 ... (image-specific ARM64 boot arguments)
                input=auto
                # root defaults to <game>/mcandroidphone/runtime
                # root=D:/UserFile/Documents/GitHub/MCAndroidPhone/.runtime
                # python=C:/Python313/python.exe
                # qemu=D:/qemu/bin/qemu-system-x86_64.exe
                # iso=D:/Android/android-x86_64-9.0-r2.iso
                # kernel=D:/Android/android-x86_64-9.0-r2/kernel
                # initrd=D:/Android/android-x86_64-9.0-r2/initrd.img
                # ffmpeg=E:/Program Files/ffmpeg/bin/ffmpeg.exe
                # angle=D:/angle/bin
                width=1080
                height=1920
                density=480
                memory=4096
                cpus=2
                accel=auto
                """;
            try {Files.writeString(file,template,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
            catch(FileAlreadyExistsException ignored){}
        }
        var p=new Properties();
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}
        overrides.forEach(p::setProperty);
        Path root=Path.of(p.getProperty("root",gameDirectory.resolve("mcandroidphone/runtime").toString()));
        if(!root.isAbsolute())root=gameDirectory.resolve(root);
        root=root.normalize();p.setProperty("root",root.toString());
        for(String name:List.of("python","qemu","iso","disk","kernel","initrd","ffmpeg","angle","firmware")) {
            String value=p.getProperty(name,"");
            if(!value.isBlank()) {var path=Path.of(value);p.setProperty(name,(path.isAbsolute()?path:root.resolve(path)).normalize().toString());}
        }
        return p;
    }
    private static Path findPython(Path root,String explicit) throws IOException {
        if(!explicit.isBlank()) {
            var p=Path.of(explicit);if(Files.isRegularFile(p))return p;
            throw new IOException("Python 不存在："+p);
        }
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        var candidates=new ArrayList<Path>();
        String[] names=windows?new String[]{"python.exe"}:new String[]{"python3","python"};
        for(String name:names) {
            candidates.add(root.resolve(windows?"python/"+name:"python/bin/"+name));
            candidates.add(root.resolve(name));
        }
        if(root.getParent()!=null)candidates.add(root.getParent().resolve(windows?".venv/Scripts/python.exe":".venv/bin/python3"));
        for(String folder:System.getenv().getOrDefault("PATH","").split(java.io.File.pathSeparator))
            if(!folder.isBlank())for(String name:names)candidates.add(Path.of(folder).resolve(name));
        if(!windows)for(String folder:List.of("/opt/homebrew/bin","/usr/local/bin","/usr/bin"))
            candidates.add(Path.of(folder,"python3"));
        for(Path candidate:candidates)if(Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate)) && !candidate.toString().contains("WindowsApps"))return candidate.toAbsolutePath();
        throw new IOException("未找到 Python 3.10+；请在 config/mcandroidphone-runtime.properties 设置 python 路径");
    }
    private String errorTail() {
        if(session==null)return "";
        try(var channel=Files.newByteChannel(session.resolve("runtime.log"))) {
            channel.position(Math.max(0,channel.size()-3072));var bytes=java.nio.ByteBuffer.allocate(3072);channel.read(bytes);
            return new String(bytes.array(),0,bytes.position(),StandardCharsets.UTF_8).strip();
        }catch(IOException ignored){return "日志："+session;}
    }
    private void closePipe() {
        Process child=process;
        if(child!=null)try{child.getOutputStream().close();}catch(IOException ignored){}
    }
    @Override public synchronized void close() {
        stop=true;
        if(state!=State.FAILED && state!=State.STOPPED){state=State.STOPPING;message="正在关闭安卓运行环境…";}
        closePipe();ready.completeExceptionally(new CancellationException("Runtime closed"));
        if(worker==null){state=State.STOPPED;message="安卓运行环境已关闭";}
    }
    public void awaitStopped(Duration timeout) {
        Thread active=worker;if(active==null||active==Thread.currentThread())return;
        try{active.join(timeout.toMillis());}catch(InterruptedException error){Thread.currentThread().interrupt();}
    }
}
