import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Copies and relocates a local Homebrew runtime, leaving installed binaries untouched. Build-time only. */
class StageMacRuntime {
    static String run(String... args)throws Exception {
        var p=new ProcessBuilder(args).redirectErrorStream(true).start();String text=new String(p.getInputStream().readAllBytes());
        if(p.waitFor()!=0)throw new IOException(Arrays.toString(args)+"\n"+text);return text;
    }
    static List<String> dependencies(Path binary)throws Exception {
        return run("/usr/bin/otool","-L",binary.toString()).lines().skip(1).map(String::trim).filter(s->s.contains(" (compatibility")).map(s->s.substring(0,s.indexOf(" (compatibility"))).toList();
    }
    static boolean system(String path){return path.startsWith("/usr/lib/")||path.startsWith("/System/Library/");}
    static void copyTree(Path source,Path target)throws IOException {
        try(var paths=Files.walk(source.toRealPath())){for(Path p:paths.toList()){Path to=target.resolve(source.toRealPath().relativize(p));if(Files.isDirectory(p))Files.createDirectories(to);else {Files.createDirectories(to.getParent());Files.copy(p,to);}}}
    }
    /** The ARM64 virt machine uses explicit pflash images; other machines' BIOS/DTBs are unused. */
    static void copyQemuData(Path source,Path target,Properties config)throws IOException {
        if(!config.getProperty("guestArch","").equals("arm64")||config.getProperty("firmware","").isBlank()) {
            copyTree(source,target);return;
        }
        Files.createDirectories(target);
        try(var paths=Files.walk(source.toRealPath())) {
            for(Path p:paths.filter(Files::isRegularFile).sorted().toList()) {
                Path relative=source.toRealPath().relativize(p);String name=relative.toString();
                // Keep option ROMs and keyboard maps for the supported network/video/input choices.
                if(name.startsWith("keymaps/")||name.endsWith(".rom")||name.startsWith("vgabios")||name.equals("edk2-licenses.txt")) {
                    Path to=target.resolve(relative);Files.createDirectories(to.getParent());Files.copy(p,to);
                }
            }
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!System.getProperty("os.name").startsWith("Mac"))throw new IllegalArgumentException("java StageMacRuntime.java local-runtime.properties new-stage-directory (macOS only)");
        var config=new Properties();try(var in=Files.newBufferedReader(Path.of(args[0]))){config.load(in);}
        Path stage=Path.of(args[1]).toAbsolutePath();Files.createDirectory(stage);Files.createDirectories(stage.resolve("bin"));Files.createDirectories(stage.resolve("lib"));
        var mapped=new LinkedHashMap<Path,Path>();var names=new HashMap<String,Path>();var queue=new ArrayDeque<Path>();
        for(String key:List.of("qemu","ffmpeg")){Path p=Path.of(config.getProperty(key)).toRealPath();Path to=stage.resolve("bin/"+p.getFileName());mapped.put(p,to);queue.add(p);config.setProperty(key,"bin/"+p.getFileName());}
        while(!queue.isEmpty()) {
            Path binary=queue.remove();Files.copy(binary,mapped.get(binary));
            for(String dep:dependencies(binary))if(!system(dep)) {
                if(!dep.startsWith("/"))throw new IOException("Unresolved source dependency: "+dep);
                Path actual=Path.of(dep).toRealPath();if(actual.equals(binary)||mapped.containsKey(actual))continue;
                String name=actual.getFileName().toString();Path collision=names.putIfAbsent(name,actual);if(collision!=null&&!collision.equals(actual))throw new IOException("Library name collision: "+name);
                mapped.put(actual,stage.resolve("lib/"+name));queue.add(actual);
            }
        }
        for(var entry:mapped.entrySet()) {
            Path original=entry.getKey(),copy=entry.getValue();
            if(copy.getParent().equals(stage.resolve("lib")))run("/usr/bin/install_name_tool","-id","@loader_path/"+copy.getFileName(),copy.toString());
            for(String dep:dependencies(original))if(!system(dep)) {
                Path actual=Path.of(dep).toRealPath();if(actual.equals(original))continue;
                Path target=mapped.get(actual);if(target==null)throw new IOException("Unresolved dependency: "+dep);
                run("/usr/bin/install_name_tool","-change",dep,"@loader_path/"+copy.getParent().relativize(target),copy.toString());
            }
        }
        Path entitlement=stage.resolve("hypervisor.plist");Files.writeString(entitlement,"<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>com.apple.security.hypervisor</key><true/></dict></plist>");
        for(Path copy:mapped.values()) {
            if(copy.getFileName().toString().startsWith("qemu-system-"))run("/usr/bin/codesign","--force","--sign","-","--entitlements",entitlement.toString(),copy.toString());
            else run("/usr/bin/codesign","--force","--sign","-",copy.toString());
            run("/usr/bin/codesign","--verify",copy.toString());
            for(String dep:dependencies(copy))if(!system(dep)&&!dep.startsWith("@loader_path/"))throw new IOException("Nonportable dependency remains: "+dep);
        }
        Path root=Path.of(config.getProperty("root","."));
        Files.createDirectories(stage.resolve("images"));
        for(String key:List.of("disk","dataDisk","firmwareVars","firmware")) {
            Path source=Path.of(config.getProperty(key));if(!source.isAbsolute())source=root.resolve(source);
            String name="images/"+key+"-"+source.getFileName();Files.copy(source,stage.resolve(name));config.setProperty(key,name);
        }
        copyQemuData(Path.of("/opt/homebrew/share/qemu"),stage.resolve("share/qemu"),config);config.setProperty("qemuData","share/qemu");
        config.remove("root");config.remove("deviceId");config.setProperty("adbPort","0");config.setProperty("storage","persistent");
        // This local stock image has no production guest environment/camera service yet.
        config.setProperty("environment","false");
        config.setProperty("camera","false");
        try(var out=Files.newOutputStream(stage.resolve("runtime.properties"))){BundleMetadata.writeProperties(config,out);}
        var provenance=new StringBuilder("Local test bundle. Not a published release.\nNative source paths:\n");
        for(Path source:mapped.keySet())provenance.append(source).append('\n');
        Files.writeString(stage.resolve("PROVENANCE.txt"),provenance);
        Path licenses=Files.createDirectories(stage.resolve("licenses"));var copied=new HashSet<Path>();
        for(Path source:mapped.keySet()) {
            Path prefix=source;while(prefix!=null&&!Files.exists(prefix.resolve("INSTALL_RECEIPT.json")))prefix=prefix.getParent();
            if(prefix==null||!copied.add(prefix))continue;
            Path target=Files.createDirectories(licenses.resolve(prefix.getParent().getFileName()+"-"+prefix.getFileName()));
            try(var files=Files.list(prefix)){for(Path p:files.filter(Files::isRegularFile).toList())if(p.getFileName().toString().matches("(?i).*(license|copying|copyright|receipt).*"))Files.copy(p,target.resolve(p.getFileName()));}
        }
        System.out.println("MAC_STAGE_OK "+stage+" nativeFiles="+mapped.size());
        System.out.println(run(stage.resolve(config.getProperty("qemu")).toString(),"--version").lines().findFirst().orElse(""));
        System.out.println(run(stage.resolve(config.getProperty("ffmpeg")).toString(),"-version").lines().findFirst().orElse(""));
    }
}
