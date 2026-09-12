import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Guarded, opt-in build specialization. Full and other products retain upstream behavior. */
class PrepareCompact {
    static final Map<Path,String> edits=new LinkedHashMap<>();
    static final String ACTIVE="$(and $(filter lineage_virtio_x86_64_go,$(TARGET_PRODUCT)),$(filter compact minimal,$(MCANDROIDPHONE_IMAGE_PROFILE)))";
    static void patch(Path root,String relative,String before,String after)throws IOException {
        Path p=root.resolve(relative);String text=edits.containsKey(p)?edits.get(p):Files.readString(p);
        if(text.contains(after))return;
        int pos=text.indexOf(before);
        if(pos<0||text.indexOf(before,pos+before.length())>=0)throw new IOException("Compact upstream anchor changed: "+p);
        edits.put(p,text.substring(0,pos)+after+text.substring(pos+before.length()));
    }
    static void onlyFull(Path root,String file,String text)throws IOException {
        patch(root,file,text,"ifeq ("+ACTIVE+",)\n"+text+"\nendif # MCANDROIDPHONE_COMPACT_V1");
    }
    public static void main(String[] args)throws Exception {
        if(args.length<1||args.length>2||args.length==2&&!args[1].equals("--check"))throw new IllegalArgumentException("PrepareCompact <lineage-root> [--check]");
        Path root=Path.of(args[0]).toRealPath();boolean check=args.length==2;
        if(!Files.isRegularFile(root.resolve("build/envsetup.sh")))throw new IOException("Not an Android source tree");
        onlyFull(root,"device/virt/virtio_x86_64/BoardConfig.mk","BOARD_MESA3D_GALLIUM_DRIVERS += crocus iris\nBOARD_MESA3D_VULKAN_DRIVERS += intel intel_hasvk");
        onlyFull(root,"device/virt/virtio_x86_64/BoardConfig.mk","$(call soong_config_set_string_list,minigbm_upstream,cflags,-DDRV_I915 -DDRV_XE)");
        patch(root,"device/virt/virtio_x86_64/BoardConfig.mk","    $(DEVICE_PATH)/configs/kernel/passthrough_gpus.config","    $(if "+ACTIVE+",,$(DEVICE_PATH)/configs/kernel/passthrough_gpus.config)");
        patch(root,"device/virt/virt-common/virt-common.mk","TARGET_MESA_ENABLE_SOFTWARE_RENDERER := true","TARGET_MESA_ENABLE_SOFTWARE_RENDERER := $(if "+ACTIVE+",false,true)");
        onlyFull(root,"device/virt/virt-common/virt-common.mk","$(call inherit-product, packages/modules/Virtualization/apex/product_packages.mk)");
        patch(root,"device/virt/virt-common/BoardConfigVirtCommon.mk","    androidboot.hypervisor.vm.supported=1", "    androidboot.hypervisor.vm.supported=$(if "+ACTIVE+",0,1)");
        for(String file:List.of("build/make/target/product/aosp_product.mk","vendor/lineage/config/common_mobile.mk"))
            patch(root,file,"    ThemePicker \\","    $(if "+ACTIVE+",,ThemePicker) \\");
        String minimal="$(and $(filter lineage_virtio_x86_64_go,$(TARGET_PRODUCT)),$(filter minimal,$(MCANDROIDPHONE_IMAGE_PROFILE)))";
        patch(root,"build/make/target/product/media_product.mk","    webview \\","    $(if "+minimal+",,webview) \\");
        patch(root,"vendor/lineage/config/common.mk","    Jelly\nendif","    $(if "+minimal+",,Jelly)\nendif");
        String bp="external/chromium-webview/Android.bp";
        patch(root,bp,"apk: \"prebuilt/x86_64/webview.apk\"","apk: \":mcphone-webview-x86_64\"");
        patch(root,bp,"android_app_import {", """
                // MCANDROIDPHONE_COMPACT_V1: only a 64-bit-only compact guest drops x86 JNI.
                soong_config_module_type_import {
                    from: "vendor/mcandroidphone/compact/Android.bp",
                    module_types: ["mcphone_webview_filter"],
                }
                mcphone_webview_filter {
                    name: "mcphone-webview-x86_64",
                    srcs: ["prebuilt/x86_64/webview.apk"],
                    out: ["webview.apk"],
                    tools: ["mcphone-filter-webview"],
                    soong_config_variables: {
                        compact_amd64: {
                            cmd: "$(location mcphone-filter-webview) $(in) $(out)",
                            conditions_default: { cmd: "cp $(in) $(out)" },
                        },
                    },
                }
                android_app_import {""");
        System.out.println("Compact plan: "+edits.size()+" upstream files; check="+check);
        if(check)return;
        for(var e:edits.entrySet())Files.writeString(e.getKey(),e.getValue());
    }
}
