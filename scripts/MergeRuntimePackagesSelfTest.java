import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
class MergeRuntimePackagesSelfTest {
    static void make(Path path,String platform,String code)throws Exception {
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))) {
            for(var e:Map.of("Mod.class",code,"mcandroidphone/bundle/"+platform+"/manifest.properties",platform,"mcandroidphone/images/"+platform.substring(platform.indexOf('-')+1)+".properties","image fixture").entrySet()) {
                zip.putNextEntry(BundleMetadata.entry(e.getKey()));zip.write(e.getValue().getBytes());zip.closeEntry();
            }
        }
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-universal-");
        try {
            Path a=root.resolve("a.jar"),b=root.resolve("b.jar"),out=root.resolve("merged.jar");make(a,"windows-amd64","same code");make(b,"macos-arm64","same code");
            MergeRuntimePackages.main(new String[]{out.toString(),a.toString(),b.toString()});
            try(var zip=new ZipFile(out.toFile())){if(zip.size()!=5||zip.getEntry("mcandroidphone/images/arm64.properties")==null||zip.getEntry("mcandroidphone/images/amd64.properties")==null)throw new AssertionError("Universal resources missing or Mod duplicated");}
            make(b,"macos-arm64","different code");try{MergeRuntimePackages.main(new String[]{root.resolve("bad.jar").toString(),a.toString(),b.toString()});throw new AssertionError("Mixed Mod versions accepted");}catch(IOException expected){}
            if(Files.exists(root.resolve("bad.jar")))throw new AssertionError("Invalid universal package published");
            System.out.println("UNIVERSAL_PACKAGE_OK shared code once, two architecture descriptors, mixed code rejected before publish");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
