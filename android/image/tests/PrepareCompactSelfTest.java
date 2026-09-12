import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** GNU Make evaluates opt-in scope; failed anchors must leave every source untouched. */
class PrepareCompactSelfTest {
    static Path project=Path.of("").toAbsolutePath();
    static void put(Path root,String path,String text)throws Exception {Path p=root.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,text);}
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static String run(Path root,int expected,String...command)throws Exception {
        Path log=Files.createTempFile("compact-check-",".log");Process p=null;
        try{p=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();check(p.waitFor(60,TimeUnit.SECONDS),"Timeout");String text=Files.readString(log);check(p.exitValue()==expected,text);return text;}
        finally{if(p!=null&&p.isAlive())p.destroyForcibly();Files.delete(log);}
    }
    static String prepare(Path root,int exit)throws Exception {return run(project,exit,Path.of(System.getProperty("java.home"),"bin","java").toString(),project.resolve("android/image/PrepareCompact.java").toString(),root.toString());}
    static Map<String,String> snapshot(Path root)throws Exception {var m=new TreeMap<String,String>();try(var paths=Files.walk(root)){for(Path p:paths.filter(Files::isRegularFile).toList())m.put(root.relativize(p).toString(),Files.readString(p));}return m;}
    static void fixture(Path root)throws Exception {
        put(root,"build/envsetup.sh","");
        put(root,"device/virt/virtio_x86_64/BoardConfig.mk","BOARD_MESA3D_GALLIUM_DRIVERS += crocus iris\nBOARD_MESA3D_VULKAN_DRIVERS += intel intel_hasvk\n$(call soong_config_set_string_list,minigbm_upstream,cflags,-DDRV_I915 -DDRV_XE)\nTARGET_KERNEL_CONFIG_EXT += \\\n    $(DEVICE_PATH)/configs/kernel/passthrough_gpus.config\n");
        put(root,"device/virt/virt-common/virt-common.mk","TARGET_MESA_ENABLE_SOFTWARE_RENDERER := true\n$(call inherit-product, packages/modules/Virtualization/apex/product_packages.mk)\n");
        put(root,"device/virt/virt-common/BoardConfigVirtCommon.mk","BOARD_BOOTCONFIG := \\\n    androidboot.hypervisor.vm.supported=1\n");
        for(String file:List.of("build/make/target/product/aosp_product.mk","vendor/lineage/config/common_mobile.mk"))put(root,file,"PRODUCT_PACKAGES += \\\n    ThemePicker \\\n\n");
        put(root,"build/make/target/product/media_product.mk","PRODUCT_PACKAGES += \\\n    webview \\\n\n");
        put(root,"vendor/lineage/config/common.mk","ifeq ($(PRODUCT_IS_ATV),)\nPRODUCT_PACKAGES += \\\n    Jelly\nendif\n");
        put(root,"external/chromium-webview/Android.bp","android_app_import {\n    apk: \"prebuilt/x86_64/webview.apk\",\n}\n");
    }
    static void scope(Path root,String target,String profile)throws Exception {
        String mk="TARGET_PRODUCT := "+target+"\nMCANDROIDPHONE_IMAGE_PROFILE := "+profile+"\nBOARD_MESA3D_GALLIUM_DRIVERS := virgl\nBOARD_MESA3D_VULKAN_DRIVERS := virtio\ninherit-product = $(eval PRODUCT_PACKAGES += com.android.compos features_com.android.virt.xml)\n";
        for(String file:List.of("device/virt/virtio_x86_64/BoardConfig.mk","device/virt/virt-common/virt-common.mk","device/virt/virt-common/BoardConfigVirtCommon.mk","build/make/target/product/aosp_product.mk","vendor/lineage/config/common_mobile.mk","build/make/target/product/media_product.mk","vendor/lineage/config/common.mk"))mk+="include "+file+"\n";
        mk+="all:\n\t@echo '$(BOARD_MESA3D_GALLIUM_DRIVERS)|$(BOARD_MESA3D_VULKAN_DRIVERS)|$(TARGET_MESA_ENABLE_SOFTWARE_RENDERER)|$(sort $(PRODUCT_PACKAGES))|$(BOARD_BOOTCONFIG)'\n";
        put(root,"Check.mk",mk);String result=run(root,0,"make","--no-print-directory","-s","-f","Check.mk").trim();
        boolean active=target.equals("lineage_virtio_x86_64_go")&&!profile.equals("full");
        String expected=active?"virgl|virtio|false|"+(profile.equals("minimal")?"":"Jelly webview")+"|androidboot.hypervisor.vm.supported=0":"virgl crocus iris|virtio intel intel_hasvk|true|Jelly ThemePicker com.android.compos features_com.android.virt.xml webview|androidboot.hypervisor.vm.supported=1";
        check(result.equals(expected),target+"/"+profile+": "+result);
    }
    static void featureScope(Path root)throws Exception {
        for(String profile:List.of("full","compact","minimal")) {
            put(root,"Features.mk","TARGET_PRODUCT := lineage_virtio_x86_64_go\nTARGET_COPY_OUT_PRODUCT := product\nMCANDROIDPHONE_IMAGE_PROFILE := "+profile+"\ninclude "+project.resolve("android/image/compact/product.mk")+"\nall:\n\t@echo '$(PRODUCT_COPY_FILES)'\n");
            String value=run(root,0,"make","--no-print-directory","-s","-f","Features.mk").trim();
            check(value.equals(profile.equals("minimal")?"vendor/mcandroidphone/compact/no-webview.xml:product/etc/permissions/mcandroidphone-no-webview.xml":""),"WebView declaration scope: "+profile);
        }
        var xml=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(project.resolve("android/image/compact/no-webview.xml").toFile());
        var nodes=xml.getElementsByTagName("unavailable-feature");check(nodes.getLength()==1&&((org.w3c.dom.Element)nodes.item(0)).getAttribute("name").equals("android.software.webview"),"Minimal must disable unavailable WebView feature");
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0)project=Path.of(args[0]).toAbsolutePath();Path root=Files.createTempDirectory("mcphone-compact-");
        try{
            featureScope(root);
            fixture(root);prepare(root,0);check(prepare(root,0).contains("0 upstream files"),"Not idempotent");
            for(String target:List.of("lineage_virtio_x86_64_go","lineage_virtio_x86_64","lineage_virtio_arm64only_go"))for(String profile:List.of("full","compact","minimal"))scope(root,target,profile);
            fixture(root);put(root,"external/chromium-webview/Android.bp","unexpected upstream layout");var before=snapshot(root);prepare(root,1);check(snapshot(root).equals(before),"Drift partially wrote sources");
            System.out.println("COMPACT_PREPARE_OK Make scope and WebView feature: full/compact/minimal; other products unchanged; idempotence and pre-write drift guard");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
