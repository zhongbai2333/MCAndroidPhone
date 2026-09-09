package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Function;

/** Streaming, integrity-checked installation of this host's embedded runtime; no downloads or shell. */
final class RuntimeBundle {
    static String platform(){String os=System.getProperty("os.name");return (os.startsWith("Windows")?"windows":os.startsWith("Mac")?"macos":os.equals("Linux")?"linux":"unsupported")+"-"+RuntimeConfig.arch(System.getProperty("os.arch"));}
    static String sha256(byte[] bytes){return HexFormat.of().formatHex(digest().digest(bytes));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static String hash(Path file)throws IOException {return hash(file,()->{});}
    static String hash(Path file,Runnable cancelled)throws IOException {var digest=digest();try(var in=Files.newInputStream(file)){byte[] buffer=new byte[1024*1024];int n;while((n=in.read(buffer))!=-1){cancelled.run();digest.update(buffer,0,n);}}return HexFormat.of().formatHex(digest.digest());}
    private record Entry(String path,long size,String hash,boolean executable,String compression,long storedSize,String storedHash){}
    static Path safePath(Path root,String name)throws IOException {
        if(!name.matches("[A-Za-z0-9_.,+/@-]+")||name.startsWith("/")||Arrays.asList(name.split("/",-1)).stream().anyMatch(s->s.equals("..")||s.equals(".")||s.isEmpty()))throw new IOException("Invalid bundled path");
        Path path=root.resolve(name);for(Path p=path;p!=null&&p.startsWith(root);p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symbolic link in runtime bundle cache");return path;
    }
    private static final class StoredInput extends FilterInputStream {
        final MessageDigest hash=digest();final long limit;final Runnable cancelled;long count;
        StoredInput(InputStream in,long limit,Runnable cancelled){super(in);this.limit=limit;this.cancelled=cancelled;}
        @Override public int read()throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:Byte.toUnsignedInt(one[0]);}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {
            cancelled.run();int n=in.read(bytes,offset,(int)Math.min(length,Math.max(1,limit-count+1)));
            if(n>0){count+=n;if(count>limit)throw new IOException("Compressed data exceeds declared size");hash.update(bytes,offset,n);}return n;
        }
    }
    static Properties install(Path game,Function<String,InputStream> resources,Runnable cancelled)throws IOException {
        String platform=platform(),prefix="/mcandroidphone/bundle/"+platform+"/";byte[] bytes;
        try(var in=resources.apply(prefix+"manifest.properties")){if(in==null)return new Properties();bytes=in.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new IOException("Bundle manifest too large");}
        var manifest=new Properties();manifest.load(new ByteArrayInputStream(bytes));
        if(!Set.of("1","2").contains(manifest.getProperty("schema",""))||!manifest.getProperty("platform","").equals(platform))throw new IOException("Wrong embedded runtime platform/version");
        int count=Integer.parseInt(manifest.getProperty("files","0"));if(count<1||count>10000)throw new IOException("Invalid bundle file count");
        var entries=new ArrayList<Entry>();var names=new HashSet<String>();long total=0;
        Path cache=Files.createDirectories(game.resolve("mcandroidphone/bundles"));
        Path installed=cache.resolve(platform+"-"+sha256(bytes));
        for(int i=0;i<count;i++) {
            String key="file."+i+".",name=manifest.getProperty(key+"path","");safePath(installed,name);
            long size=Long.parseLong(manifest.getProperty(key+"size","-1"));String hash=manifest.getProperty(key+"sha256","");String exec=manifest.getProperty(key+"executable","false");
            if(!names.add(name)||size<0||size>32L*1024*1024*1024||!hash.matches("[0-9a-f]{64}")||!Set.of("true","false").contains(exec))throw new IOException("Invalid bundle entry");
            total=Math.addExact(total,size);if(total>64L*1024*1024*1024)throw new IOException("Runtime bundle too large");
            String compression=manifest.getProperty(key+"compression","none");
            long storedSize=Long.parseLong(manifest.getProperty(key+"storedSize",""+size));
            String storedHash=manifest.getProperty(key+"storedSha256",hash);
            if(!Set.of("none","xz").contains(compression)||storedSize<0||storedSize>32L*1024*1024*1024||!storedHash.matches("[0-9a-f]{64}"))throw new IOException("Invalid bundle encoding");
            if(compression.equals("xz")&&!manifest.getProperty("schema").equals("2"))throw new IOException("Compressed entry requires schema 2");
            if(compression.equals("none")&&(storedSize!=size||!storedHash.equals(hash)))throw new IOException("Identity encoding metadata mismatch");
            entries.add(new Entry(name,size,hash,exec.equals("true"),compression,storedSize,storedHash));
        }
        try(var channel=FileChannel.open(cache.resolve(platform+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock=null;
            while(lock==null){cancelled.run();try{lock=channel.tryLock();}catch(OverlappingFileLockException ignored){}if(lock==null)try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Bundle install interrupted",e);}}
            try {
                safePath(cache,installed.getFileName().toString());
                if(!Files.exists(installed)) {
                    if(Files.getFileStore(cache).getUsableSpace()<total+256L*1024*1024)throw new IOException("Not enough free space to install embedded Android");
                    Path staging=Files.createTempDirectory(cache,".extracting-");
                    try {
                        for(var entry:entries) {
                            cancelled.run();Path file=safePath(staging,entry.path);Files.createDirectories(file.getParent());
                            try(var raw=resources.apply(prefix+"files/"+entry.path);var output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)) {
                                if(raw==null)throw new IOException("Missing bundled file: "+entry.path);
                                var stored=new StoredInput(raw,entry.storedSize,cancelled);
                                try(var input=entry.compression.equals("xz")?BundleCompression.decode(stored):stored) {
                                    var hash=digest();byte[] buffer=new byte[1024*1024];long size=0;int n;
                                    while((n=input.read(buffer))!=-1){cancelled.run();size+=n;if(size>entry.size)throw new IOException("Bundle file exceeds declared size");hash.update(buffer,0,n);output.write(buffer,0,n);}
                                    if(size!=entry.size||!HexFormat.of().formatHex(hash.digest()).equals(entry.hash))throw new IOException("Bundled file integrity check failed: "+entry.path);
                                    if(stored.count!=entry.storedSize||!HexFormat.of().formatHex(stored.hash.digest()).equals(entry.storedHash))throw new IOException("Compressed bundle integrity check failed: "+entry.path);
                                }
                            }
                            if(entry.executable&&!System.getProperty("os.name").startsWith("Windows")&&!file.toFile().setExecutable(true,true))throw new IOException("Cannot make bundled binary executable");
                        }
                        Files.move(staging,installed,StandardCopyOption.ATOMIC_MOVE);
                    }finally{if(Files.exists(staging))try(var paths=Files.walk(staging)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
                }else for(var entry:entries) {
                    cancelled.run();Path file=safePath(installed,entry.path);
                    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=entry.size||!hash(file,cancelled).equals(entry.hash)||entry.executable&&!System.getProperty("os.name").startsWith("Windows")&&!Files.isExecutable(file))throw new IOException("Installed runtime integrity check failed; preserve devices and remove only bundle cache: "+file);
                }
            }finally{lock.release();}
        }
        var config=new Properties();
        var paths=Set.of("qemu","ffmpeg","firmware","firmwareVars","disk","dataDisk","kernel","initrd","angle","qemuData");
        for(String key:manifest.stringPropertyNames())if(key.startsWith("config.")) {
            String setting=key.substring(7),value=manifest.getProperty(key);
            config.setProperty(setting,paths.contains(setting)?safePath(installed,value).toString():value);
        }
        config.setProperty("root",installed.toString());return config;
    }
}
