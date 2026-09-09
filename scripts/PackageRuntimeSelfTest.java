import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.*;

/** Small synthetic packages exercise reproducibility without native binaries or Android images. */
class PackageRuntimeSelfTest {
    private static final String MANIFEST = "mcandroidphone/bundle/macos-arm64/manifest.properties";

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("mcphone-package-test-");
        TimeZone originalZone = TimeZone.getDefault();
        try {
            Path stage = Files.createDirectory(temp.resolve("stage"));
            var config = new Properties();
            config.setProperty("guestArch", "arm64");
            config.setProperty("# note:=", " 相机\\camera\nnext\r\t\u2603");
            for (String key : List.of("qemu", "ffmpeg", "disk", "dataDisk", "firmware", "firmwareVars")) {
                config.setProperty(key, key);
                Files.writeString(stage.resolve(key), "fixture " + key);
            }
            try (var out = Files.newOutputStream(stage.resolve("runtime.properties"))) {
                BundleMetadata.writeProperties(config, out);
            }
            Path base = temp.resolve("base.jar");
            writeBase(base, List.of("z.txt", "a.txt"), 1600000000000L);
            Path first = pack(base, stage, temp.resolve("first.jar"));
            Thread.sleep(2100); // Cross both Properties' seconds and ZIP's two-second timestamp boundary.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            writeBase(base, List.of("a.txt", "z.txt"), 1800000000000L);
            Files.setLastModifiedTime(stage.resolve("disk"), FileTime.fromMillis(1800000000000L));
            try (var out = Files.newOutputStream(stage.resolve("runtime.properties"))) {
                BundleMetadata.writeProperties(config, out);
            }
            Path second = pack(base, stage, temp.resolve("second.jar"));
            require(Files.mismatch(first, second) == -1, "Equivalent content must produce identical JAR bytes");
            byte[] before = manifest(first);
            var decoded = new Properties();
            decoded.load(new ByteArrayInputStream(before));
            require(config.getProperty("# note:=").equals(decoded.getProperty("config.# note:=")), "Escaped properties must round-trip");

            // A mod-only update should reuse the runtime cache, but actual image changes must invalidate it.
            writeBase(base, List.of("updated-mod.txt"), 1800000000000L);
            require(Arrays.equals(before, manifest(pack(base, stage, temp.resolve("mod-update.jar")))), "Mod-only update changed runtime identity");
            Files.writeString(stage.resolve("disk"), "changed image");
            require(!Arrays.equals(before, manifest(pack(base, stage, temp.resolve("image-update.jar")))), "Image update failed to change runtime identity");
            System.out.println("PACKAGE_REPRODUCIBILITY_OK identical bytes, stable cache, Unicode round-trip, image invalidation");
        } finally {
            TimeZone.setDefault(originalZone);
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Path pack(Path base, Path stage, Path output) throws Exception {
        PackageRuntime.main(new String[]{base.toString(), stage.toString(), "macos-arm64", output.toString()});
        return output;
    }

    private static byte[] manifest(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry(MANIFEST))) {
            return input.readAllBytes();
        }
    }

    private static void writeBase(Path path, List<String> names, long timestamp) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : names) {
                var entry = new ZipEntry(name);
                entry.setTime(timestamp);
                zip.putNextEntry(entry);
                zip.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
