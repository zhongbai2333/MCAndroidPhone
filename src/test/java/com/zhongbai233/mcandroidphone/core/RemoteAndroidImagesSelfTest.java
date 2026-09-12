package com.zhongbai233.mcandroidphone.core;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.zip.*;

public final class RemoteAndroidImagesSelfTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    interface Action{void run()throws Exception;}
    static void rejected(Action action)throws Exception {try{action.run();throw new AssertionError("Invalid download accepted");}catch(IOException expected){}}
    static byte[] properties(Properties p)throws IOException {var b=new ByteArrayOutputStream();p.store(b,null);return b.toString(java.nio.charset.StandardCharsets.ISO_8859_1).lines().filter(line->!line.startsWith("#")).sorted().collect(java.util.stream.Collectors.joining("\n","","\n")).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);}
    static final class Fixture implements AutoCloseable {
        final HttpServer server;final ExecutorService threads=Executors.newCachedThreadPool();final AtomicInteger requests=new AtomicInteger(),ranges=new AtomicInteger();
        final AtomicBoolean truncate=new AtomicBoolean(),badRange=new AtomicBoolean(),ignoreRange=new AtomicBoolean();
        final byte[] zip,system;final Properties descriptor=new Properties();
        Fixture()throws Exception {
            system=new byte[2*1024*1024];new Random(42).nextBytes(system);
            var payload=new LinkedHashMap<String,byte[]>();payload.put("vda.qcow2",system);payload.put("vdb.qcow2","clean userdata".getBytes());payload.put("efi_vars.fd","clean vars".getBytes());
            var bytes=new ByteArrayOutputStream();try(var z=new ZipOutputStream(bytes)){for(var e:payload.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}zip=bytes.toByteArray();
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);
            server.createContext("/image",exchange->{
                requests.incrementAndGet();check(exchange.getRequestHeaders().getFirst("Authorization")==null,"Token leaked to loopback");
                int start=0;String range=exchange.getRequestHeaders().getFirst("Range");
                if(range!=null){ranges.incrementAndGet();start=Integer.parseInt(range.substring(6,range.length()-1));}
                if(ignoreRange.get())start=0;
                int code=start==0?200:206;if(code==206)exchange.getResponseHeaders().set("Content-Range","bytes "+(badRange.get()?start+1:start)+"-"+(zip.length-1)+"/"+zip.length);
                exchange.sendResponseHeaders(code,zip.length-start);
                try(var out=exchange.getResponseBody()){if(truncate.compareAndSet(true,false))out.write(zip,start,(zip.length-start)/2);else out.write(zip,start,zip.length-start);}catch(IOException ignored){}finally{exchange.close();}
            });server.start();
            descriptor.setProperty("schema","1");descriptor.setProperty("platform","android-amd64");descriptor.setProperty("files","3");descriptor.setProperty("config.guestArch","amd64");
            String[] roles={"disk","dataDisk","firmwareVars"};int i=0;
            for(var e:payload.entrySet()) {String k="file."+i+".";descriptor.setProperty(k+"path",e.getKey());descriptor.setProperty(k+"size",""+e.getValue().length);descriptor.setProperty(k+"sha256",RuntimeBundle.sha256(e.getValue()));descriptor.setProperty("config."+roles[i++],e.getKey());}
            descriptor.setProperty("download.url","http://127.0.0.1:"+server.getAddress().getPort()+"/image");descriptor.setProperty("download.bytes",""+zip.length);descriptor.setProperty("download.sha256",RuntimeBundle.sha256(zip));
        }
        Function<String,InputStream> resources()throws IOException {byte[] b=properties(descriptor);return name->name.equals("/mcandroidphone/images/amd64.properties")?new ByteArrayInputStream(b):null;}
        Properties install(Path game)throws Exception{return install(game,()->{},s->{});}
        Properties install(Path game,Runnable cancelled,Consumer<String> status)throws Exception {
            return RemoteAndroidImages.install(game,"amd64",resources(),cancelled,status,true,()->{throw new AssertionError("Token requested for an untrusted origin");});
        }
        public void close(){server.stop(0);threads.shutdownNow();}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-download-tests-");
        try {
            try(var f=new Fixture()) {
                Path game=root.resolve("resume");f.truncate.set(true);var messages=new ArrayList<String>();
                Properties installed=f.install(game,()->{},messages::add);
                check(Arrays.equals(Files.readAllBytes(Path.of(installed.getProperty("disk"))),f.system),"Resumed image differs");check(f.ranges.get()>0,"Interrupted response did not resume");check(messages.stream().anyMatch(s->s.contains("100%")),"No visible progress");
                int before=f.requests.get();Properties cached=f.install(game);check(cached.equals(installed)&&f.requests.get()==before,"Cache reuse contacted network");
                Files.createDirectories(game.resolve("mcandroidphone/devices"));Files.writeString(game.resolve("mcandroidphone/devices/user-marker"),"keep user data");
                Files.writeString(Path.of(installed.getProperty("disk")),"corrupt image cache");rejected(()->f.install(game));check(Files.readString(game.resolve("mcandroidphone/devices/user-marker")).equals("keep user data"),"Touched device data");
            }
            try(var f=new Fixture()) {
                Path game=root.resolve("cancel");var stop=new AtomicBoolean();
                try{f.install(game,()->{if(stop.get())throw new CancellationException();},s->{if(s.contains("正在下载"))stop.set(true);});throw new AssertionError("Cancel ignored");}catch(CancellationException expected){}
                try(var files=Files.list(game.resolve("mcandroidphone/downloads"))){check(files.anyMatch(p->p.toString().endsWith(".partial")),"Cancellation lost resumable data");}
                f.install(game);check(f.ranges.get()>0,"Retry did not use partial download");
            }
            try(var f=new Fixture()) {
                Path game=root.resolve("ignored-range");var stop=new AtomicBoolean();
                try{f.install(game,()->{if(stop.get())throw new CancellationException();},s->{if(s.contains("正在下载"))stop.set(true);});}catch(CancellationException expected){}
                f.ignoreRange.set(true);check(Arrays.equals(Files.readAllBytes(Path.of(f.install(game).getProperty("disk"))),f.system),"HTTP 200 resume duplicated bytes");
            }
            try(var f=new Fixture()) {
                Path game=root.resolve("bad-range");f.truncate.set(true);f.badRange.set(true);rejected(()->f.install(game));
                try(var paths=Files.list(game.resolve("mcandroidphone/images"))){check(paths.noneMatch(p->p.getFileName().toString().startsWith("android-amd64-")),"Published failed image");}
            }
            try(var f=new Fixture()) {
                f.descriptor.setProperty("download.sha256","0".repeat(64));rejected(()->f.install(root.resolve("bad-hash")));
                f.descriptor.setProperty("file.0.path","../outside");int count=f.requests.get();rejected(()->f.install(root.resolve("traversal")));check(f.requests.get()==count,"Bad descriptor accessed network");
                f.descriptor.setProperty("platform","android-arm64");rejected(()->f.install(root.resolve("wrong-arch")));
            }
            try(var f=new Fixture()) {
                Path game=root.resolve("concurrent");var pool=Executors.newFixedThreadPool(2);
                try{var a=pool.submit(()->f.install(game));var b=pool.submit(()->f.install(game));check(a.get(20,TimeUnit.SECONDS).equals(b.get(20,TimeUnit.SECONDS)),"Concurrent cache differs");check(f.requests.get()==1,"Concurrent duplicate transfer");}finally{pool.shutdownNow();}
            }
            rejected(()->RemoteAndroidImages.validateUrl(URI.create("http://127.0.0.1/file"),false));
            rejected(()->RemoteAndroidImages.validateUrl(URI.create("https://name:secret@example.com/file"),false));
            check(RemoteAndroidImages.install(root,"amd64",name->null,()->{},s->{}).isEmpty(),"Plain Mod changed external media behavior");
            System.out.println("REMOTE_ANDROID_IMAGES_OK: resume, ignored range, bad range/hash, cancellation, cache, concurrent install, no credential forwarding, architecture, traversal, preserved device data");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
