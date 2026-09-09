import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Builds one of six self-contained mod JARs from an explicitly staged runtime. Java 25, build-time only. */
class PackageRuntime {
    private record Encoded(Path source,long size,String hash,long crc){}
    private static Encoded encoded(Path base,Path source,long expectedSize,String expectedHash)throws Exception {
        var checksum=new CRC32();var digest=MessageDigest.getInstance("SHA-256");
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{base.toUri().toURL()},ClassLoader.getPlatformClassLoader());
            var raw=new DigestInputStream(new CheckedInputStream(Files.newInputStream(source),checksum),digest)) {
            var method=loader.loadClass("com.zhongbai233.mcandroidphone.core.BundleCompression").getMethod("decode",InputStream.class);
            try(var input=(InputStream)method.invoke(null,raw)) {
                var decoded=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[1024*1024];long count=0;int n;
                while((n=input.read(buffer))!=-1){count+=n;if(count>expectedSize)throw new IOException("XZ exceeds staged file size");decoded.update(buffer,0,n);}
                if(count!=expectedSize||!HexFormat.of().formatHex(decoded.digest()).equals(expectedHash))throw new IOException("XZ does not match staged original: "+source);
            }
        }
        return new Encoded(source,Files.size(source),HexFormat.of().formatHex(digest.digest()),checksum.getValue());
    }
    static String hash(Path p)throws Exception {var h=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)h.update(b,0,n);}return HexFormat.of().formatHex(h.digest());}
    public static void main(String[] args)throws Exception {
        if(args.length!=4&&(args.length!=6||!args[4].equals("--xz-dir")))throw new IllegalArgumentException("base.jar stage-directory platform output.jar [--xz-dir precompressed-directory]");
        Path base=Path.of(args[0]).toRealPath(),stage=Path.of(args[1]).toRealPath(),out=Path.of(args[3]).toAbsolutePath();String platform=args[2];
        if(!platform.matches("(windows|linux|macos)-(amd64|arm64)"))throw new IllegalArgumentException("Invalid platform");
        if(out.startsWith(stage))throw new IllegalArgumentException("Output cannot be inside stage directory");
        var config=new Properties();try(var in=Files.newInputStream(stage.resolve("runtime.properties"))){config.load(in);}
        if(!config.getProperty("guestArch","").equals(platform.substring(platform.indexOf('-')+1)))throw new IllegalArgumentException("Image architecture differs from package");
        for(String key:List.of("qemu","ffmpeg","disk","dataDisk","firmware","firmwareVars")) {
            String name=config.getProperty(key,"");Path p=stage.resolve(name).normalize();
            if(name.isEmpty()||Path.of(name).isAbsolute()||!p.startsWith(stage)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing relative staged "+key);
        }
        Path compressed=args.length==6?Path.of(args[5]).toRealPath():null;
        var encodedFiles=new HashMap<Path,Encoded>();
        List<Path> files;try(var paths=Files.walk(stage)){files=paths.filter(p->!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList();}
        var manifest=new Properties();manifest.setProperty("schema","1");manifest.setProperty("platform",platform);manifest.setProperty("files",""+files.size());
        for(String key:config.stringPropertyNames())manifest.setProperty("config."+key,config.getProperty(key));
        int i=0;for(Path p:files) {
            if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Staged symlink or special file: "+p);
            String name=stage.relativize(p).toString().replace('\\','/');if(!name.matches("[A-Za-z0-9_.,+/@-]+"))throw new IOException("Unsupported bundled filename: "+name);
            String k="file."+(i++)+".";manifest.setProperty(k+"path",name);manifest.setProperty(k+"size",""+Files.size(p));manifest.setProperty(k+"sha256",hash(p));manifest.setProperty(k+"executable",""+Files.isExecutable(p));
            if(compressed!=null) {
                Path candidate=compressed.resolve(name+".xz");
                if(Files.exists(candidate)) {
                    if(!candidate.toRealPath().startsWith(compressed)||!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid precompressed file");
                    Encoded encoded=encoded(base,candidate,Files.size(p),manifest.getProperty(k+"sha256"));encodedFiles.put(p,encoded);
                    manifest.setProperty(k+"compression","xz");manifest.setProperty(k+"storedSize",""+encoded.size);manifest.setProperty(k+"storedSha256",encoded.hash);
                }
            }
        }
        if(compressed!=null&&encodedFiles.isEmpty())throw new IOException("No matching precompressed files");
        if(!encodedFiles.isEmpty())manifest.setProperty("schema","2");
        Files.createDirectories(out.getParent());Path tmp=Files.createTempFile(out.getParent(),".package-",".jar");String prefix="mcandroidphone/bundle/"+platform+"/";
        try {
            try(var zip=new ZipOutputStream(Files.newOutputStream(tmp));var original=new ZipFile(base.toFile())) {
                zip.setLevel(4);
                for(var e:original.stream().sorted(Comparator.comparing(ZipEntry::getName)).toList()){if(e.getName().startsWith("mcandroidphone/bundle/"))throw new IOException("Base JAR already has a runtime");zip.putNextEntry(BundleMetadata.entry(e.getName()));if(!e.isDirectory())try(var in=original.getInputStream(e)){in.transferTo(zip);}zip.closeEntry();}
                zip.putNextEntry(BundleMetadata.entry(prefix+"manifest.properties"));BundleMetadata.writeProperties(manifest,zip);zip.closeEntry();
                for(Path p:files) {
                    var entry=BundleMetadata.entry(prefix+"files/"+stage.relativize(p).toString().replace('\\','/'));var encoded=encodedFiles.get(p);
                    if(encoded!=null){entry.setMethod(ZipEntry.STORED);entry.setSize(encoded.size);entry.setCompressedSize(encoded.size);entry.setCrc(encoded.crc);}
                    zip.putNextEntry(entry);Files.copy(encoded==null?p:encoded.source,zip);zip.closeEntry();
                }
            }
            Files.move(tmp,out,StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(out.resolveSibling(out.getFileName()+".sha256"),hash(out)+"  "+out.getFileName()+"\n",StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_PACKAGE_OK "+out+" bytes="+Files.size(out)+" files="+files.size());
        }finally{Files.deleteIfExists(tmp);}
    }
}
