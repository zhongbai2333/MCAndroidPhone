package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable content-addressed bases owned by device storage, independent of bundle cache lifetime. */
final class SharedSystemDisk {
    private static final int CLUSTER = 65536;
    private static final long MAX_SIZE = 1L << 40;

    static long virtualSize(Path source, String format) throws IOException {
        long size;
        if (format.equals("raw")) size = Files.size(source);
        else if (format.equals("qcow2")) {
            try (var in = new RandomAccessFile(source.toFile(), "r")) {
                if (in.readInt() != 0x514649fb) throw new IOException("Invalid qcow2 system image");
                int version = in.readInt();
                if (version != 2 && version != 3) throw new IOException("Unsupported qcow2 version");
                if (in.readLong() != 0 || in.readInt() != 0) throw new IOException("System template must be standalone, without a backing file");
                int bits = in.readInt();
                if (bits < 9 || bits > 21) throw new IOException("Invalid qcow2 cluster size");
                size = in.readLong();
                if (in.readInt() != 0) throw new IOException("Encrypted system templates are unsupported");
                if (version == 3) {
                    in.seek(72);
                    if (in.readLong() != 0) throw new IOException("System template has dirty, corrupt or unsupported qcow2 features");
                    in.seek(100);
                    int length = in.readInt();
                    if (length < 104 || length > (1 << bits) || length % 8 != 0) throw new IOException("Invalid qcow2 header length");
                }
            }
        } else throw new IOException("Unsupported system disk format: " + format);
        if (size <= 0 || size > MAX_SIZE || size % 512 != 0) throw new IOException("Shared system disk must be sector aligned and at most 1 TiB");
        return size;
    }

    static String prepare(Path devices, Path source, String format, Runnable cancelled) throws IOException {
        virtualSize(source, format);
        String hash = RuntimeBundle.hash(source, cancelled), name = hash + "." + format;
        Path bases = devices.resolve(".bases");
        if (Files.isSymbolicLink(bases)) throw new IOException("System bases must not be a symbolic link");
        Files.createDirectories(bases);
        try (var channel = FileChannel.open(bases.resolve(name + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock = null;
            while (lock == null) {
                cancelled.run();
                try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { }
                if (lock == null) try { Thread.sleep(100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IOException("System base preparation interrupted", e);
                }
            }
            try {
                Path base = bases.resolve(name);
                if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.getFileStore(bases).getUsableSpace() < Files.size(source) + 256L * 1024 * 1024)
                        throw new IOException("Not enough free space for shared system base");
                    Path temp = Files.createTempFile(bases, ".copying-", ".tmp");
                    try {
                        copy(source, temp, cancelled);
                        if (!RuntimeBundle.hash(temp, cancelled).equals(hash)) throw new IOException("System template changed during copy");
                        virtualSize(temp, format);
                        cancelled.run();
                        Files.move(temp, base, StandardCopyOption.ATOMIC_MOVE);
                    } finally { Files.deleteIfExists(temp); }
                } else verify(devices, name, cancelled);
            } finally { lock.release(); }
        }
        return name;
    }

    static Path verify(Path devices, String name, Runnable cancelled) throws IOException {
        if (!name.matches("[0-9a-f]{64}\\.(raw|qcow2)")) throw new IOException("Invalid system base reference");
        Path base = devices.resolve(".bases").resolve(name);
        if (Files.isSymbolicLink(base.getParent()) || !Files.isRegularFile(base, LinkOption.NOFOLLOW_LINKS)
                || !RuntimeBundle.hash(base, cancelled).equals(name.substring(0, 64)))
            throw new IOException("Shared system base missing or changed; preserve devices and restore " + base);
        return base;
    }

    static void copy(Path source, Path target, Runnable cancelled) throws IOException {
        try (var in = FileChannel.open(source, StandardOpenOption.READ);
             var out = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long offset = 0, size = in.size();
            while (offset < size) {
                cancelled.run();
                long n = in.transferTo(offset, Math.min(8 * 1024 * 1024, size - offset), out);
                if (n <= 0) throw new IOException("System base copy made no progress");
                offset += n;
            }
            out.force(true);
        }
    }

    /** Empty qcow2 v3: header, refcount table, refcount block, then one zeroed L1 cluster.
     * See https://www.qemu.org/docs/master/interop/qcow2.html. QEMU owns all subsequent writes. */
    static void createOverlay(Path target, String baseName, long size) throws IOException {
        if (!baseName.matches("[0-9a-f]{64}\\.(raw|qcow2)") || size <= 0 || size > MAX_SIZE || size % 512 != 0)
            throw new IOException("Invalid overlay geometry or base reference");
        byte[] backing = ("../.bases/" + baseName).getBytes(StandardCharsets.UTF_8);
        byte[] format = baseName.substring(65).getBytes(StandardCharsets.US_ASCII);
        var bytes = ByteBuffer.allocate(4 * CLUSTER); // big endian; every metadata cluster has refcount 1
        bytes.putInt(0x514649fb).putInt(3).putLong(128).putInt(backing.length).putInt(16).putLong(size)
                .putInt(0).putInt((int)((size + (1L << 29) - 1) >> 29)).putLong(3L * CLUSTER)
                .putLong(CLUSTER).putInt(1).putInt(0).putLong(0).putLong(0).putLong(0).putLong(0)
                .putInt(4).putInt(104);
        bytes.putInt(0xe2792aca).putInt(format.length).put(format);
        bytes.position(128); bytes.put(backing);
        bytes.putLong(CLUSTER, 2L * CLUSTER);
        for (int i = 0; i < 4; i++) bytes.putShort(2 * CLUSTER + i * 2, (short)1);
        bytes.clear();
        try (var out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) out.write(bytes);
            out.force(true);
        }
    }

    static void verifyOverlay(Path overlay, String name) throws IOException {
        try (var in = new RandomAccessFile(overlay.toFile(), "r")) {
            if (in.readInt() != 0x514649fb || !Set.of(2, 3).contains(in.readInt())) throw new IOException("Invalid system overlay");
            long offset = in.readLong(); int length = in.readInt();
            byte[] expected = ("../.bases/" + name).getBytes(StandardCharsets.UTF_8);
            if (offset < 72 || offset > 2 * 1024 * 1024 || length != expected.length) throw new IOException("System overlay backing reference changed");
            in.seek(offset); byte[] actual = new byte[length]; in.readFully(actual);
            if (!Arrays.equals(expected, actual)) throw new IOException("System overlay backing reference changed");
        }
    }
}
