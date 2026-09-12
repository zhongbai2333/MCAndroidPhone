package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;

/** Pinned image downloads. Only immutable clean templates enter the shared image cache. */
final class RemoteAndroidImages {
    static final long MAX_ARCHIVE=8L*1024*1024*1024;
    static Properties install(Path game,String arch,Function<String,InputStream> resources,Runnable cancelled,Consumer<String> status)throws IOException {
        return install(game,arch,resources,cancelled,status,false,()->System.getenv("MCANDROIDPHONE_GITHUB_TOKEN"));
    }
    // Loopback HTTP is available only to the in-process test fixture, never a runtime setting.
    static Properties install(Path game,String arch,Function<String,InputStream> resources,Runnable cancelled,Consumer<String> status,boolean testHttp,Supplier<String> token)throws IOException {
        if(!Set.of("amd64","arm64").contains(arch))throw new IOException("Invalid image architecture");
        byte[] bytes;
        try(var in=resources.apply("/mcandroidphone/images/"+arch+".properties")) {
            if(in==null)return new Properties();bytes=in.readNBytes(65537);
            if(bytes.length>65536)throw new IOException("Image descriptor too large");
        }
        var descriptor=new Properties();descriptor.load(new ByteArrayInputStream(bytes));
        if(!descriptor.getProperty("platform","").equals("android-"+arch)||!descriptor.getProperty("config.guestArch","").equals(arch)||!descriptor.getProperty("files","").equals("3"))throw new IOException("Image descriptor architecture/files mismatch");
        URI url;long length;
        try{url=URI.create(descriptor.getProperty("download.url",""));length=Long.parseLong(descriptor.getProperty("download.bytes","0"));}
        catch(IllegalArgumentException e){throw new IOException("Invalid image download descriptor",e);}
        validateUrl(url,testHttp);
        String hash=descriptor.getProperty("download.sha256","");
        if(length<1||length>MAX_ARCHIVE||!hash.matches("[0-9a-f]{64}"))throw new IOException("Invalid image archive size/hash");
        var mapping=new HashMap<String,String>();var archiveNames=new HashSet<String>();var imagePaths=new HashSet<String>();
        for(String key:List.of("disk","dataDisk","firmwareVars")) {
            String path=descriptor.getProperty("config."+key,"");RuntimeBundle.safePath(game,path);
            if(!imagePaths.add(path))throw new IOException("Image disk roles must be distinct");
        }
        String prefix="/mcandroidphone/images/"+arch+"/";
        for(int i=0;i<3;i++) {
            String key="file."+i+".",path=descriptor.getProperty(key+"path","");
            String stored=descriptor.getProperty(key+"archivePath",path);RuntimeBundle.safePath(game,stored);
            if(!imagePaths.contains(path)||mapping.put(prefix+"files/"+path,stored)!=null||!archiveNames.add(stored)||!descriptor.getProperty(key+"executable","false").equals("false"))throw new IOException("Invalid image archive mapping");
        }
        for(String key:descriptor.stringPropertyNames())if(key.startsWith("config.")&&!Set.of("disk","diskFormat","dataDisk","dataDiskFormat","firmwareVars","firmwareVarsFormat","guestArch","width","height","density","memory","cpus","cpuModel","shutdownMethod").contains(key.substring(7)))throw new IOException("Unexpected image runtime setting");
        long decoded=0;
        try{for(int i=0;i<3;i++)decoded=Math.addExact(decoded,Long.parseLong(descriptor.getProperty("file."+i+".size","-1")));}
        catch(IllegalArgumentException|ArithmeticException e){throw new IOException("Invalid decoded image size",e);}
        final long decodedBytes=decoded;
        final ZipFile[] zip={null};
        Function<String,InputStream> source=name->{
            if(name.equals(prefix+"manifest.properties"))return new ByteArrayInputStream(bytes);
            String entry=mapping.get(name);if(entry==null)return null;
            try {
                if(zip[0]==null) {
                    Path archive=download(game,arch,url,length,hash,decodedBytes,cancelled,status,testHttp,token);
                    zip[0]=new ZipFile(archive.toFile());var seen=new HashSet<String>();
                    for(var z:zip[0].stream().toList())if(z.isDirectory()||!seen.add(z.getName())||!archiveNames.contains(z.getName())&&!Set.of("README.txt","SHA256SUMS").contains(z.getName()))throw new IOException("Unexpected image ZIP entry");
                    if(!seen.containsAll(archiveNames))throw new IOException("Missing image ZIP entries");
                    status.accept("正在校验并解压安卓镜像…");
                }
                return zip[0].getInputStream(zip[0].getEntry(entry));
            }catch(IOException e){throw new UncheckedIOException(e);}
        };
        try {status.accept("正在检查安卓镜像缓存…");return RuntimeBundle.installImages(game,arch,source,cancelled);}
        catch(UncheckedIOException e){throw e.getCause();}
        finally{if(zip[0]!=null)zip[0].close();}
    }
    static void validateUrl(URI url,boolean testHttp)throws IOException {
        boolean loopback=testHttp&&"http".equals(url.getScheme())&&Set.of("127.0.0.1","[::1]","::1").contains(url.getHost());
        if(url.getHost()==null||url.getUserInfo()!=null||url.getFragment()!=null||!"https".equals(url.getScheme())&&!loopback)throw new IOException("Android image URL must use HTTPS");
    }
    static Path download(Path game,String arch,URI url,long length,String hash,long decodedBytes,Runnable cancelled,Consumer<String> status,boolean testHttp,Supplier<String> token)throws IOException {
        Path cache=RuntimeBundle.safePath(game.toAbsolutePath().normalize(),"mcandroidphone/downloads");Files.createDirectories(cache);
        String name="android-"+arch+"-"+hash;
        Path complete=RuntimeBundle.safePath(cache,name+".zip"),partial=RuntimeBundle.safePath(cache,name+".partial");
        try(var channel=FileChannel.open(RuntimeBundle.safePath(cache,name+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock=null;
            while(lock==null){cancelled.run();try{lock=channel.tryLock();}catch(OverlappingFileLockException ignored){}if(lock==null)pause(cancelled);}
            try {
                if(Files.exists(complete)) {
                    if(Files.size(complete)==length&&RuntimeBundle.hash(complete,cancelled).equals(hash))return complete;
                    throw new IOException("安卓下载缓存校验失败，请删除该下载包后重试："+complete.getFileName());
                }
                IOException last=null;
                for(int attempt=0;attempt<3;attempt++) {
                    cancelled.run();
                    try {
                        if(Files.exists(partial)&&Files.size(partial)>length)Files.delete(partial);
                        long offset=Files.exists(partial)?Files.size(partial):0;
                        if(offset<length) {
                            if(Files.getFileStore(cache).getUsableSpace()<length-offset+decodedBytes+256L*1024*1024)throw new IOException("Not enough free space for Android download");
                            transfer(url,partial,offset,length,cancelled,status,testHttp,token);
                        }
                        status.accept("正在校验安卓下载包…");
                        if(Files.size(partial)!=length||!RuntimeBundle.hash(partial,cancelled).equals(hash)) {
                            Files.delete(partial);throw new IOException("安卓镜像 SHA-256 校验失败，已丢弃下载内容");
                        }
                        Files.move(partial,complete,StandardCopyOption.ATOMIC_MOVE);return complete;
                    }catch(IOException e){last=e;if(attempt<2){status.accept("安卓镜像下载中断，正在重试（"+(attempt+1)+"/2）…");pause(cancelled);}}
                }
                throw new IOException("安卓镜像下载失败，可再次右键续传："+last.getMessage(),last);
            }finally{lock.release();}
        }
    }
    private static void pause(Runnable cancelled)throws IOException {
        cancelled.run();try{Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Image download interrupted",e);}cancelled.run();
    }
    private static void transfer(URI initial,Path partial,long offset,long length,Runnable cancelled,Consumer<String> status,boolean testHttp,Supplier<String> token)throws IOException {
        URI uri=initial;HttpURLConnection connection=null;
        try {
            for(int redirects=0;;redirects++) {
                cancelled.run();validateUrl(uri,testHttp);
                connection=(HttpURLConnection)uri.toURL().openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept","application/octet-stream");connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("User-Agent","MCAndroidPhone-image-installer");
                if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
                // An optional developer token is never stored in config/logs or forwarded to a CDN.
                if("https".equals(uri.getScheme())&&"api.github.com".equalsIgnoreCase(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443)) {
                    String value=token.get();if(value!=null&&!value.isBlank())connection.setRequestProperty("Authorization","Bearer "+value.trim());
                }
                int code=connection.getResponseCode();
                if(Set.of(301,302,303,307,308).contains(code)) {
                    if(redirects>=5)throw new IOException("Too many image download redirects");
                    String location=connection.getHeaderField("Location");if(location==null)throw new IOException("Image redirect has no destination");
                    URI next;try{next=uri.resolve(location);}catch(IllegalArgumentException e){throw new IOException("Invalid image redirect",e);}
                    connection.disconnect();connection=null;uri=next;continue;
                }
                if(code!=200&&code!=206)throw new IOException("HTTP "+code+((code==401||code==403||code==404)?"；私有 GitHub Release 需授权或预先下载到缓存":""));
                if(code==206) {
                    String expected="bytes "+offset+"-"+(length-1)+"/"+length;
                    if(!expected.equals(connection.getHeaderField("Content-Range")))throw new IOException("Invalid image resume range");
                }else offset=0;
                long contentLength=connection.getContentLengthLong();
                if(contentLength>=0&&contentLength!=length-offset)throw new IOException("Image HTTP length differs from pinned descriptor");
                long written=offset;
                try(var input=connection.getInputStream();var output=Files.newOutputStream(partial,StandardOpenOption.CREATE,StandardOpenOption.WRITE,offset==0?StandardOpenOption.TRUNCATE_EXISTING:StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer=new byte[256*1024];int n;
                    while((n=input.read(buffer))!=-1) {
                        cancelled.run();if(n>length-written)throw new IOException("Image download exceeds pinned size");
                        output.write(buffer,0,n);written+=n;
                        status.accept(String.format(Locale.ROOT,"正在下载安卓镜像：%d%%（%.1f / %.1f MB）",written*100/length,written/1e6,length/1e6));
                    }
                }
                if(written!=length)throw new EOFException("Incomplete Android image download");
                return;
            }
        }finally{if(connection!=null)connection.disconnect();}
    }
}
