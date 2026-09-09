import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Install this overlay into an already synced LineageOS 23.2 build checkout. */
class Prepare {
    static final String MARK="MCANDROIDPHONE_ENVIRONMENT_V1";
    static final Map<Path,String> edits=new LinkedHashMap<>();
    static void patch(Path file,String before,String after)throws IOException {
        String original=edits.containsKey(file)?edits.get(file):Files.readString(file);
        if(original.contains(after))return;
        int at=original.indexOf(before);
        if(at<0||original.indexOf(before,at+before.length())>=0)throw new IOException("Upstream anchor changed: "+file);
        edits.put(file,original.substring(0,at)+after+original.substring(at+before.length()));
    }
    static void append(Path file,String addition)throws IOException {
        String text=Files.readString(file);if(!text.contains(addition))edits.put(file,text+"\n# "+MARK+"\n"+addition+"\n");
    }
    static final String GO_TARGETS="lineage_virtio_arm64only_go lineage_virtio_x86_64_go";
    static void optionalPackage(Path file,String name)throws IOException {
        String text=edits.containsKey(file)?edits.get(file):Files.readString(file);
        String replacement="$(if $(filter "+GO_TARGETS+",$(TARGET_PRODUCT)),,"+name+")";
        if(text.contains(replacement))return;
        var matcher=java.util.regex.Pattern.compile("(?m)^    "+java.util.regex.Pattern.quote(name)+"( ?\\\\)?$").matcher(text);
        if(!matcher.find())throw new IOException("Missing package anchor "+name+": "+file);
        String before=matcher.group();if(matcher.find())throw new IOException("Ambiguous package anchor "+name);
        patch(file,before,before.replace(name,replacement));
    }
    public static void main(String[] args)throws Exception {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("java android/image/Prepare.java <lineage-root> <this-project-root> [--check]");
        boolean check=args.length==3&&args[2].equals("--check");if(args.length==3&&!check)throw new IllegalArgumentException(args[2]);
        Path root=Path.of(args[0]).toAbsolutePath().normalize(),source=Path.of(args[1]).toAbsolutePath().resolve("android/image");
        if(!Files.isRegularFile(root.resolve("build/envsetup.sh")))throw new IOException("Not a synced Android source tree");
        for(String arch:List.of("arm64only","x86_64")) {
            Path product=root.resolve("device/virt/virtio_"+arch+"/lineage_virtio_"+arch+"_go.mk");
            // A child product cannot override scalar values already set by its parent.
            // Apply our final settings directly in these two root products.
            String old="$(call inherit-product, vendor/mcandroidphone/product.mk)";
            String addition="include vendor/mcandroidphone/product.mk";
            if(Files.readString(product).contains(old))patch(product,old,addition);
            else append(product,addition);
            patch(product,"$(call inherit-product, $(SRC_TARGET_DIR)/product/languages_full.mk)",
                    "PRODUCT_LOCALES := en_US zh_CN # MCANDROIDPHONE_RESOURCE_LOCALES_V1");
        }
        // Remove packages at their actual declaration sites, not from unresolved inherit markers.
        // Other Lineage products retain the original packages.
        for(String name:List.of("BasicDreams","EasterEgg","PrintRecommendationService"))
            optionalPackage(root.resolve("build/make/target/product/handheld_system.mk"),name);
        optionalPackage(root.resolve("vendor/lineage/config/common_mobile.mk"),"Backgrounds");
        optionalPackage(root.resolve("vendor/lineage/config/common.mk"),"vim");
        append(root.resolve("device/virt/virtio-common/BoardConfigCommon.mk"),"BOARD_VENDOR_SEPOLICY_DIRS += vendor/mcandroidphone/sepolicy");
        Path sensors=root.resolve("hardware/interfaces/sensors/aidl/default");
        patch(sensors.resolve("Sensor.cpp"),"#include \"sensors-impl/Sensor.h\"","#include \"sensors-impl/Sensor.h\"\n#include \"EnvironmentState.h\" // "+MARK);
        patch(sensors.resolve("Android.bp"),"    name: \"libsensorsexampleimpl\",","    name: \"libsensorsexampleimpl\",\n    header_libs: [\"mcphone-environment-headers\"], // "+MARK);
        patch(sensors.resolve("Sensor.cpp"),"    readEventPayload(event.payload);", """
                readEventPayload(event.payload);
                // MCANDROIDPHONE_ENVIRONMENT_V1: normal HAL events, never SensorService injection mode.
                const int type = static_cast<int>(mSensorInfo.type);
                if (type == 1 || type == 2 || type == 4 || type == 5 || type == 6) {
                    mcphone::Snapshot state;
                    if (!mcphone::readSnapshot(state)) return events;
                    const auto& v = state.values;
                    if (type == 5 || type == 6) {
                        event.payload.set<EventPayload::Tag::scalar>(static_cast<float>(v[type == 5 ? 17 : 16]));
                    } else {
                        const auto field = mcphone::magnetic(state);
                        const int offset = type == 1 ? 10 : 13;
                        EventPayload::Vec3 vector = {
                            .x = type == 2 ? field[0] : static_cast<float>(v[offset]),
                            .y = type == 2 ? field[1] : static_cast<float>(v[offset + 1]),
                            .z = type == 2 ? field[2] : static_cast<float>(v[offset + 2]),
                            .status = SensorStatus::ACCURACY_HIGH,
                        };
                        event.payload.set<EventPayload::Tag::vec3>(vector);
                    }
                }""");
        Path gnss=root.resolve("hardware/interfaces/gnss/aidl/default");
        patch(gnss.resolve("Gnss.cpp"),"#include \"Gnss.h\"","#include \"Gnss.h\"\n#include \"EnvironmentState.h\" // "+MARK);
        patch(gnss.resolve("Android.bp"),"    name: \"android.hardware.gnss-service.example\",","    name: \"android.hardware.gnss-service.example\",\n    header_libs: [\"mcphone-environment-headers\"], // "+MARK);
        patch(gnss.resolve("Gnss.cpp"),"void Gnss::reportLocation(const GnssLocation& location) {", """
            void Gnss::reportLocation(const GnssLocation& original) {
                // MCANDROIDPHONE_ENVIRONMENT_V1: no fix while paused, stale, or outside the mapped world.
                mcphone::Snapshot state;
                if (!mcphone::readSnapshot(state) || !state.locationValid) return;
                auto location = original;
                location.latitudeDegrees = state.values[3];
                location.longitudeDegrees = state.values[4];
                location.altitudeMeters = state.values[5];
                location.speedMetersPerSec = static_cast<float>(state.values[18]);
                location.bearingDegrees = static_cast<float>(state.values[19]);
                location.horizontalAccuracyMeters = 1.0f;
                location.verticalAccuracyMeters = 1.0f;""");
        // Stock example NMEA describes a different location. Do not advertise that data.
        patch(gnss.resolve("Gnss.cpp"),"                this->reportNmea();","                // "+MARK+": virtual NMEA is not implemented.");
        Map<Path,byte[]> copies=new LinkedHashMap<>();
        for(String entry:List.of("guest","sepolicy","overlay","product.mk","go-optimization.mk"))try(var files=Files.walk(source.resolve(entry))) {
            for(Path file:files.filter(Files::isRegularFile).toList()) {
                Path destination=root.resolve("vendor/mcandroidphone").resolve(source.relativize(file));byte[] data=Files.readAllBytes(file);
                if(Files.exists(destination)&&!Arrays.equals(Files.readAllBytes(destination),data))throw new IOException("Refusing to overwrite edited overlay: "+destination);
                copies.put(destination,data);
            }
        }
        Path camera=source.getParent().resolve("camera");
        for(String entry:List.of("Android.bp","AndroidManifest.xml","src"))try(var files=Files.walk(camera.resolve(entry))) {
            for(Path file:files.filter(Files::isRegularFile).toList()) {
                Path destination=root.resolve("vendor/mcandroidphone/camera").resolve(camera.relativize(file));byte[] data=Files.readAllBytes(file);
                if(Files.exists(destination)&&!Arrays.equals(Files.readAllBytes(destination),data))throw new IOException("Refusing to overwrite edited camera overlay: "+destination);
                copies.put(destination,data);
            }
        }
        // All anchors and destination checks complete before the first write.
        System.out.println("Overlay plan: "+edits.size()+" upstream files, "+copies.size()+" overlay files; check="+check);
        if(check)return;
        for(var e:copies.entrySet()){Files.createDirectories(e.getKey().getParent());if(!Files.exists(e.getKey()))Files.write(e.getKey(),e.getValue(),StandardOpenOption.CREATE_NEW);}
        for(var e:edits.entrySet())Files.writeString(e.getKey(),e.getValue());
    }
}
