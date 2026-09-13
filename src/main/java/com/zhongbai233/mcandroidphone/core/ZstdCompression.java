package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.jar.*;

/** Pinned private JNI codec; no shared mod packages and a bounded 128 MiB decode window. */
public final class ZstdCompression {
    private static volatile ClassLoader codec;
    private ZstdCompression(){}
    private static synchronized ClassLoader codec()throws IOException {
        if(codec!=null)return codec;
        if(!Set.of("windows-amd64","windows-arm64","macos-amd64","macos-arm64","linux-amd64","linux-arm64").contains(RuntimeBundle.platform()))throw new IOException("Unsupported zstd host");
        byte[] jar;
        try(var in=ZstdCompression.class.getResourceAsStream("/mcandroidphone/runtime/zstd-codec.jar")) {
            if(in==null)throw new IOException("Missing embedded zstd decoder");jar=in.readNBytes(16*1024*1024+1);
        }
        if(jar.length>16*1024*1024||!RuntimeBundle.sha256(jar).equals("d84360d39f57a40dc1e26ea2d4bb66da0cb88057ba117edae67431a719057edc"))throw new IOException("Embedded zstd decoder integrity failure");
        var entries=new HashMap<String,byte[]>();int total=0;
        try(var zip=new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry entry;
            while((entry=zip.getNextJarEntry())!=null) {
                if(entry.isDirectory())continue;
                byte[] bytes=zip.readNBytes(8*1024*1024+1);total+=bytes.length;
                if(bytes.length>8*1024*1024||total>64*1024*1024)throw new IOException("Invalid zstd codec archive");
                entries.put(entry.getName(),bytes);
            }
        }
        codec=new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
                if(!name.startsWith("com.github.luben.zstd."))throw new ClassNotFoundException(name);
                byte[] bytes=entries.get(name.replace('.','/')+".class");if(bytes==null)throw new ClassNotFoundException(name);
                return defineClass(name,bytes,0,bytes.length);
            }
            @Override public InputStream getResourceAsStream(String name) {
                byte[] bytes=entries.get(name);return bytes==null?null:new ByteArrayInputStream(bytes);
            }
        };
        return codec;
    }
    public static InputStream decode(InputStream input)throws IOException {
        InputStream stream=null;
        try {
            var type=codec().loadClass("com.github.luben.zstd.ZstdInputStreamNoFinalizer");
            stream=(InputStream)type.getConstructor(InputStream.class).newInstance(input);
            type.getMethod("setLongMax",int.class).invoke(stream,27);return stream;
        }catch(ReflectiveOperationException|LinkageError e){if(stream!=null)stream.close();throw failure(e);}
    }
    /** Tests/build tools only. */
    public static OutputStream encode(OutputStream output)throws IOException {
        try{return (OutputStream)codec().loadClass("com.github.luben.zstd.ZstdOutputStreamNoFinalizer").getConstructor(OutputStream.class,int.class).newInstance(output,3);}
        catch(ReflectiveOperationException|LinkageError e){throw failure(e);}
    }
    private static IOException failure(Throwable e) {
        Throwable cause=e instanceof InvocationTargetException invocation?invocation.getCause():e;
        return cause instanceof IOException io?io:new IOException("Cannot initialize embedded zstd codec",cause);
    }
}
