package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Private, pinned pure-Java codec. Its packages never join Minecraft's module/class path. */
public final class BundleCompression {
    private static final String RESOURCE="/mcandroidphone/runtime/xz-codec.jar";
    private static final String SHA256="3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b";
    private static volatile ClassLoader codec;
    private BundleCompression(){}
    private static synchronized ClassLoader codec()throws IOException {
        if(codec!=null)return codec;
        byte[] jar;
        try(var in=BundleCompression.class.getResourceAsStream(RESOURCE)) {
            if(in==null)throw new IOException("Missing embedded XZ decoder");
            jar=in.readNBytes(1024*1024+1);
        }
        if(jar.length>1024*1024||!RuntimeBundle.sha256(jar).equals(SHA256))throw new IOException("Embedded XZ decoder integrity failure");
        var versions=new TreeMap<Integer,Map<String,byte[]>>();int total=0;
        try(var zip=new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry entry;
            while((entry=zip.getNextJarEntry())!=null) {
                String name=entry.getName();int version=0;
                if(name.startsWith("META-INF/versions/")) {
                    String[] parts=name.split("/",4);if(parts.length!=4)continue;
                    version=Integer.parseInt(parts[2]);name=parts[3];
                    if(version>Runtime.version().feature())continue;
                }
                if(!name.startsWith("org/tukaani/xz/")||!name.endsWith(".class"))continue;
                byte[] bytes=zip.readNBytes(1024*1024+1);total+=bytes.length;
                if(bytes.length>1024*1024||total>4*1024*1024)throw new IOException("Invalid XZ codec archive");
                versions.computeIfAbsent(version,k->new HashMap<>()).put(name.substring(0,name.length()-6).replace('/','.'),bytes);
            }
        }
        var classes=new HashMap<String,byte[]>();versions.values().forEach(classes::putAll);
        codec=new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
                byte[] bytes=classes.get(name);if(bytes==null)throw new ClassNotFoundException(name);
                return defineClass(name,bytes,0,bytes.length);
            }
        };
        return codec;
    }
    /** Memory is capped at 64 MiB even if the compressed stream requests a larger dictionary. */
    public static InputStream decode(InputStream input)throws IOException {
        try{return (InputStream)codec().loadClass("org.tukaani.xz.XZInputStream")
                .getConstructor(InputStream.class,int.class).newInstance(input,64*1024);}
        catch(ReflectiveOperationException e){throw failure(e);}
    }
    /** Build/test convenience; encoding is not part of normal game startup. */
    public static OutputStream encode(OutputStream output)throws IOException {
        try {
            var loader=codec();Object options=loader.loadClass("org.tukaani.xz.LZMA2Options").getConstructor(int.class).newInstance(6);
            return (OutputStream)loader.loadClass("org.tukaani.xz.XZOutputStream")
                    .getConstructor(OutputStream.class,loader.loadClass("org.tukaani.xz.FilterOptions")).newInstance(output,options);
        }catch(ReflectiveOperationException e){throw failure(e);}
    }
    private static IOException failure(ReflectiveOperationException e) {
        Throwable cause=e instanceof InvocationTargetException invocation?invocation.getCause():e;
        return cause instanceof IOException io?io:new IOException("Cannot initialize embedded XZ codec",cause);
    }
}
