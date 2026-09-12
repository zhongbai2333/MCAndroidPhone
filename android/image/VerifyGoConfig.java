import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Fail before compilation if the resolved product silently lost our compatibility/size contract. */
class VerifyGoConfig {
    static final List<String> VARIABLES=List.of("PRODUCT_NAME","PRODUCT_DEVICE","PRODUCT_BRAND","PRODUCT_LOCALES",
            "TARGET_BUILD_VARIANT","PRODUCT_DEX_PREOPT_DEFAULT_COMPILER_FILTER","PRODUCT_SYSTEM_SERVER_COMPILER_FILTER",
            "PRODUCT_ART_TARGET_INCLUDE_DEBUG_BUILD","PRODUCT_MINIMIZE_JAVA_DEBUG_INFO","PRODUCT_DEXPREOPT_SPEED_APPS",
            "PRODUCT_PACKAGES","PRODUCT_VENDOR_PROPERTIES","MCANDROIDPHONE_IMAGE_PROFILE","PRODUCT_COMPRESSED_APEX","PRODUCT_COPY_FILES");
    static Set<String> words(String value){return new HashSet<>(Arrays.asList(value.trim().split("\\s+")));}
    static void verify(Properties p) {
        String name=p.getProperty("PRODUCT_NAME","");
        if(!Set.of("lineage_virtio_arm64only_go","lineage_virtio_x86_64_go").contains(name))throw new IllegalArgumentException("Not an approved Go product: "+name);
        String profile=p.getProperty("MCANDROIDPHONE_IMAGE_PROFILE","full");
        if(!Set.of("full","compact","minimal").contains(profile))throw new IllegalArgumentException("Invalid image profile");
        boolean compact=!profile.equals("full");
        if(compact&&!name.equals("lineage_virtio_x86_64_go"))throw new IllegalArgumentException("Compact profile requires AMD64");
        Map<String,String> required=Map.of("PRODUCT_DEVICE",name.substring("lineage_".length()),"PRODUCT_BRAND","MCAndroidPhone",
                "TARGET_BUILD_VARIANT","user","PRODUCT_DEX_PREOPT_DEFAULT_COMPILER_FILTER","verify",
                "PRODUCT_SYSTEM_SERVER_COMPILER_FILTER",name.contains("x86_64")&&!compact?"speed":"speed-profile","PRODUCT_ART_TARGET_INCLUDE_DEBUG_BUILD","false",
                "PRODUCT_MINIMIZE_JAVA_DEBUG_INFO","true");
        required.forEach((k,v)->{if(!v.equals(p.getProperty(k,"")))throw new IllegalArgumentException(k+" must be "+v+", got "+p.getProperty(k));});
        if(!words(p.getProperty("PRODUCT_LOCALES","")).equals(Set.of("en_US","zh_CN")))throw new IllegalArgumentException("Unexpected resource locales: " + p.getProperty("PRODUCT_LOCALES", ""));
        if(!compact&&!words(p.getProperty("PRODUCT_DEXPREOPT_SPEED_APPS","")).containsAll(Set.of("SystemUI","Launcher3QuickStepGo")))
            throw new IllegalArgumentException("Missing hot-app precompilation");
        Set<String> packages=words(p.getProperty("PRODUCT_PACKAGES",""));
        for(String needed:List.of("Settings","SystemUI","Launcher3QuickStepGo","LatinIME","cameraserver","DocumentsUI","MCPhoneCamera","mcphone-environmentd"))
            if(!packages.contains(needed))throw new IllegalArgumentException("Required module missing: "+needed);
        if(profile.equals("minimal")?packages.contains("webview"):!packages.contains("webview"))throw new IllegalArgumentException("WebView does not match image profile");
        if(profile.equals("minimal")&&packages.contains("Jelly"))throw new IllegalArgumentException("Minimal must omit the WebView-dependent browser");
        boolean webviewDisabled=words(p.getProperty("PRODUCT_COPY_FILES","")).stream().anyMatch(v->v.startsWith("vendor/mcandroidphone/compact/no-webview.xml:"));
        if(profile.equals("minimal")!=webviewDisabled)throw new IllegalArgumentException("WebView feature declaration must match installed provider profile");
        if(compact) {
            if(!"false".equals(p.getProperty("PRODUCT_COMPRESSED_APEX")))throw new IllegalArgumentException("Compact profile must expose APEX contents to outer compression");
            for(String omitted:List.of("ThemePicker","com.android.compos","features_com.android.virt.xml"))if(packages.contains(omitted))throw new IllegalArgumentException("Compact module still present: "+omitted);
        }
        for(String omitted:List.of("BasicDreams","EasterEgg","PrintRecommendationService","Backgrounds","vim"))
            if(packages.contains(omitted))throw new IllegalArgumentException("Optional module still present: "+omitted);
        var vendor=words(p.getProperty("PRODUCT_VENDOR_PROPERTIES",""));
        if(!vendor.contains("ro.config.low_ram=true")||vendor.contains("ro.config.low_ram=false"))throw new IllegalArgumentException("Go low-RAM configuration missing or conflicting");
    }
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--variables")){VARIABLES.forEach(System.out::println);return;}
        if(args.length!=1)throw new IllegalArgumentException("java android/image/VerifyGoConfig.java resolved-product.properties | --variables");
        var p=new Properties();try(var in=Files.newBufferedReader(Path.of(args[0]))){p.load(in);}verify(p);
        System.out.println("GO_CONFIG_OK "+p.getProperty("PRODUCT_NAME")+"; configuration only, image/app acceptance still required");
    }
}
