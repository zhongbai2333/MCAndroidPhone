package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One Java device runtime. Native children belong to isolated Java guardians, never to a shell. */
public final class ManagedRuntime implements AutoCloseable {
    public enum State { NEW, STARTING, READY, FAILED, STOPPING, STOPPED }
    private final Path gameDirectory;
    private final Map<String,String> overrides;
    private final Supplier<InputStream> bundle;
    private final CompletableFuture<ManagedRuntime> ready=new CompletableFuture<>();
    private final List<OwnedProcess> processes=new ArrayList<>();
    private volatile State state=State.NEW;
    private volatile String message="尚未启动";
    private volatile Path session;
    private Path helper;
    private volatile LocalDevice device;
    private volatile boolean stop;
    private Thread worker;
    private int ordinal,qmpPort;
    private String vmUuid;
    private OwnedProcess ownedQemu;
    private boolean persistentGuest;
    private boolean shutdownPowerKey;
    private volatile String shutdownOutcome="not-requested";
    private volatile EnvironmentChannel environment;
    private DeviceStorage storage;
    private volatile CameraChannel camera;
    private final Thread hook;

    public ManagedRuntime(Path gameDirectory,Map<String,String> overrides,Supplier<InputStream> bundle) {
        this.gameDirectory=gameDirectory.toAbsolutePath().normalize();this.overrides=Map.copyOf(overrides);this.bundle=bundle;
        hook=new Thread(()->{close();awaitStopped(Duration.ofSeconds(25));},"androidphone-runtime-shutdown");
    }
    public synchronized CompletableFuture<ManagedRuntime> start() {
        if(state!=State.NEW)return ready;
        state=State.STARTING;message="正在启动安卓运行环境…";
        Runtime.getRuntime().addShutdownHook(hook);
        worker=Thread.ofPlatform().daemon().name("androidphone-runtime").start(this::run);return ready;
    }
    public PhoneConnection connect(){if(state!=State.READY)throw new IllegalStateException(status());return device.connect();}
    public State state(){return state;}
    public String status(){return message;}
    public Path sessionDirectory(){return session;}
    public void publishEnvironment(com.zhongbai233.mcandroidphone.environment.EnvironmentPacket packet){var channel=environment;if(channel!=null)channel.publish(packet);}
    public void invalidateEnvironment(){var channel=environment;if(channel!=null)channel.invalidate();}
    public String environmentStatus(){var channel=environment;return channel==null?"environment channel disabled":channel.status();}
    public boolean cameraDemanded(){var channel=camera;return channel!=null&&channel.demanded();}
    public record CameraRequest(long generation,int facing){}
    public CameraRequest cameraRequest(){var channel=camera;return channel==null?null:channel.request();}
    public void publishCamera(CameraRequest request,long sequence,int width,int height,byte[] jpeg){var channel=camera;if(channel!=null)channel.publish(request,sequence,width,height,jpeg);}
    public void publishCamera(long sequence,int width,int height,byte[] jpeg){var channel=camera;if(channel!=null)channel.publish(sequence,width,height,jpeg);}
    public void invalidateCamera(){var channel=camera;if(channel!=null)channel.invalidate();}
    public String cameraStatus(){var channel=camera;return channel==null?"camera channel disabled":channel.status();}
    private void cancelled(){if(stop)throw new CancellationException("Runtime closed");}
    OwnedProcess spawn(String name,List<String> command,Map<String,String> environment,boolean pipe)throws Exception {
        OwnedProcess child;
        synchronized(this){cancelled();child=new OwnedProcess(helper,session,name+"-"+(++ordinal),command,environment,pipe);processes.add(child);save();}
        child.release();child.awaitStarted();synchronized(this){save();}cancelled();return child;
    }
    private synchronized void save()throws IOException {
        if(session==null)return;
        var info=new LinkedHashMap<String,Object>();info.put("implementation","java");info.put("ownerPid",ProcessHandle.current().pid());
        info.put("state",state.toString().toLowerCase(Locale.ROOT));info.put("message",message);
        info.put("shutdownOutcome",shutdownOutcome);
        info.put("children",processes.stream().map(OwnedProcess::state).toList());
        Path temp=session.resolve("session.json.tmp");Files.writeString(temp,Json.write(info));
        Files.move(temp,session.resolve("session.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static int port(boolean vnc)throws IOException {
        // Windows can allocate ephemeral ports below 5900, often reusing the same one.
        // VNC needs a nonnegative display number; probe its valid range explicitly.
        InetAddress loopback=InetAddress.getByName("127.0.0.1");
        BindException last=null;
        for(int i=0;i<100;i++) {
            int candidate=vnc?ThreadLocalRandom.current().nextInt(5900,65536):0;
            try(var socket=new ServerSocket(candidate,1,loopback)){return socket.getLocalPort();}
            catch(BindException unavailable){last=unavailable;}
        }
        throw new IOException("No free IPv4 loopback port for "+(vnc?"VNC":"QMP"),last);
    }
    private void run() {
        try {
            RuntimeConfig config=new RuntimeConfig(settings());cancelled();
            String shutdownMethod=config.get("shutdownMethod","qmp");
            if(!Set.of("qmp","power-key").contains(shutdownMethod))throw new IOException("Invalid shutdownMethod");
            shutdownPowerKey=shutdownMethod.equals("power-key");
            session=Files.createDirectories(gameDirectory.resolve("mcandroidphone/sessions").resolve(UUID.randomUUID().toString()));
            try(var writer=Files.newBufferedWriter(session.resolve("runtime.properties"))){config.values.store(writer,"Java runtime session");}
            helper=session.resolve("native-guard.jar");
            try(InputStream input=bundle.get()){if(input==null)throw new IOException("JAR 缺少 native-guard.jar");byte[] bytes=input.readNBytes(1024*1024+1);
                if(bytes.length>1024*1024)throw new IOException("Native guardian exceeds limit");Files.write(helper,bytes,StandardOpenOption.CREATE_NEW);}
            device=new LocalDevice();
            String backend=config.get("backend","qemu");
            if(backend.equals("pattern"))device.backend=new PatternDevice(device,config.integer("width",1080,2,4096),config.integer("height",1920,2,4096));
            else if(backend.equals("qemu")) {
                message="正在准备安卓设备存储…";
                storage=DeviceStorage.prepare(config,gameDirectory,this::cancelled);
                persistentGuest=config.persistentDisks;
                try(var writer=Files.newBufferedWriter(session.resolve("runtime.properties"))){config.values.store(writer,"Java runtime session");}
                config.display();config.gpu();
                var nativeEnvironment=new HashMap<String,String>();
                if(config.gpu().equals("virgl")) {
                    Path angle=Path.of(config.get("angle","angle/bin"));if(!angle.isAbsolute())angle=config.root.resolve(angle);
                    for(String library:List.of("libEGL.dll","libGLESv2.dll"))if(!Files.isRegularFile(angle.resolve(library)))throw new IOException("ANGLE runtime missing: "+angle);
                    nativeEnvironment.put("PATH",angle.toAbsolutePath()+File.pathSeparator+System.getenv().getOrDefault("PATH",""));
                }else config.executable("ffmpeg","ffmpeg");
                String input=config.get("input","auto");
                if(input.equals("auto")) {
                    input="mouse";
                    if(!config.flag("bios")) {
                        var probe=spawn("qemu-devices",List.of(config.executable("qemu",config.guest().equals("arm64")?"qemu-system-aarch64":"qemu-system-x86_64").toString(),"-device","help"),nativeEnvironment,true);
                        var output=new CompletableFuture<String>();Thread.ofPlatform().daemon().start(()->{try{output.complete(new String(probe.output().readNBytes(1024*1024),StandardCharsets.UTF_8));}catch(Exception e){output.completeExceptionally(e);}});
                        // macOS verifies newly extracted ad-hoc signed binaries and their dylibs on first launch.
                        String devices;try{devices=output.get(30,TimeUnit.SECONDS);}finally{probe.close();}
                        if(devices.contains("virtio-multitouch-pci"))input="touchscreen";
                    }
                }
                int vnc=config.display().equals("vnc")?port(true):0,qmp;do{qmp=port(false);}while(qmp==vnc);String uuid=UUID.randomUUID().toString();qmpPort=qmp;vmUuid=uuid;
                if(config.flag("environment"))environment=new EnvironmentChannel();
                if(config.flag("camera"))camera=new CameraChannel();
                var command=config.qemu(session,vnc,qmp,uuid,input,environment==null?0:environment.port(),camera==null?0:camera.port());
                if(!config.values.containsKey("colorOrder"))config.values.setProperty("colorOrder",config.gpu().equals("virgl")||config.guest().equals("arm64")||!command.contains("-kernel")?"rgb":"bgr");
                Files.writeString(session.resolve("qemu-command.json"),Json.write(command));
                var qemu=spawn("qemu",command,nativeEnvironment,false);ownedQemu=qemu;
                long deadline=System.nanoTime()+30_000_000_000L;boolean connected=false;
                while(System.nanoTime()<deadline){cancelled();if(!qemu.alive())throw new IOException("QEMU exited: "+qemu.tail());
                    try(var control=new QmpClient(qmp,1000)){Object identity=control.execute("query-uuid",null);
                        if(!(identity instanceof Map<?,?> map)||!uuid.equals(map.get("UUID")))throw new IllegalStateException("QEMU identity mismatch");connected=true;break;}
                    catch(IOException e){Thread.sleep(100);}}
                if(!connected)throw new IOException("QEMU startup timeout: "+qemu.tail());
                device.backend=config.display().equals("dbus")?new QemuDbusDevice(this,config,device,qemu.childPid,qmp,input):new QemuDevice(this,config,device,vnc,qmp,input);
            } else throw new IllegalArgumentException("Unknown Java backend: "+backend);
            cancelled();device.backend.start();
            synchronized(this){cancelled();state=State.READY;message="安卓运行环境已启动（Java）";save();ready.complete(this);}
            while(!stop){device.backend.check();if(device.failure!=null)throw new IOException("Device failed",device.failure);
                synchronized(this){for(var p:processes){if(!p.name.startsWith("qemu-devices")&&p.name.startsWith("qemu-")&&!p.alive())throw new IOException("QEMU exited: "+p.tail());
                    if(Files.exists(p.log)&&Files.size(p.log)>64L*1024*1024)throw new IOException("Native log size limit exceeded");}}
                Thread.sleep(100);}
        }catch(Exception error){
            if(!stop){
                state=State.FAILED;message=error.getMessage()==null?error.toString():error.getMessage();
                if(session!=null)try(var log=new PrintWriter(Files.newBufferedWriter(session.resolve("failure.log")))){error.printStackTrace(log);}catch(IOException ignored){}
            }
            ready.completeExceptionally(error);
        }
        finally {
            // Stop accepting launches before closing native pipes, including concurrent resize launches.
            synchronized(this){stop=true;}
            // Keep guest transports connected while Android shuts down its drivers.
            if(camera!=null)camera.invalidate();
            try{if(device!=null)device.close();}catch(Exception error){state=State.FAILED;message="Device cleanup failed: "+error;}
            if(ownedQemu!=null&&ownedQemu.alive()) {
                if(persistentGuest) {
                    message="正在等待安卓保存数据并关机…";
                    try(var controller=new QmpClient(qmpPort,1000)) {
                        shutdownOutcome=controller.powerdown(vmUuid,15000,shutdownPowerKey)?"guest-confirmed":"forced-timeout";
                    }catch(IOException error){shutdownOutcome="forced-error";}
                }else shutdownOutcome="immediate-snapshot";
                if(!shutdownOutcome.equals("guest-confirmed"))
                try(var controller=new QmpClient(qmpPort,1000)) {
                    Object identity=controller.execute("query-uuid",null);
                    if(identity instanceof Map<?,?> map&&vmUuid.equals(map.get("UUID")))controller.execute("quit",null);
                }catch(IOException ignored){}
                try{ownedQemu.guardian.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
            List<OwnedProcess> owned;synchronized(this){owned=List.copyOf(processes);}
            for(var p:owned.reversed())try{p.close();}catch(Exception error){state=State.FAILED;message="Native cleanup failed: "+error;}
            if(environment!=null)environment.close();
            if(camera!=null)camera.close();
            if(storage!=null)try{storage.close();}catch(IOException error){state=State.FAILED;message="Storage cleanup failed: "+error;}
            if(state!=State.FAILED){state=State.STOPPED;message=shutdownOutcome.startsWith("forced-")?"安卓未确认正常关机，已强制关闭；未落盘的数据可能丢失":"安卓运行环境已关闭";}
            try{save();}catch(IOException error){state=State.FAILED;message="Session record failed: "+error;}
            ready.completeExceptionally(new CancellationException("Runtime stopped"));
            try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored){}
        }
    }
    private Properties settings()throws IOException {
        Path file=gameDirectory.resolve("config/mcandroidphone-runtime.properties");Files.createDirectories(file.getParent());
        if(!Files.exists(file))try{Files.writeString(file,"""
            # MC Android Phone Java runtime. Uses Minecraft's Java; no Python installation required.
            # QEMU, FFmpeg and Android media are external native dependencies.
            backend=qemu
            gpu=virtio
            display=auto
            input=auto
            # root defaults to <game>/mcandroidphone/runtime; relative paths resolve against root.
            # guestArch=arm64
            # firmware=/path/to/edk2-aarch64-code.fd
            # disk=/path/to/android.qcow2
            # kernelAppend=console=ttyAMA0 ... (image-specific ARM64 kernel arguments)
            width=1080
            height=1920
            density=480
            memory=4096
            cpus=2
            accel=auto
            """,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}
        var p=new Properties();try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}overrides.forEach(p::setProperty);
        if(p.getProperty("backend","qemu").equals("qemu")&&!Boolean.parseBoolean(p.getProperty("bios","false"))&&List.of("disk","iso","kernel").stream().allMatch(k->p.getProperty(k,"").isBlank())) {
            message="正在检查并解压内置安卓运行包…";
            Properties embedded=RuntimeBundle.install(gameDirectory,name->ManagedRuntime.class.getResourceAsStream(name),this::cancelled);
            for(String key:embedded.stringPropertyNames())p.putIfAbsent(key,embedded.getProperty(key));
        }
        Path root=Path.of(p.getProperty("root",gameDirectory.resolve("mcandroidphone/runtime").toString()));
        if(!root.isAbsolute())root=gameDirectory.resolve(root);p.setProperty("root",root.normalize().toString());return p;
    }
    @Override public synchronized void close(){stop=true;ready.completeExceptionally(new CancellationException("Runtime closed"));
        if(worker==null){state=State.STOPPED;message="安卓运行环境已关闭";}
        else if(state!=State.FAILED&&state!=State.STOPPED){state=State.STOPPING;message="正在关闭安卓运行环境…";}}
    public void awaitStopped(Duration timeout){Thread active=worker;if(active==null||active==Thread.currentThread())return;
        try{active.join(timeout.toMillis());}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
