import java.nio.file.*;
import java.util.*;

/** Native XZ fixture round trip and image-architecture guards; developer-only. */
class CompressRuntimeSelfTest {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("CompressRuntimeSelfTest <xz-executable>");
        Path root=Files.createTempDirectory("mcphone-image-xz-"),stage=Files.createDirectory(root.resolve("stage"));
        try {
            Files.createDirectories(stage.resolve("images"));
            byte[] bytes=new byte[1024*1024];new Random(42).nextBytes(bytes);
            for(int i=0;i<bytes.length-8;i+=16){bytes[i]=(byte)0xe8;bytes[i+1]=(byte)i;bytes[i+2]=(byte)(i>>8);bytes[i+3]=0;bytes[i+4]=0;}
            Files.write(stage.resolve("images/system.qcow2"),bytes);
            Path config=stage.resolve("runtime.properties");
            Files.writeString(config,"guestArch=amd64\ndisk=images/system.qcow2\n");
            Path encoded=root.resolve("encoded");
            CompressRuntime.main(new String[]{stage.toString(),"windows-amd64",encoded.toString(),args[0],"--amd64-image-compact"});
            Path decoded=root.resolve("decoded");
            int result=new ProcessBuilder(args[0],"-dc",encoded.resolve("images/system.qcow2.xz").toString()).inheritIO().redirectOutput(decoded.toFile()).start().waitFor();
            if(result!=0||!Arrays.equals(bytes,Files.readAllBytes(decoded)))throw new AssertionError("AMD64 XZ round trip changed content");
            for(String[] invalid:new String[][]{{"macos-arm64","--amd64-image-compact"},{"windows-amd64","--arm64-image"}}) {
                try{CompressRuntime.main(new String[]{stage.toString(),invalid[0],root.resolve("rejected").toString(),args[0],invalid[1]});throw new AssertionError("Wrong platform accepted");}catch(IllegalArgumentException expected){}
            }
            Files.writeString(config,"guestArch=arm64\ndisk=images/system.qcow2\n");
            try{CompressRuntime.main(new String[]{stage.toString(),"windows-amd64",root.resolve("rejected").toString(),args[0],"--amd64-image-compact"});throw new AssertionError("Wrong guest accepted");}catch(IllegalArgumentException expected){}
            if(Files.exists(root.resolve("rejected")))throw new AssertionError("Rejected request created output");
            System.out.println("COMPRESS_RUNTIME_OK AMD64 native XZ round trip; platform and guest architecture guards");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
