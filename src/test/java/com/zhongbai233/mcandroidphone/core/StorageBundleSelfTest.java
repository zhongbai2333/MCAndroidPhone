package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class StorageBundleSelfTest {
    private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    private static void rejected(Throwing action)throws Exception {try{action.run();throw new AssertionError("Invalid state accepted");}catch(IOException expected){}}
    private interface Throwing{void run()throws Exception;}
    private static RuntimeConfig config(Path root,String id){var p=new Properties();p.setProperty("root",root.toString());p.setProperty("deviceId",id);p.setProperty("diskLayout","copy");p.setProperty("disk","system.qcow2");p.setProperty("dataDisk","user.qcow2");p.setProperty("firmwareVars","vars.fd");return new RuntimeConfig(p);}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-storage-tests-");
        try {
            Files.writeString(root.resolve("system.qcow2"),"pristine system");Files.writeString(root.resolve("user.qcow2"),"pristine user");Files.writeString(root.resolve("vars.fd"),"pristine EFI");
            String id=UUID.randomUUID().toString();RuntimeConfig first=config(root,id);Path saved;
            try(var storage=DeviceStorage.prepare(first,root,()->{})) {
                check(storage!=null&&first.persistentDisks,"Persistent mode selected for item UUID");saved=first.path("dataDisk");Files.writeString(saved,"installed app and photo");
                rejected(()->DeviceStorage.prepare(config(root,id),root,()->{}));
                check(Files.readString(root.resolve("user.qcow2")).equals("pristine user"),"Template preserved");
            }
            RuntimeConfig second=config(root,id);
            second.values.remove("diskLayout"); // New overlay default must never migrate an existing schema-1 phone.
            try(var storage=DeviceStorage.prepare(second,root,()->{})){check(second.path("dataDisk").equals(saved)&&Files.readString(saved).equals("installed app and photo"),"Restart preserved user data");check(Files.readString(second.path("disk")).equals("pristine system"),"Legacy full system disk preserved");}
            try(var storage=DeviceStorage.prepare(config(root,UUID.randomUUID().toString()),root,()->{})){check(storage!=null,"Second independent phone");}
            RuntimeConfig snapshot=config(root,id);snapshot.values.setProperty("storage","snapshot");check(DeviceStorage.prepare(snapshot,root,()->{})==null,"Smoke snapshots do not acquire persistent state");
            rejected(()->DeviceStorage.prepare(config(root,"../escape"),root,()->{}));
            var resourceBytes=new HashMap<String,byte[]>();String prefix="/mcandroidphone/bundle/"+RuntimeBundle.platform()+"/";byte[] payload="native executable fixture".getBytes();
            var manifest=new Properties();manifest.setProperty("schema","1");manifest.setProperty("platform",RuntimeBundle.platform());manifest.setProperty("files","1");manifest.setProperty("file.0.path","bin/qemu");manifest.setProperty("file.0.size",""+payload.length);manifest.setProperty("file.0.sha256",RuntimeBundle.sha256(payload));manifest.setProperty("file.0.executable","true");manifest.setProperty("config.qemu","bin/qemu");
            var buffer=new ByteArrayOutputStream();manifest.store(buffer,"");resourceBytes.put(prefix+"manifest.properties",buffer.toByteArray());resourceBytes.put(prefix+"files/bin/qemu",payload);
            java.util.function.Function<String,InputStream> resources=k->resourceBytes.containsKey(k)?new ByteArrayInputStream(resourceBytes.get(k)):null;
            Properties installed=RuntimeBundle.install(root,resources,()->{});Path binary=Path.of(installed.getProperty("qemu"));check(Arrays.equals(Files.readAllBytes(binary),payload),"Extraction bytes");
            check(RuntimeBundle.install(root,resources,()->{}).getProperty("root").equals(installed.getProperty("root")),"Cache reused");
            Files.writeString(binary,"tampered");rejected(()->RuntimeBundle.install(root,resources,()->{}));
            manifest.setProperty("file.0.path","../outside");buffer.reset();manifest.store(buffer,"");resourceBytes.put(prefix+"manifest.properties",buffer.toByteArray());rejected(()->RuntimeBundle.install(root,resources,()->{}));
            manifest.setProperty("file.0.path","bin/qemu");manifest.setProperty("file.0.sha256","0".repeat(64));buffer.reset();manifest.store(buffer,"");resourceBytes.put(prefix+"manifest.properties",buffer.toByteArray());rejected(()->RuntimeBundle.install(root,resources,()->{}));
            check(RuntimeBundle.install(root,k->null,()->{}).isEmpty(),"Thin JAR retains external configuration path");
            System.out.println("STORAGE_BUNDLE_TESTS_OK: restart, isolation, lock, templates, snapshot, integrity, traversal, cache");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
