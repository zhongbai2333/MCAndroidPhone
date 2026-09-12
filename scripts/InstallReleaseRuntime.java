import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Developer bootstrap: reuse a release's native runtime/images with source runClient builds. */
class InstallReleaseRuntime {
    static Object invoke(Class<?> type,String name,Class<?>[] signature,Object... args)throws Exception {
        var method=type.getDeclaredMethod(name,signature);method.setAccessible(true);
        try{return method.invoke(null,args);}catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof Exception ex)throw ex;throw e;}
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("PLATFORM_RELEASE.jar CACHE_GAME_DIRECTORY NEW_RUNTIME.properties");
        Path jar=Path.of(args[0]).toRealPath(),cache=Path.of(args[1]).toAbsolutePath(),out=Path.of(args[2]).toAbsolutePath();
        if(Files.exists(out))throw new IOException("Choose a new config path; existing configuration will not be replaced");
        Runnable cancelled=()->{if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();};
        try(var loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            Function<String,InputStream> resources=name->loader.getResourceAsStream(name.startsWith("/")?name.substring(1):name);
            Properties config=(Properties)invoke(loader.loadClass("com.zhongbai233.mcandroidphone.core.RuntimeBundle"),"install",new Class[]{Path.class,Function.class,Runnable.class},cache,resources,cancelled);
            if(config.isEmpty())throw new IOException("This release has no native runtime for the current host; choose the matching platform JAR");
            String arch=config.getProperty("guestArch");
            Properties images=(Properties)invoke(loader.loadClass("com.zhongbai233.mcandroidphone.core.RemoteAndroidImages"),"install",new Class[]{Path.class,String.class,Function.class,Runnable.class,Consumer.class},cache,arch,resources,cancelled,(Consumer<String>)System.out::println);
            images.remove("root");config.putAll(images);
            if(config.getProperty("disk","").isBlank())throw new IOException("Release contains neither an image nor a download descriptor");
            config.remove("deviceId");config.setProperty("adbPort","0");config.setProperty("storage","persistent");
            Files.createDirectories(out.getParent());try(var stream=Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)){BundleMetadata.writeProperties(config,stream);}
            System.out.println("RELEASE_RUNTIME_INSTALLED config="+out+"; use test-phone.sh/cmd qemu --runtime-config with this file");
        }
    }
}
