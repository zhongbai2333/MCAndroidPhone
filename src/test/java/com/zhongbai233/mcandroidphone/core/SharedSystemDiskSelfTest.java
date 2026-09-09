package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Portable storage lifecycle checks; optional native tool directory enables real QEMU read/write checks. */
public final class SharedSystemDiskSelfTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    interface Action { void run() throws Exception; }
    static void rejected(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid storage accepted"); } catch (IOException expected) { }
    }
    static RuntimeConfig config(Path root, String id, String format) {
        var p = new Properties(); p.setProperty("root", root.toString()); p.setProperty("deviceId", id);
        p.setProperty("disk", "source." + format); p.setProperty("diskFormat", format);
        p.setProperty("dataDisk", "user.raw"); p.setProperty("dataDiskFormat", "raw");
        return new RuntimeConfig(p);
    }
    static void nativeRun(Path tools, String name, String... args) throws Exception {
        var command = new ArrayList<String>(); command.add(tools.resolve(name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString());
        command.addAll(List.of(args));
        Path output = Files.createTempFile("mcphone-qemu-check-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            check(process.waitFor(30, TimeUnit.SECONDS), "Native disk check timeout");
            check(process.exitValue() == 0, command + "\n" + Files.readString(output));
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            Files.deleteIfExists(output);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcphone-overlay-tests-");
        Path tools = args.length == 0 ? null : Path.of(args[0]);
        try {
            byte[] source = new byte[1024 * 1024]; Arrays.fill(source, (byte)0x5a);
            Files.write(root.resolve("source.raw"), source); Files.writeString(root.resolve("user.raw"), "private data");
            Path cancelledDevices = Files.createDirectory(root.resolve("cancelled-devices"));
            try {
                SharedSystemDisk.prepare(cancelledDevices, root.resolve("source.raw"), "raw", () -> {
                    Path bases = cancelledDevices.resolve(".bases");
                    if (Files.isDirectory(bases)) try (var files = Files.list(bases)) {
                        if (files.anyMatch(p -> p.getFileName().toString().startsWith(".copying-"))) throw new CancellationException();
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                throw new AssertionError("Copy cancellation ignored");
            } catch (CancellationException expected) { }
            try (var files = Files.list(cancelledDevices.resolve(".bases"))) {
                check(files.noneMatch(p -> p.getFileName().toString().startsWith(".copying-")), "Cancelled partial copy must be removed");
            }
            SharedSystemDisk.prepare(cancelledDevices, root.resolve("source.raw"), "raw", () -> {}); // Lock released for retry.
            rejected(() -> SharedSystemDisk.virtualSize(root.resolve("source.raw"), "qcow2"));
            exercise(root, "raw", tools);
            if (tools != null) {
                Path qcow = Files.createDirectory(root.resolve("qcow"));
                Files.write(qcow.resolve("source.raw"), source); Files.writeString(qcow.resolve("user.raw"), "private data");
                nativeRun(tools, "qemu-img", "convert", "-f", "raw", "-O", "qcow2", qcow.resolve("source.raw").toString(), qcow.resolve("source.qcow2").toString());
                exercise(qcow, "qcow2", tools);
            }
            System.out.println("SHARED_SYSTEM_DISK_OK lifecycle, version pinning, migration, cancellation, corruption" + (tools == null ? " (portable)" : ", QEMU check/read/write isolation for raw and qcow2 bases"));
        } finally {
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
    static void exercise(Path root, String format, Path tools) throws Exception {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var first = config(root, a, format); var second = config(root, b, format);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { try (var s = DeviceStorage.prepare(first, root, () -> {})) { return first.path("disk"); } });
            var two = pool.submit(() -> { try (var s = DeviceStorage.prepare(second, root, () -> {})) { return second.path("disk"); } });
            check(Files.size(one.get(30, TimeUnit.SECONDS)) == 262144 && Files.size(two.get(30, TimeUnit.SECONDS)) == 262144, "New overlays must be 256 KiB");
        }
        Path devices = root.resolve("mcandroidphone/devices"), base;
        try (var paths = Files.list(devices.resolve(".bases"))) {
            var bases = paths.filter(p -> p.toString().endsWith("." + format)).toList();
            check(bases.size() == 1, "Concurrent phones must share exactly one base"); base = bases.getFirst();
        }
        String originalHash = RuntimeBundle.hash(base);
        Files.writeString(first.path("dataDisk"), "saved photo");
        check(Files.readString(second.path("dataDisk")).equals("private data"), "User disks must remain independent");
        if (tools != null) {
            nativeRun(tools, "qemu-img", "check", first.path("disk").toString());
            nativeRun(tools, "qemu-io", "-f", "qcow2", "-c", "read -P 0x5a 0 4096", "-c", "write -P 0xa5 0 4096", first.path("disk").toString());
            nativeRun(tools, "qemu-io", "-f", "qcow2", "-c", "read -P 0x5a 0 4096", second.path("disk").toString());
            nativeRun(tools, "qemu-img", "check", first.path("disk").toString());
            check(RuntimeBundle.hash(base).equals(originalHash), "QEMU writes must not change the base");
        }
        // An updated template creates a new base only for new devices.
        Path source = root.resolve("source." + format);
        if (format.equals("raw")) { byte[] changed = Files.readAllBytes(source); changed[0] = 1; Files.write(source, changed); }
        else nativeRun(tools, "qemu-io", "-f", "qcow2", "-c", "write -P 0x11 0 512", source.toString());
        try (var s = DeviceStorage.prepare(config(root, UUID.randomUUID().toString(), format), root, () -> {})) { }
        try (var paths = Files.list(devices.resolve(".bases"))) { check(paths.filter(p -> p.toString().endsWith("." + format)).count() == 2, "Image updates need a separate base"); }
        Files.delete(source); // Existing devices must survive original template/bundle cache deletion.
        Path moved = root.resolve("迁移 devices with spaces"); Files.move(root.resolve("mcandroidphone"), moved);
        Path newGame = Files.createDirectory(root.resolve("moved-game")); Files.move(moved, newGame.resolve("mcandroidphone"));
        var restart = config(newGame, a, format);
        try (var s = DeviceStorage.prepare(restart, newGame, () -> {})) {
            check(Files.readString(restart.path("dataDisk")).equals("saved photo"), "Restart/move must preserve user data");
            if (tools != null) nativeRun(tools, "qemu-io", "-f", "qcow2", "-c", "read -P 0xa5 0 4096", restart.path("disk").toString());
            rejected(() -> DeviceStorage.prepare(config(newGame, a, format), newGame, () -> {}));
            rejected(() -> SharedSystemDisk.virtualSize(restart.path("disk"), "qcow2")); // Do not import external backing chains.
        }
        Path movedBase = newGame.resolve("mcandroidphone/devices/.bases").resolve(base.getFileName());
        check(RuntimeBundle.hash(movedBase).equals(originalHash), "Base stays unchanged across update and move");
        Path missing = movedBase.resolveSibling("temporarily-missing"); Files.move(movedBase, missing);
        rejected(() -> DeviceStorage.prepare(config(newGame, a, format), newGame, () -> {}));
        Files.move(missing, movedBase);
        byte[] data = Files.readAllBytes(movedBase); data[data.length - 1] ^= 1; Files.write(movedBase, data);
        rejected(() -> DeviceStorage.prepare(config(newGame, a, format), newGame, () -> {}));
        var cancel = new java.util.concurrent.atomic.AtomicInteger();
        try { RuntimeBundle.hash(movedBase, () -> { cancel.incrementAndGet(); throw new CancellationException(); }); throw new AssertionError("Ignored cancellation"); }
        catch (CancellationException expected) { check(cancel.get() == 1, "Hash must check cancellation per chunk"); }
    }
}
