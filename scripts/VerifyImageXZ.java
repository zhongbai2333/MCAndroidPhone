import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Verify/decode a prepared image with the same private Java codec shipped to players. */
class VerifyImageXZ {
    static String hash(Path path)throws Exception {
        var sha=MessageDigest.getInstance("SHA-256");
        try(var in=new DigestInputStream(Files.newInputStream(path),sha)){in.transferTo(OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(sha.digest());
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("VerifyImageXZ <base-mod.jar> <image.xz> <original-image> <new-decoded-image>");
        Path jar=Path.of(args[0]).toRealPath(),encoded=Path.of(args[1]).toRealPath(),original=Path.of(args[2]).toRealPath();
        Path output=Path.of(args[3]).toAbsolutePath();
        if(Files.exists(output))throw new IOException("Output already exists");
        Path partial=output.resolveSibling(output.getFileName()+".partial");
        long expectedSize=Files.size(original);String expectedHash=hash(original);
        long start=System.nanoTime(),count=0;var sha=MessageDigest.getInstance("SHA-256");
        try(var loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader());
            var raw=Files.newInputStream(encoded);
            var input=(InputStream)loader.loadClass("com.zhongbai233.mcandroidphone.core.BundleCompression").getMethod("decode",InputStream.class).invoke(null,raw);
            var out=Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW)) {
            byte[] bytes=new byte[1024*1024];int n;
            while((n=input.read(bytes))!=-1){count+=n;if(count>expectedSize)throw new IOException("Decoded image exceeds original size");sha.update(bytes,0,n);out.write(bytes,0,n);}
        }
        String actualHash=HexFormat.of().formatHex(sha.digest());
        if(count!=expectedSize||!actualHash.equals(expectedHash))throw new IOException("Decoded image differs from original; partial retained");
        Files.move(partial,output);
        System.out.println("COMPRESSED_IMAGE_OK bytes="+count+" sha256="+actualHash+" decodeAndWriteMs="+(System.nanoTime()-start)/1_000_000+" output="+output);
    }
}
