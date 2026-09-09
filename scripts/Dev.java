import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Java 25 development entry point, also usable without Gradle for core/lifecycle tests. */
class Dev {
    static final Path ROOT=Path.of("").toAbsolutePath(),CLASSES=ROOT.resolve("build/portable-classes");
    static final boolean WINDOWS=System.getProperty("os.name").startsWith("Windows");
    static String java(String name){return Path.of(System.getProperty("java.home"),"bin",name+(WINDOWS?".exe":"")).toString();}
    static void run(List<String> args)throws Exception {int exit=new ProcessBuilder(args).directory(ROOT.toFile()).inheritIO().start().waitFor();if(exit!=0)throw new IOException("Command failed ("+exit+"): "+args.getFirst());}
    static void compile()throws Exception {
        Files.createDirectories(CLASSES);var args=new ArrayList<>(List.of(java("javac"),"-encoding","UTF-8","-d",CLASSES.toString()));
        for(String kind:List.of("main","test")) {
            try(var sources=Files.list(ROOT.resolve("src/"+kind+"/java/com/zhongbai233/mcandroidphone/core"))){sources.filter(p->p.toString().endsWith(".java")).sorted().forEach(p->args.add(p.toString()));}
            try(var sources=Files.list(ROOT.resolve("src/"+kind+"/java/com/zhongbai233/mcandroidphone/phone"))){sources.filter(p->Set.of("PhoneGeometry.java","PhonePose.java","PhoneGeometrySelfTest.java").contains(p.getFileName().toString())).sorted().forEach(p->args.add(p.toString()));}
        }
        try(var sources=Files.list(ROOT.resolve("src/main/java/com/zhongbai233/mcandroidphone/environment"))){sources.filter(p->p.toString().endsWith(".java")).sorted().forEach(p->args.add(p.toString()));}
        run(args);Path jar=CLASSES.resolve("mcandroidphone/runtime/native-guard.jar");Files.createDirectories(jar.getParent());
        try(var zip=new ZipOutputStream(Files.newOutputStream(jar));var files=Files.list(CLASSES.resolve("com/zhongbai233/mcandroidphone/core"))) {
            for(var p:files.filter(p->p.getFileName().toString().matches("(NativeGuard|Json)(\\$.*)?\\.class")).toList()){zip.putNextEntry(new ZipEntry(CLASSES.relativize(p).toString().replace('\\','/')));Files.copy(p,zip);zip.closeEntry();}
        }
    }
    public static void main(String[] args)throws Exception {
        String mode=args.length==0?"pattern":args[0];boolean world=false,android=false,environment=false;String warmup="0";var settings=new LinkedHashMap<String,String>();
        for(int i=1;i<args.length;i++)switch(args[i]) {
            case "--world-smoke"->world=true;
            case "--android-smoke"->{world=true;android=true;}
            case "--environment-smoke"->{world=true;environment=true;settings.put("environment","true");}
            case "--warmup"->warmup=args[++i];
            case "--guest-arch"->settings.put("guestArch",args[++i]);
            case "--gpu","--qemu-gpu"->settings.put("gpu",args[++i]);
            case "--set"->{String value=args[++i];int split=value.indexOf('=');if(split<1)throw new IllegalArgumentException("Expected KEY=VALUE");settings.put(value.substring(0,split),value.substring(split+1));}
            default->throw new IllegalArgumentException("Unknown option: "+args[i]);
        }
        if(android&&!mode.equals("qemu"))throw new IllegalArgumentException("--android-smoke requires qemu and an initialized Android image");
        if(mode.equals("check")){System.out.println("Java "+Runtime.version()+"; "+System.getProperty("os.name")+" "+System.getProperty("os.arch")+"; Python required=false");return;}
        if(mode.equals("quick")||mode.equals("runtime-smoke")) {
            compile();var base=List.of(java("java"),"--enable-native-access=ALL-UNNAMED","-cp",CLASSES.toString());
            if(mode.equals("quick"))for(String test:List.of("core.CoreSelfTest","phone.PhoneGeometrySelfTest","core.EnvironmentSelfTest","core.CameraChannelSelfTest","core.StorageBundleSelfTest","core.SharedSystemDiskSelfTest","core.QmpShutdownSelfTest","core.ManagedRuntimeSelfTest")){var command=new ArrayList<>(base);command.add("com.zhongbai233.mcandroidphone."+test);if(test.endsWith("ManagedRuntimeSelfTest"))command.add(ROOT.resolve(".runtime").toString());run(command);}
            else {var command=new ArrayList<>(base);command.add("com.zhongbai233.mcandroidphone.core.RuntimeSmoke");command.add(ROOT.resolve(".runtime").toString());settings.forEach((k,v)->command.add(k+"="+v));run(command);}return;
        }
        if(mode.equals("native-probe")) {
            if(!System.getProperty("os.name").startsWith("Mac"))throw new IllegalArgumentException("Use platform native probe instructions in docs/portable-validation.md");
            Path out=ROOT.resolve("build/native-probe/iosurface-probe");Files.createDirectories(out.getParent());run(List.of("xcrun","clang","-Wno-deprecated-declarations",ROOT.resolve("native/macos/iosurface_probe.m").toString(),"-framework","Foundation","-framework","Metal","-framework","IOSurface","-framework","OpenGL","-o",out.toString()));int result=new ProcessBuilder(out.toString()).inheritIO().start().waitFor();if(result==77)System.out.println("GPU capability unavailable on this host; no production zero-copy claim.");else if(result!=0)throw new IOException("Native probe failed: "+result);return;
        }
        if(!Set.of("build","pattern","qemu","bios").contains(mode))throw new IllegalArgumentException("Mode: check, quick, build, pattern, qemu, bios, runtime-smoke, native-probe");
        var command=new ArrayList<String>(List.of(java("java"),"-cp",ROOT.resolve("gradle/wrapper/gradle-wrapper.jar").toString(),"org.gradle.wrapper.GradleWrapperMain"));command.add(mode.equals("build")?"build":"runClient");
        if(!mode.equals("build"))settings.putIfAbsent("backend",mode.equals("pattern")?"pattern":"qemu");
        if(mode.equals("bios")){settings.put("bios","true");settings.putIfAbsent("guestArch","amd64");settings.putIfAbsent("input","mouse");}
        settings.forEach((k,v)->command.add("-PphoneRuntime"+Character.toUpperCase(k.charAt(0))+k.substring(1)+"="+v));
        if(world){command.add("-PphoneRuntimeStorage=snapshot");Path evidence=ROOT.resolve(".runtime/evidence/"+UUID.randomUUID());Files.createDirectories(evidence);System.out.println("Evidence: "+evidence);command.addAll(List.of("-PphoneWorldSmoke=true","-PphoneSmokeWarmupSeconds="+warmup,"-PphoneSmokeScreenshot="+evidence.resolve("phone.png")));if(mode.equals("pattern"))command.add("-PphonePatternSmoke=true");}
        if(android)command.add("-PphoneAndroidSmoke=true");
        if(environment)command.add("-PphoneEnvironmentSmoke=true");
        run(command);
    }
}
