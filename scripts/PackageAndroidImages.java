import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** A separate, pinned three-disk download; generated only from clean distribution templates. */
class PackageAndroidImages {
    static String hash(Path path)throws Exception {var h=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)h.update(b,0,n);}return HexFormat.of().formatHex(h.digest());}
    public static void main(String[] args)throws Exception {
        if(args.length!=7)throw new IllegalArgumentException("IMAGES ARCH SYSTEM.qcow2.xz DOWNLOAD_HTTPS_URL NEW.zip NEW.properties BASE_MOD.jar");
        Path images=Path.of(args[0]).toRealPath(),xz=Path.of(args[2]).toRealPath(),out=Path.of(args[4]).toAbsolutePath(),descriptor=Path.of(args[5]).toAbsolutePath();String arch=args[1];
        if(!Set.of("amd64","arm64").contains(arch)||!"https".equals(java.net.URI.create(args[3]).getScheme()))throw new IllegalArgumentException("Invalid architecture/URL");
        if(Files.exists(out)||Files.exists(descriptor)||out.startsWith(images)||descriptor.startsWith(images))throw new IOException("Use new output paths outside the image input directory");
        PackageRuntime.verifyCompressed(Path.of(args[6]).toRealPath(),xz,images.resolve("vda.qcow2"));
        var m=new Properties();m.setProperty("schema","2");m.setProperty("platform","android-"+arch);m.setProperty("files","3");m.setProperty("config.guestArch",arch);
        m.setProperty("config.width","720");m.setProperty("config.height","1280");m.setProperty("config.memory","4096");m.setProperty("config.cpus","4");m.setProperty("config.shutdownMethod","power-key");if(arch.equals("amd64"))m.setProperty("config.cpuModel","SandyBridge");
        String[] names={"vda.qcow2","vdb.qcow2","efi_vars.fd"},roles={"disk","dataDisk","firmwareVars"};var checks=new StringBuilder();
        Files.createDirectories(out.getParent());Path tmp=Files.createTempFile(out.getParent(),".images-",".zip");
        try {
            try(var zip=new ZipOutputStream(Files.newOutputStream(tmp))) {
                zip.setLevel(9);
                for(int i=0;i<3;i++) {
                    Path raw=images.resolve(names[i]),stored=i==0?xz:raw;String key="file."+i+".",entry=i==0?names[i]+".xz":names[i];
                    String rawHash=hash(raw),storedHash=i==0?hash(stored):rawHash;
                    m.setProperty(key+"path",names[i]);m.setProperty(key+"archivePath",entry);m.setProperty(key+"size",""+Files.size(raw));m.setProperty(key+"sha256",rawHash);m.setProperty(key+"executable","false");
                    m.setProperty(key+"compression",i==0?"xz":"none");m.setProperty(key+"storedSize",""+Files.size(stored));m.setProperty(key+"storedSha256",storedHash);
                    m.setProperty("config."+roles[i],names[i]);m.setProperty("config."+roles[i]+"Format","qcow2");
                    ZipEntry z=BundleMetadata.entry(entry);
                    if(i==0){var crc=new CRC32();try(var in=Files.newInputStream(stored)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)crc.update(b,0,n);}z.setMethod(ZipEntry.STORED);z.setSize(Files.size(stored));z.setCompressedSize(Files.size(stored));z.setCrc(crc.getValue());}
                    zip.putNextEntry(z);Files.copy(stored,zip);zip.closeEntry();checks.append(storedHash).append("  ").append(entry).append('\n');if(i==0)checks.append(rawHash).append("  ").append(names[i]).append('\n');
                }
                zip.putNextEntry(BundleMetadata.entry("SHA256SUMS"));zip.write(checks.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                zip.putNextEntry(BundleMetadata.entry("README.txt"));zip.write(("MCAndroidPhone Android Go full / "+arch+". Clean distribution templates.\nWebView retained. First-use download is verified and decoded by the Mod.\nFor manual QEMU use, decode vda.qcow2.xz before booting.\nAlways keep system metadata, user disk and EFI variables paired; never mix old phone data with a fresh system template.\nAndroid and its components retain their respective licenses; the Mod's MIT license does not relicense Android.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
            m.setProperty("download.url",args[3]);m.setProperty("download.bytes",""+Files.size(tmp));m.setProperty("download.sha256",hash(tmp));
            Files.move(tmp,out,StandardCopyOption.ATOMIC_MOVE);
            Files.createDirectories(descriptor.getParent());try(var stream=Files.newOutputStream(descriptor,StandardOpenOption.CREATE_NEW)){BundleMetadata.writeProperties(m,stream);}
            Files.writeString(out.resolveSibling(out.getFileName()+".sha256"),m.getProperty("download.sha256")+"  "+out.getFileName()+"\n",StandardOpenOption.CREATE_NEW);
            System.out.println("ANDROID_IMAGES_PACKAGE_OK arch="+arch+" bytes="+Files.size(out)+" sha256="+m.getProperty("download.sha256"));
        }finally{Files.deleteIfExists(tmp);}
    }
}
