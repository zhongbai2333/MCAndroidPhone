import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
class VerifyImageCompression {
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("MOD.jar ENCODED.xz|zst RAW_BYTES RAW_SHA256");
        long expected=Long.parseLong(args[2]),count=0,start=System.nanoTime();var hash=MessageDigest.getInstance("SHA-256");
        try(var loader=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ClassLoader.getPlatformClassLoader());var raw=Files.newInputStream(Path.of(args[1]));
            var input=(InputStream)loader.loadClass("com.zhongbai233.mcandroidphone.core.BundleCompression").getMethod("decode",String.class,InputStream.class).invoke(null,args[1].endsWith(".zst")?"zstd":"xz",raw)) {
            byte[] b=new byte[1024*1024];int n;while((n=input.read(b))!=-1){count+=n;if(count>expected)throw new IOException("Decoded size exceeds expected");hash.update(b,0,n);}
        }
        String actual=HexFormat.of().formatHex(hash.digest());if(count!=expected||!actual.equals(args[3]))throw new IOException("Decoded image does not match expected");
        System.out.println("IMAGE_CODEC_OK bytes="+count+" sha256="+actual+" decodeAndHashMs="+(System.nanoTime()-start)/1000000);
    }
}
