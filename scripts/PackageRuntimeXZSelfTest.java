import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Test the shipped codec, precompressed-file validation and deterministic STORED entries. */
class PackageRuntimeXZSelfTest {
    public static void main(String[] args)throws Exception {
        Path base=Path.of(args[0]).toRealPath(),temp=Files.createTempDirectory("mcphone-xz-package-");
        try {
            Path stage=Files.createDirectory(temp.resolve("stage")),compressed=Files.createDirectory(temp.resolve("xz"));
            var config=new Properties();config.setProperty("guestArch","arm64");
            for(String key:List.of("qemu","ffmpeg","disk","dataDisk","firmware","firmwareVars")){config.setProperty(key,key);Files.writeString(stage.resolve(key),"native fixture "+key);}
            Files.write(stage.resolve("disk"),"compressible disk fixture\n".repeat(4096).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try(var out=Files.newOutputStream(stage.resolve("runtime.properties"))){BundleMetadata.writeProperties(config,out);}
            try(var loader=new java.net.URLClassLoader(new java.net.URL[]{base.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
                var encode=loader.loadClass("com.zhongbai233.mcandroidphone.core.BundleCompression").getMethod("encode",OutputStream.class);
                try(var raw=Files.newOutputStream(compressed.resolve("disk.xz"));var out=(OutputStream)encode.invoke(null,raw)){Files.copy(stage.resolve("disk"),out);}
            }
            Path a=temp.resolve("a.jar"),b=temp.resolve("b.jar");
            for(Path out:List.of(a,b))PackageRuntime.main(new String[]{base.toString(),stage.toString(),"macos-arm64",out.toString(),"--xz-dir",compressed.toString()});
            if(Files.mismatch(a,b)!=-1)throw new AssertionError("Compressed package not deterministic");
            try(var zip=new ZipFile(a.toFile())) {
                var e=zip.getEntry("mcandroidphone/bundle/macos-arm64/files/disk");
                if(e.getMethod()!=ZipEntry.STORED||e.getSize()>=Files.size(stage.resolve("disk")))throw new AssertionError("XZ was deflated again or not compressed");
            }
            Files.writeString(stage.resolve("disk"),"changed original");
            try{PackageRuntime.main(new String[]{base.toString(),stage.toString(),"macos-arm64",temp.resolve("bad.jar").toString(),"--xz-dir",compressed.toString()});throw new AssertionError("Wrong precompressed image accepted");}
            catch(IOException expected){}
            if(Files.exists(temp.resolve("bad.jar")))throw new AssertionError("Invalid package published");
            System.out.println("XZ_PACKAGE_OK deterministic bytes, STORED entry, mismatched image rejected");
        } finally {try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
