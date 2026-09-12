import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Combine verified platform packages while requiring exactly identical Mod code/resources. */
class MergeRuntimePackages {
    record Source(ZipFile zip,ZipEntry entry){}
    static boolean runtime(String name){return name.startsWith("mcandroidphone/bundle/")||name.startsWith("mcandroidphone/images/");}
    static String hash(Source source)throws Exception {var h=MessageDigest.getInstance("SHA-256");try(var in=source.zip.getInputStream(source.entry)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)h.update(b,0,n);}return HexFormat.of().formatHex(h.digest());}
    public static void main(String[] args)throws Exception {
        if(args.length<3)throw new IllegalArgumentException("NEW_UNIVERSAL.jar PLATFORM_A.jar PLATFORM_B.jar [...]");
        Path output=Path.of(args[0]).toAbsolutePath();if(Files.exists(output))throw new IOException("Output already exists");
        var opened=new ArrayList<ZipFile>();Path temp=null;
        try {
            var entries=new TreeMap<String,Source>();Set<String> core=null;var platforms=new TreeSet<String>();
            for(int i=1;i<args.length;i++) {
                var zip=new ZipFile(Path.of(args[i]).toFile());opened.add(zip);var seen=new HashSet<String>();var thisCore=new TreeSet<String>();int nativeCount=0;
                for(var e:zip.stream().toList()) {
                    String name=e.getName();if(!seen.add(name))throw new IOException("Duplicate input ZIP entry");
                    if(!runtime(name))thisCore.add(name);
                    if(name.matches("mcandroidphone/bundle/(windows|macos|linux)-(amd64|arm64)/manifest.properties")) {
                        String platform=name.split("/")[2];if(!platforms.add(platform))throw new IOException("Duplicate native platform: "+platform);nativeCount++;
                    }
                    var source=new Source(zip,e);var previous=entries.putIfAbsent(name,source);
                    if(previous!=null&&(e.getSize()!=previous.entry.getSize()||!hash(source).equals(hash(previous))))throw new IOException("Packages disagree on shared Mod/image resource: "+name);
                }
                if(nativeCount==0)throw new IOException("Input has no platform runtime");
                if(core==null)core=thisCore;else if(!core.equals(thisCore))throw new IOException("Packages were built from different Mod resource sets");
            }
            Files.createDirectories(output.getParent());temp=Files.createTempFile(output.getParent(),".universal-",".jar");
            try(var out=new ZipOutputStream(Files.newOutputStream(temp))) {
                out.setLevel(4);
                for(var item:entries.entrySet()) {
                    var source=item.getValue();var e=BundleMetadata.entry(item.getKey());
                    if(source.entry.getMethod()==ZipEntry.STORED){e.setMethod(ZipEntry.STORED);e.setSize(source.entry.getSize());e.setCompressedSize(source.entry.getSize());e.setCrc(source.entry.getCrc());}
                    out.putNextEntry(e);if(!source.entry.isDirectory())try(var in=source.zip.getInputStream(source.entry)){in.transferTo(out);}out.closeEntry();
                }
            }
            Files.move(temp,output,StandardCopyOption.ATOMIC_MOVE);Files.writeString(output.resolveSibling(output.getFileName()+".sha256"),PackageRuntime.hash(output)+"  "+output.getFileName()+"\n",StandardOpenOption.CREATE_NEW);
            System.out.println("UNIVERSAL_RUNTIME_PACKAGE_OK platforms="+platforms+" bytes="+Files.size(output));
        }finally{for(var zip:opened)zip.close();if(temp!=null)Files.deleteIfExists(temp);}
    }
}
