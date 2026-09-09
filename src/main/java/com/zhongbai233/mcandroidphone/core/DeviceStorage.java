package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Private, independently writable media per item UUID. Templates are never opened for writes. */
final class DeviceStorage implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private DeviceStorage(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}

    static DeviceStorage prepare(RuntimeConfig config,Path game,Runnable cancelled)throws IOException {
        String mode=config.get("storage",config.get("deviceId","").isEmpty()?"snapshot":"persistent");
        if(!Set.of("snapshot","persistent").contains(mode))throw new IOException("Invalid storage mode");
        if(mode.equals("snapshot")||config.flag("bios")||!config.get("backend","qemu").equals("qemu"))return null;
        String id=config.get("deviceId","");
        try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw new IOException("Persistent storage requires a canonical deviceId UUID",e);}
        Path parent=Files.createDirectories(game.resolve("mcandroidphone/devices"));
        var channel=FileChannel.open(parent.resolve(id+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException e){channel.close();throw new IOException("This phone is already running",e);}
        if(lock==null){channel.close();throw new IOException("This phone is already running");}
        var storage=new DeviceStorage(channel,lock);
        try {
            Path folder=parent.resolve(id);
            if(Files.isSymbolicLink(folder))throw new IOException("Phone storage must not be a symbolic link");
            if(!Files.exists(folder)) {
                if(config.path("disk")==null)throw new IOException("Persistent storage requires an installed Android disk");
                String layout=config.get("diskLayout","overlay");
                if(!Set.of("copy","overlay").contains(layout))throw new IOException("Invalid diskLayout");
                boolean overlay=layout.equals("overlay");
                var sources=new LinkedHashMap<String,Path>();
                for(String key:List.of("disk","dataDisk","firmwareVars")){Path source=config.path(key);if(source!=null)sources.put(key,source);}
                String base=overlay?SharedSystemDisk.prepare(parent,sources.get("disk"),config.get("diskFormat","qcow2"),cancelled):null;
                long required=0;for(var entry:sources.entrySet())required=Math.addExact(required,overlay&&entry.getKey().equals("disk")?262144:Files.size(entry.getValue()));
                if(Files.getFileStore(parent).getUsableSpace()<required+256L*1024*1024)throw new IOException("Not enough free space to create this phone's storage");
                Path staging=Files.createTempDirectory(parent,".initializing-");
                try {
                    var info=new Properties();info.setProperty("guestArch",config.guest());info.setProperty("schema",overlay?"2":"1");
                    if(overlay)info.setProperty("diskBacking",base);
                    for(var entry:sources.entrySet()) {
                        String key=entry.getKey(),format=config.get(key.equals("disk")?"diskFormat":key.equals("dataDisk")?"dataDiskFormat":"firmwareVarsFormat",key.equals("firmwareVars")?"raw":"qcow2");
                        if(!Set.of("raw","qcow2").contains(format))throw new IOException("Invalid media format");
                        if(overlay&&key.equals("disk"))format="qcow2";
                        String name=key+"."+format;
                        if(overlay&&key.equals("disk"))SharedSystemDisk.createOverlay(staging.resolve(name),base,
                            SharedSystemDisk.virtualSize(parent.resolve(".bases").resolve(base),config.get("diskFormat","qcow2")));
                        else try(var in=FileChannel.open(entry.getValue(),StandardOpenOption.READ);var out=FileChannel.open(staging.resolve(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                            long offset=0,size=in.size();while(offset<size){cancelled.run();long n=in.transferTo(offset,Math.min(8*1024*1024,size-offset),out);if(n<=0)throw new IOException("Media copy made no progress");offset+=n;}out.force(true);
                        }
                        info.setProperty(key,name);info.setProperty(key+"Format",format);info.setProperty(key+"Source",entry.getValue().toString());
                    }
                    try(var out=Files.newBufferedWriter(staging.resolve("storage.properties"))){info.store(out,"Persistent phone media; never automatically reset or replaced");}
                    cancelled.run();Files.move(staging,folder,StandardCopyOption.ATOMIC_MOVE);
                }finally{if(Files.exists(staging))try(var paths=Files.walk(staging)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
            }
            var info=new Properties();try(var in=Files.newBufferedReader(folder.resolve("storage.properties"))){info.load(in);}
            if(!Set.of("1","2").contains(info.getProperty("schema",""))||!info.getProperty("guestArch","").equals(config.guest()))throw new IOException("Phone storage architecture/version mismatch; existing data was preserved");
            if(info.getProperty("schema").equals("2")) {
                String base=info.getProperty("diskBacking","");
                SharedSystemDisk.verify(parent,base,cancelled);
                if(!"disk.qcow2".equals(info.getProperty("disk"))||!"qcow2".equals(info.getProperty("diskFormat")))throw new IOException("Invalid system overlay metadata");
                Path disk=folder.resolve("disk.qcow2");
                if(!Files.isRegularFile(disk,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing system overlay");
                SharedSystemDisk.verifyOverlay(disk,base);
            }
            for(String key:List.of("disk","dataDisk","firmwareVars")) {
                String name=info.getProperty(key);
                if(name==null){config.values.remove(key);continue;}
                String format=info.getProperty(key+"Format","");
                if(!Set.of("raw","qcow2").contains(format)||!name.equals(key+"."+format))throw new IOException("Invalid stored media path");
                Path p=folder.resolve(name);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Phone media missing; existing data was preserved: "+p);
                config.values.setProperty(key,p.toString());config.values.setProperty(key.equals("disk")?"diskFormat":key+"Format",format);
            }
            config.persistentDisks=true;return storage;
        }catch(Throwable e){storage.close();throw e;}
    }
    @Override public void close()throws IOException {try{lock.release();}finally{channel.close();}}
}
