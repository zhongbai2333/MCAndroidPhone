import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Read-only package size report; optional maximum MiB makes oversized artifacts fail validation. */
class InspectBundle {
    public static void main(String[] args)throws Exception {
        if(args.length<1||args.length>2)throw new IllegalArgumentException("java scripts/InspectBundle.java runtime.jar [maximum-MiB]");
        Path jar=Path.of(args[0]).toRealPath();long limit=args.length==2?Math.multiplyExact(Long.parseLong(args[1]),1024L*1024):-1;
        if(args.length==2&&(limit<=0||limit>64L*1024*1024*1024))throw new IllegalArgumentException("Invalid size limit");
        long total=Files.size(jar);System.out.printf(Locale.ROOT,"Package: %s%nBytes: %d (%.2f MiB)%n",jar,total,total/1048576.0);
        try(var zip=new ZipFile(jar.toFile())) {
            var entries=zip.stream().filter(e->!e.isDirectory()).sorted(Comparator.comparingLong(ZipEntry::getCompressedSize).reversed()).toList();
            System.out.println("Largest ZIP entries: compressed bytes / ZIP payload bytes / percentage / path (XZ payload bytes are not installed image size)");
            entries.stream().limit(15).forEach(e->System.out.printf(Locale.ROOT,"%12d %12d %6.2f%% %s%n",e.getCompressedSize(),e.getSize(),100.0*e.getCompressedSize()/total,e.getName()));
            for(var metadata:entries.stream().filter(e->e.getName().endsWith("/manifest.properties")&&e.getName().startsWith("mcandroidphone/bundle/")).toList()) {
                var manifest=new Properties();try(var in=zip.getInputStream(metadata)){manifest.load(in);}
                long installed=0;int compressed=0;for(int i=0;i<Integer.parseInt(manifest.getProperty("files"));i++){installed+=Long.parseLong(manifest.getProperty("file."+i+".size"));if(manifest.getProperty("file."+i+".compression","none").equals("xz"))compressed++;}
                System.out.printf(Locale.ROOT,"Installed runtime files: %.2f MiB; XZ files: %d%n",installed/1048576.0,compressed);
            }
            long images=entries.stream().filter(e->e.getName().contains("/files/images/")).mapToLong(ZipEntry::getCompressedSize).sum();
            System.out.printf(Locale.ROOT,"Image contribution: %.2f MiB (%.2f%%)%n",images/1048576.0,100.0*images/total);
        }
        if(limit!=-1){System.out.printf("Budget: %d bytes; result=%s%n",limit,total<=limit?"PASS":"FAIL");if(total>limit)throw new IllegalStateException("Package exceeds size budget by "+(total-limit)+" bytes");}
    }
}
