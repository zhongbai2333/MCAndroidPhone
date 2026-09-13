package com.zhongbai233.mcandroidphone.core;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;

public final class AndroidImageLibrarySelfTest {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private interface Attempt{void run()throws Exception;}
    private static void rejected(Attempt a)throws Exception{try{a.run();throw new AssertionError("Invalid image accepted");}catch(IOException expected){}}
    private static void write(Properties p,Path file)throws Exception{try(var out=Files.newOutputStream(file)){p.store(out,null);}}
    private static Properties fixture(Path dir)throws Exception {
        Files.createDirectories(dir);var p=new Properties();p.setProperty("schema","2");p.setProperty("platform","android-amd64");p.setProperty("files","3");p.setProperty("config.guestArch","amd64");p.setProperty("config.width","720");p.setProperty("config.height","1280");
        int i=0;for(String role:List.of("disk","dataDisk","firmwareVars")) {
            String name=role+".raw",key="file."+(i++)+".";byte[] data=new byte[1024];Arrays.fill(data,(byte)i);Files.write(dir.resolve(name),data);
            p.setProperty(key+"path",name);p.setProperty(key+"size","1024");p.setProperty(key+"sha256",RuntimeBundle.sha256(data));p.setProperty("config."+role,name);p.setProperty("config."+role+"Format","raw");
        }
        write(p,dir.resolve("manifest.properties"));return p;
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-image-library-");
        try {
            Path game=root.resolve("game"),source=root.resolve("source");Properties manifest=fixture(source);var library=new AndroidImageLibrary(game);
            String first=library.importLocal(source,"amd64","Test first",()->{},s->{});library.select(first);
            check(library.selected("amd64").equals(first),"Default not saved");Properties config=library.install(first,()->{},s->{});check(config.getProperty("width").equals("720"),"Config lost");
            String device=UUID.randomUUID().toString();config.setProperty("deviceId",device);config.setProperty("storage","persistent");config.setProperty("diskLayout","copy");
            try(var storage=DeviceStorage.prepare(new RuntimeConfig(config),game,()->{})){}
            manifest.setProperty("config.width","1080");write(manifest,source.resolve("manifest.properties"));
            String second=library.importLocal(source,"amd64","Test second",()->{},s->{});library.select(second);
            Properties old=AndroidImageLibrary.forDevice(game,"amd64",device,()->{},s->{});
            check(old.getProperty("width").equals("720")&&old.getProperty("imageId").equals(first),"Default change modified existing phone");
            Path saved=Path.of(old.getProperty("disk"));byte[] data=Files.readAllBytes(saved);library.remove(first);
            check(Arrays.equals(Files.readAllBytes(saved),data),"Removal modified device data");
            check(AndroidImageLibrary.forDevice(game,"amd64",device,()->{},s->{}).equals(old),"Phone depends on removed template");
            rejected(()->library.remove(second));rejected(()->library.importLocal(source,"arm64","wrong arch",()->{},s->{}));
            manifest.setProperty("config.qemu","arbitrary.exe");write(manifest,source.resolve("manifest.properties"));rejected(()->library.importLocal(source,"amd64","bad config",()->{},s->{}));manifest.remove("config.qemu");
            manifest.setProperty("file.0.path","../outside");write(manifest,source.resolve("manifest.properties"));rejected(()->library.importLocal(source,"amd64","traversal",()->{},s->{}));
            fixture(source);try{library.importLocal(source,"amd64","cancel",()->{throw new CancellationException();},s->{});throw new AssertionError("Cancel ignored");}catch(CancellationException expected){}
            // Self-contained zstd ZIP exercises the real private JNI decoder through the import path.
            manifest=fixture(source);Path zipPath=root.resolve("custom.zip");
            try(var zip=new ZipOutputStream(Files.newOutputStream(zipPath))) {
                for(int i=0;i<3;i++) {
                    String key="file."+i+".",name=manifest.getProperty(key+"path");byte[] raw=Files.readAllBytes(source.resolve(name));var encoded=new ByteArrayOutputStream();try(var out=ZstdCompression.encode(encoded)){out.write(raw);}byte[] zst=encoded.toByteArray();
                    manifest.setProperty(key+"compression","zstd");manifest.setProperty(key+"archivePath",name+".zst");manifest.setProperty(key+"storedSize",Integer.toString(zst.length));manifest.setProperty(key+"storedSha256",RuntimeBundle.sha256(zst));
                    zip.putNextEntry(new ZipEntry(name+".zst"));zip.write(zst);zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("manifest.properties"));manifest.store(zip,null);zip.closeEntry();
            }
            String zipped=library.importLocal(zipPath,"amd64","Zstd ZIP",()->{},s->{});Files.delete(zipPath);
            check(library.install(zipped,()->{},s->{}).getProperty("guestArch").equals("amd64"),"Import depends on original ZIP");
            System.out.println("IMAGE_LIBRARY_OK official catalog, custom directory/zstd ZIP, architecture/path/config rejection, cancellation, default selection, preserved device binding, cache removal independent of phone data");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
