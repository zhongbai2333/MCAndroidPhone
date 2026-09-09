import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.zip.ZipEntry;

/** Stable build metadata: wall-clock time must not change the runtime cache identity. */
final class BundleMetadata {
    static void writeProperties(Properties properties, OutputStream output) throws IOException {
        var encoded = new ByteArrayOutputStream();
        // Keep the JDK's escaping, including Unicode, separators and embedded newlines.
        properties.store(encoded, null);
        String stable = encoded.toString(StandardCharsets.ISO_8859_1).lines()
                .filter(line -> !line.startsWith("#"))
                .sorted().collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        output.write(stable.getBytes(StandardCharsets.ISO_8859_1));
    }

    static ZipEntry entry(String name) {
        var entry = new ZipEntry(name);
        // Stay away from ZIP's 1980 lower-bound sentinel, which can add a UTC extra timestamp.
        entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
        return entry;
    }
}
