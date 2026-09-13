package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;

/** Image templates and the default for new phones. Existing device storage is never reset. */
public final class AndroidImageLibrary {
    public record Image(String id,String name,String arch,boolean official,boolean installed,long bytes){}
    private final Path game,library;
    public AndroidImageLibrary(Path game)throws IOException {
        this.game=game.toAbsolutePath().normalize();library=RuntimeBundle.safePath(this.game,"mcandroidphone/library");Files.createDirectories(library);
    }
    public static String hostArch(){return RuntimeConfig.arch(System.getProperty("os.arch"));}
    private static byte[] bytes(Properties p)throws IOException {var out=new ByteArrayOutputStream();p.store(out,null);return out.toByteArray();}
    private static Properties read(Path path)throws IOException {var p=new Properties();try(var in=Files.newInputStream(path)){byte[] b=in.readNBytes(65537);if(b.length>65536)throw new IOException("Image metadata too large");p.load(new ByteArrayInputStream(b));}return p;}
    private Path record(String id)throws IOException {if(!id.matches("[0-9a-f]{64}"))throw new IOException("Invalid image ID");return RuntimeBundle.safePath(library,id+".properties");}
    private static byte[] official(String arch)throws IOException {
        if(!Set.of("amd64","arm64").contains(arch))throw new IOException("Invalid architecture");
        InputStream stream=AndroidImageLibrary.class.getResourceAsStream("/mcandroidphone/images/"+arch+".properties");
        if(stream==null)stream=AndroidImageLibrary.class.getResourceAsStream("/mcandroidphone/catalog/"+arch+".properties");
        if(stream==null)return null;
        try(var in=stream){byte[] b=in.readNBytes(65537);if(b.length>65536)throw new IOException("Official catalog too large");return b;}
    }
    private static Properties properties(byte[] b)throws IOException {var p=new Properties();p.load(new ByteArrayInputStream(b));return p;}
    private Path installed(String arch,String id)throws IOException {return RuntimeBundle.safePath(game,"mcandroidphone/images/android-"+arch+"-"+id);}
    public synchronized List<Image> list()throws IOException {
        var result=new LinkedHashMap<String,Image>();
        for(String arch:List.of("amd64","arm64")) {
            byte[] b=official(arch);if(b==null)continue;String id=RuntimeBundle.sha256(b);var p=properties(b);
            result.put(id,new Image(id,"官方 Android Go · "+arch,arch,true,Files.isDirectory(installed(arch,id)),Long.parseLong(p.getProperty("download.bytes","0"))));
        }
        try(var files=Files.list(library)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}\\.properties")).sorted().toList()) {
                String id=file.getFileName().toString().substring(0,64);byte[] b=Files.readAllBytes(record(id));
                if(!RuntimeBundle.sha256(b).equals(id))throw new IOException("Image record changed");
                var p=properties(b);String arch=p.getProperty("config.guestArch","");
                result.putIfAbsent(id,new Image(id,p.getProperty("image.name","已安装 Android · "+arch),arch,false,Files.isDirectory(installed(arch,id)),Long.parseLong(p.getProperty("download.bytes","0"))));
            }
        }
        return List.copyOf(result.values());
    }
    public synchronized String selected(String arch)throws IOException {
        Path file=RuntimeBundle.safePath(library,"selection.properties");String id=Files.exists(file)?read(file).getProperty(arch,""):"";
        if(!id.isEmpty())return id;byte[] b=official(arch);return b==null?"":RuntimeBundle.sha256(b);
    }
    private byte[] descriptor(String id)throws IOException {
        for(String arch:List.of("amd64","arm64")){byte[] b=official(arch);if(b!=null&&RuntimeBundle.sha256(b).equals(id))return b;}
        byte[] b=Files.readAllBytes(record(id));if(!RuntimeBundle.sha256(b).equals(id))throw new IOException("Image record changed");return b;
    }
    private void atomic(Path target,byte[] b)throws IOException {
        Path temp=Files.createTempFile(library,".saving-",".tmp");try{Files.write(temp,b);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
    public synchronized void select(String id)throws IOException {
        var p=properties(descriptor(id));String arch=p.getProperty("config.guestArch");
        if(!Files.isDirectory(installed(arch,id)))throw new IOException("请先安装镜像");
        Path file=RuntimeBundle.safePath(library,"selection.properties");Properties selection=Files.exists(file)?read(file):new Properties();selection.setProperty(arch,id);atomic(file,bytes(selection));
    }
    public synchronized Properties install(String id,Runnable cancelled,Consumer<String> status)throws IOException {
        byte[] b=descriptor(id);String arch=properties(b).getProperty("config.guestArch");
        if(properties(b).getProperty("download.url","").equals("https://local.invalid/imported-image")&&!Files.isDirectory(installed(arch,id)))throw new IOException("本地镜像缓存已移除，请重新导入");
        Properties config=RemoteAndroidImages.installDescriptor(game,arch,b,cancelled,status,false,()->System.getenv("MCANDROIDPHONE_GITHUB_TOKEN"),null);
        validateDisks(config);atomic(record(id),b);config.setProperty("imageId",id);return config;
    }
    static Properties forDevice(Path game,String arch,String deviceId,Runnable cancelled,Consumer<String> status)throws IOException {
        // Saved image settings are tied to the phone's already-private disks, not the current default.
        if(deviceId.matches("[0-9a-f-]{36}")) {
            Path saved=RuntimeBundle.safePath(game,"mcandroidphone/devices/"+deviceId+"/storage.properties");
            if(Files.isRegularFile(saved)) {
                Properties storage=read(saved),config=new Properties();
                for(String key:storage.stringPropertyNames())if(key.startsWith("imageConfig."))config.setProperty(key.substring(12),storage.getProperty(key));
                if(!arch.equals(storage.getProperty("guestArch")))throw new IOException("已有手机架构与当前运行环境不同，原数据已保留");
                config.putIfAbsent("guestArch",arch);
                for(String role:List.of("disk","dataDisk","firmwareVars")) {
                    String name=storage.getProperty(role),format=storage.getProperty(role+"Format");
                    if(name!=null){config.setProperty(role,RuntimeBundle.safePath(saved.getParent(),name).toString());config.setProperty(role+"Format",format);}
                }
                return config;
            }
        }
        var library=new AndroidImageLibrary(game);String id=library.selected(arch);
        return id.isEmpty()?new Properties():library.install(id,cancelled,status);
    }
    private static void validateDisks(Properties config)throws IOException {
        for(String role:List.of("disk","dataDisk","firmwareVars"))SharedSystemDisk.virtualSize(Path.of(config.getProperty(role)),config.getProperty(role+"Format","qcow2"));
    }
    /** ZIP with manifest.properties, or a clean three-disk directory (optional manifest.properties). */
    public synchronized String importLocal(Path input,String arch,String label,Runnable cancelled,Consumer<String> status)throws IOException {
        if(!Set.of("amd64","arm64").contains(arch))throw new IOException("Invalid image architecture");
        Path source=input.toAbsolutePath().normalize();if(Files.isSymbolicLink(source))throw new IOException("Image source must not be a symbolic link");
        Properties p;boolean directory=Files.isDirectory(source);status.accept("正在检查本地安卓镜像…");
        if(directory) {
            Path manifest=RuntimeBundle.safePath(source,"manifest.properties");
            p=Files.exists(manifest)?read(manifest):directoryManifest(source,arch,cancelled);
        }else {
            if(!Files.isRegularFile(source)||Files.size(source)>RemoteAndroidImages.MAX_ARCHIVE)throw new IOException("请选择镜像 ZIP 或三盘目录");
            try(var zip=new ZipFile(source.toFile())) {
                var entry=zip.getEntry("manifest.properties");if(entry==null)throw new IOException("镜像包缺少 manifest.properties；旧包可解压到目录并添加对应清单");
                try(var in=zip.getInputStream(entry)){byte[] b=in.readNBytes(65537);if(b.length>65536)throw new IOException("Image manifest too large");p=properties(b);}
            }
        }
        if(!arch.equals(p.getProperty("config.guestArch")))throw new IOException("镜像架构与所选架构不匹配");
        p.setProperty("image.name",label.isBlank()?source.getFileName().toString():label.substring(0,Math.min(label.length(),80)));
        // Imported packages can never turn into automatic network requests.
        p.setProperty("download.url","https://local.invalid/imported-image");
        p.setProperty("download.bytes",directory?"1":Long.toString(Files.size(source)));
        p.setProperty("download.sha256",directory?"0".repeat(64):RuntimeBundle.hash(source,cancelled));
        byte[] b=bytes(p);String id=RuntimeBundle.sha256(b);
        Properties config=RemoteAndroidImages.installDescriptor(game,arch,b,cancelled,status,false,()->null,source);validateDisks(config);
        cancelled.run();atomic(record(id),b);return id;
    }
    private static Properties directoryManifest(Path source,String arch,Runnable cancelled)throws IOException {
        var p=new Properties();p.setProperty("schema","2");p.setProperty("platform","android-"+arch);p.setProperty("files","3");p.setProperty("config.guestArch",arch);
        p.setProperty("config.width","720");p.setProperty("config.height","1280");p.setProperty("config.shutdownMethod","power-key");
        String[] files={"vda.qcow2","vdb.qcow2","efi_vars.fd"},roles={"disk","dataDisk","firmwareVars"};
        for(int i=0;i<3;i++) {
            Path file=RuntimeBundle.safePath(source,files[i]);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("三盘目录需要 vda.qcow2、vdb.qcow2 和 efi_vars.fd");
            String key="file."+i+".";p.setProperty(key+"path",files[i]);p.setProperty(key+"size",Long.toString(Files.size(file)));p.setProperty(key+"sha256",RuntimeBundle.hash(file,cancelled));
            String format="qcow2";if(i==2)try(var data=new DataInputStream(Files.newInputStream(file))){if(data.readInt()!=0x514649fb)format="raw";}
            SharedSystemDisk.virtualSize(file,format);p.setProperty("config."+roles[i],files[i]);p.setProperty("config."+roles[i]+"Format",format);
        }
        return p;
    }
    public synchronized void remove(String id)throws IOException {
        Properties p=properties(descriptor(id));String arch=p.getProperty("config.guestArch");
        if(id.equals(selected(arch)))throw new IOException("请先选择另一个默认镜像");
        // Device bases are independent; remove only this library record/template. No phone data is traversed.
        Path target=installed(arch,id);Files.createDirectories(target.getParent());
        try(var channel=java.nio.channels.FileChannel.open(RuntimeBundle.safePath(target.getParent(),"android-"+arch+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
        java.nio.channels.FileLock lock;
        try{lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){throw new IOException("镜像正在使用，请稍后重试",e);}
        if(lock==null)throw new IOException("镜像正在使用，请稍后重试");
        try(lock) {
        if(Files.exists(target))try(var paths=Files.walk(target)) {
            var all=paths.sorted(Comparator.reverseOrder()).toList();
            for(Path path:all)if(Files.isSymbolicLink(path)||!path.toAbsolutePath().normalize().startsWith(target))throw new IOException("Unsafe image cache path");
            for(Path path:all)Files.delete(path);
        }
        Files.deleteIfExists(record(id));
        }
        }
    }
}
