import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Developer-only XZ preparation. PackageRuntime verifies every decoded file against staging. */
class CompressRuntime {
    public static void main(String[] args)throws Exception {
        if(args.length<4||args.length>5||args.length==5&&!Set.of("--arm64-image","--arm64-image-compact").contains(args[4]))
            throw new IllegalArgumentException("java scripts/CompressRuntime.java STAGE PLATFORM NEW_XZ_DIR XZ_EXECUTABLE [--arm64-image | --arm64-image-compact]");
        if(!Set.of("macos-arm64","macos-amd64","linux-arm64","linux-amd64","windows-arm64","windows-amd64").contains(args[1]))
            throw new IllegalArgumentException("Unsupported platform");
        boolean arm64=args[1].endsWith("-arm64"),imageFilter=args.length==5;
        boolean compactImage=imageFilter&&args[4].equals("--arm64-image-compact");
        if(imageFilter&&!arm64)throw new IllegalArgumentException("ARM64 image filter requires an ARM64 platform");
        Path stage=Path.of(args[0]).toRealPath(),output=Path.of(args[2]).toAbsolutePath(),xz=Path.of(args[3]).toRealPath();
        var config=new Properties();try(var in=Files.newInputStream(stage.resolve("runtime.properties"))){config.load(in);}
        if(imageFilter&&!config.getProperty("guestArch","").equals("arm64"))
            throw new IllegalArgumentException("ARM64 image filter requires guestArch=arm64 in staging");
        Path systemDisk=Path.of(config.getProperty("disk","images/disk-vda.qcow2"));
        // Output must not become part of staging or replace an earlier compression run.
        Path outputParent=output.getParent().toRealPath();output=outputParent.resolve(output.getFileName());
        if(output.startsWith(stage))throw new IllegalArgumentException("XZ output must be outside staging");
        Files.createDirectory(output);
        int files=0;
        try(var paths=Files.walk(stage)) {
            for(Path source:paths.filter(Files::isRegularFile).sorted().toList()) {
                if(Files.size(source)<256*1024)continue;
                Path relative=stage.relativize(source);
                // Runtime platform labels alone do not identify a guest image's instruction set.
                // Whole-image BCJ is opt-in and must be benchmarked; native ELF/PE/Mach-O is known here.
                String first=relative.getName(0).toString();
                boolean nativeCode=Set.of("bin","lib").contains(first);
                boolean bcj=arm64&&(nativeCode||imageFilter&&relative.equals(systemDisk));
                Path target=output.resolve(relative+".xz"),partial=output.resolve(relative+".xz.partial");
                Files.createDirectories(target.getParent());
                var command=new ArrayList<String>(List.of(xz.toString(),"-T2"));
                if(bcj)command.add("--arm64");
                String lzma=compactImage&&relative.equals(systemDisk)?"--lzma2=preset=6,dict=48MiB,lc=2,lp=2":"--lzma2=preset=6,dict=32MiB";
                command.addAll(List.of(lzma,"-c",source.toString()));
                var process=new ProcessBuilder(command).redirectOutput(partial.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                try {
                    if(process.waitFor()!=0)throw new IOException("XZ failed for "+relative+"; incomplete output retained as .partial");
                } finally {if(process.isAlive())process.destroyForcibly();}
                Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE);
                System.out.println(relative+" "+Files.size(source)+" -> "+Files.size(target)+" bytes; arm64Filter="+bcj+" "+lzma);files++;
            }
        }
        if(files==0)throw new IOException("No eligible files in staging");
        System.out.println("COMPRESSION_PREPARED files="+files+"; validate with PackageRuntime --xz-dir");
    }
}
