package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Exercises the actual isolated decoder and atomic runtime installer without native tools. */
public final class CompressedBundleSelfTest {
    private interface Attempt {void run()throws Exception;}
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static void rejected(Attempt action)throws Exception {try{action.run();throw new AssertionError("Invalid stream accepted");}catch(IOException expected){}}
    private static Properties manifest(byte[] data,byte[] xz) {
        var p=new Properties();p.setProperty("schema","2");p.setProperty("platform",RuntimeBundle.platform());p.setProperty("files","1");
        p.setProperty("file.0.path","images/system.qcow2");p.setProperty("file.0.size",""+data.length);p.setProperty("file.0.sha256",RuntimeBundle.sha256(data));
        p.setProperty("file.0.compression","xz");p.setProperty("file.0.storedSize",""+xz.length);p.setProperty("file.0.storedSha256",RuntimeBundle.sha256(xz));p.setProperty("config.disk","images/system.qcow2");return p;
    }
    private static Properties install(Path root,Properties manifest,byte[] xz,Runnable cancel)throws Exception {
        var text=new StringBuilder();manifest.stringPropertyNames().stream().sorted().forEach(key->text.append(key).append("=").append(manifest.getProperty(key)).append("\n"));byte[] bytes=text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);String prefix="/mcandroidphone/bundle/"+RuntimeBundle.platform()+"/";
        return RuntimeBundle.install(root,name->name.equals(prefix+"manifest.properties")?new ByteArrayInputStream(bytes):name.equals(prefix+"files/images/system.qcow2")?new ByteArrayInputStream(xz):null,cancel);
    }
    private static void cleanFailure(Path root)throws Exception {
        Path cache=root.resolve("mcandroidphone/bundles");if(Files.isDirectory(cache))try(var entries=Files.list(cache)){check(entries.noneMatch(Files::isDirectory),"Failed extraction left a staging/installed directory");}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-compressed-tests-");
        try {
            byte[] data=new byte[3*1024*1024];for(int i=0;i<data.length;i++)data[i]=(byte)(i%251);
            var compressed=new ByteArrayOutputStream();try(var out=BundleCompression.encode(compressed)){out.write(data);}byte[] xz=compressed.toByteArray();
            Properties p=manifest(data,xz);Path good=root.resolve("good");var result=install(good,p,xz,()->{});Path disk=Path.of(result.getProperty("disk"));
            check(Arrays.equals(Files.readAllBytes(disk),data),"Decoded image differs");
            check(install(good,p,xz,()->{}).equals(result),"Cache not reused");Files.write(disk,new byte[data.length]);rejected(()->install(good,p,xz,()->{}));
            for(String key:List.of("size","storedSize","sha256","storedSha256","compression")) {
                var bad=manifest(data,xz);bad.setProperty("file.0."+key,key.endsWith("Size")||key.equals("size")?"1":key.contains("ha256")?"0".repeat(64):"unknown");
                Path target=root.resolve(key);rejected(()->install(target,bad,xz,()->{}));cleanFailure(target);
            }
            byte[] corrupt=xz.clone();corrupt[corrupt.length/2]^=1;
            for(byte[] bad:List.of(corrupt,Arrays.copyOf(xz,xz.length-7),Arrays.copyOf(xz,xz.length+3))) {
                Path target=root.resolve(UUID.randomUUID().toString());rejected(()->install(target,manifest(data,bad),bad,()->{}));cleanFailure(target);
            }
            Path cancelled=root.resolve("cancelled");var calls=new java.util.concurrent.atomic.AtomicInteger();
            try{install(cancelled,p,xz,()->{if(calls.incrementAndGet()>5)throw new CancellationException();});throw new AssertionError("Cancel ignored");}catch(CancellationException expected){}cleanFailure(cancelled);
            byte[] huge=Base64.getDecoder().decode("/Td6WFoAAATm1rRGBMAUECEBHgAAAAAAAAAAALVgkgwBAA9jaGVjayBtZW1vcnkgY2FwAMulJ0TtzI3uAAEwELyTd+IftvN9AQAAAAAEWVo=");
            rejected(()->{try(var input=BundleCompression.decode(new ByteArrayInputStream(huge))){input.readAllBytes();}});
            var old=manifest(data,xz);old.setProperty("schema","1");rejected(()->install(root.resolve("old"),old,xz,()->{}));
            System.out.println("COMPRESSED_BUNDLE_OK exact bytes, cached integrity, truncation/corruption, size limits, decoder memory cap, cancellation cleanup, schema compatibility");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
