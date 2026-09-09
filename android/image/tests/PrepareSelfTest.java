import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline patch safety plus GNU Make evaluation; not a Soong/Android compilation test. */
class PrepareSelfTest {
    static Path project=Path.of("").toAbsolutePath(), fixtures=project.resolve("android/image/tests/fixtures");
    static void put(Path root,String file,String text)throws IOException {Path p=root.resolve(file);Files.createDirectories(p.getParent());Files.writeString(p,text);}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static Path fixture(Path directory)throws Exception {
        Files.createDirectories(directory);put(directory,"build/envsetup.sh","# Synthetic source-root marker; no Android build environment\n");
        for(String arch:List.of("arm64only","x86_64"))put(directory,"device/virt/virtio_"+arch+"/lineage_virtio_"+arch+"_go.mk",Files.readString(fixtures.resolve(arch.equals("arm64only")?"arm64-go.mk":"amd64-go.mk")));
        put(directory,"device/virt/virtio-common/BoardConfigCommon.mk","# Test board\n");
        put(directory,"hardware/interfaces/sensors/aidl/default/Sensor.cpp","#include \"sensors-impl/Sensor.h\"\n    readEventPayload(event.payload);\n");
        put(directory,"hardware/interfaces/sensors/aidl/default/Android.bp","    name: \"libsensorsexampleimpl\",\n");
        put(directory,"hardware/interfaces/gnss/aidl/default/Gnss.cpp","#include \"Gnss.h\"\nvoid Gnss::reportLocation(const GnssLocation& location) {\n                this->reportNmea();\n}\n");
        put(directory,"hardware/interfaces/gnss/aidl/default/Android.bp","    name: \"android.hardware.gnss-service.example\",\n");
        for(var e:Map.of("handheld_system.mk","build/make/target/product/handheld_system.mk","common.mk","vendor/lineage/config/common.mk","common_mobile.mk","vendor/lineage/config/common_mobile.mk").entrySet())put(directory,e.getValue(),Files.readString(fixtures.resolve(e.getKey())));
        return directory;
    }
    static String run(Path cwd,int expected,String... command)throws Exception {
        Path log=Files.createTempFile("mcphone-go-test-",".log");Process p=null;
        try {
            p=new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            check(p.waitFor(40,TimeUnit.SECONDS),"Test process timeout");String text=Files.readString(log);check(p.exitValue()==expected,Arrays.toString(command)+"\n"+text);return text;
        } finally {if(p!=null&&p.isAlive())p.destroyForcibly();Files.deleteIfExists(log);}
    }
    static Map<String,String> snapshot(Path root)throws Exception {
        var result=new TreeMap<String,String>();try(var paths=Files.walk(root)){for(Path p:paths.filter(Files::isRegularFile).toList())result.put(root.relativize(p).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}return result;
    }
    static void prepare(Path root,int exit,boolean check)throws Exception {
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),project.resolve("android/image/Prepare.java").toString(),root.toString(),project.toString()));if(check)args.add("--check");run(project,exit,args.toArray(String[]::new));
    }
    static void evaluate(Path root,String arch)throws Exception {
        String product="lineage_virtio_"+arch+"_go";
        String mk="TARGET_PRODUCT := "+product+"\ninclude device/virt/virtio_"+arch+"/lineage_virtio_"+arch+"_go.mk\nall:\n\t@echo $(PRODUCT_BRAND) $(PRODUCT_LOCALES) $(PRODUCT_DEX_PREOPT_DEFAULT_COMPILER_FILTER) $(PRODUCT_SYSTEM_SERVER_COMPILER_FILTER) $(PRODUCT_DEVICE)\n";
        put(root,"Evaluate.mk",mk);String result=run(root,0,"make","--no-print-directory","-s","-f","Evaluate.mk").trim();
        check(result.equals("MCAndroidPhone en_US zh_CN verify speed-profile virtio_"+arch+"_go"),"Root product override failed: "+result);
    }
    static void packageScope(Path root,String target,boolean omitted)throws Exception {
        var mk=new StringBuilder("TARGET_PRODUCT := "+target+"\n");
        for(String file:List.of("build/make/target/product/handheld_system.mk","vendor/lineage/config/common_mobile.mk","vendor/lineage/config/common.mk"))
            for(String line:Files.readAllLines(root.resolve(file)))if(line.contains("$(if $(filter lineage_virtio_arm64only_go"))mk.append("PRODUCT_PACKAGES += ").append(line.trim().replace("\\","")).append('\n');
        mk.append("all:\n\t@echo $(PRODUCT_PACKAGES)\n");put(root,"Packages.mk",mk.toString());String result=run(root,0,"make","--no-print-directory","-s","-f","Packages.mk").trim();
        check(omitted?result.isEmpty():new HashSet<>(Arrays.asList(result.split("\\s+"))).equals(Set.of("BasicDreams","EasterEgg","PrintRecommendationService","Backgrounds","vim")),"Package scope leaked: "+target+" "+result);
    }
    static void configContract(Path root)throws Exception {
        var p=new Properties();
        p.setProperty("PRODUCT_NAME","lineage_virtio_arm64only_go");p.setProperty("PRODUCT_DEVICE","virtio_arm64only_go");p.setProperty("PRODUCT_BRAND","MCAndroidPhone");
        p.setProperty("PRODUCT_LOCALES","en_US zh_CN");p.setProperty("TARGET_BUILD_VARIANT","user");p.setProperty("PRODUCT_DEX_PREOPT_DEFAULT_COMPILER_FILTER","verify");
        p.setProperty("PRODUCT_SYSTEM_SERVER_COMPILER_FILTER","speed-profile");p.setProperty("PRODUCT_ART_TARGET_INCLUDE_DEBUG_BUILD","false");p.setProperty("PRODUCT_MINIMIZE_JAVA_DEBUG_INFO","true");
        p.setProperty("PRODUCT_DEXPREOPT_SPEED_APPS","SystemUI Launcher3QuickStepGo");p.setProperty("PRODUCT_VENDOR_PROPERTIES","ro.config.low_ram=true");
        p.setProperty("PRODUCT_PACKAGES","Settings SystemUI Launcher3QuickStepGo LatinIME webview cameraserver DocumentsUI MCPhoneCamera mcphone-environmentd");
        for(String fault:List.of("none","missing-webview","locales","brand","preopt","optional","variant")) {
            var candidate=new Properties();candidate.putAll(p);
            switch(fault) {
                case "missing-webview" -> candidate.setProperty("PRODUCT_PACKAGES",p.getProperty("PRODUCT_PACKAGES").replace("webview ",""));
                case "locales" -> candidate.setProperty("PRODUCT_LOCALES","en_US zh_CN fr_FR");
                case "brand" -> candidate.setProperty("PRODUCT_BRAND","VirtIO");
                case "preopt" -> candidate.setProperty("PRODUCT_DEX_PREOPT_DEFAULT_COMPILER_FILTER","speed");
                case "optional" -> candidate.setProperty("PRODUCT_PACKAGES",p.getProperty("PRODUCT_PACKAGES")+" EasterEgg");
                case "variant" -> candidate.setProperty("TARGET_BUILD_VARIANT","userdebug");
            }
            Path file=root.resolve(fault+".properties");try(var out=Files.newOutputStream(file)){candidate.store(out,"Test resolved configuration");}
            run(project,fault.equals("none")?0:1,Path.of(System.getProperty("java.home"),"bin","java").toString(),project.resolve("android/image/VerifyGoConfig.java").toString(),file.toString());
        }
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-go-prepare-");
        try {
            Path good=fixture(root.resolve("good"));var original=snapshot(good);prepare(good,0,true);check(snapshot(good).equals(original),"Dry run wrote files");prepare(good,0,false);
            var applied=snapshot(good);prepare(good,0,false);check(snapshot(good).equals(applied),"Non-idempotent patch");
            evaluate(good,"arm64only");evaluate(good,"x86_64");packageScope(good,"lineage_virtio_arm64only_go",true);packageScope(good,"lineage_virtio_x86_64_go",true);packageScope(good,"lineage_virtio_arm64only",false);
            Path drift=fixture(root.resolve("drift"));put(drift,"vendor/lineage/config/common.mk","# Changed upstream, no vim anchor\n");var before=snapshot(drift);prepare(drift,1,false);check(snapshot(drift).equals(before),"Upstream drift partially wrote files");
            Path edited=fixture(root.resolve("edited"));put(edited,"vendor/mcandroidphone/product.mk","# User edits must survive\n");before=snapshot(edited);prepare(edited,1,false);check(snapshot(edited).equals(before),"Local edits overwritten");
            configContract(root);
            System.out.println("GO_PREPARE_OK dry-run, idempotence, both root products evaluated, non-Go packages preserved, drift and dirty overlay rejected before writes");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
