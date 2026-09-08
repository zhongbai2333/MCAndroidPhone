package com.zhongbai233.mcandroidphone.core;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

final class Wire {
    static final int MAX_LINE = 8192;

    private Wire() {}

    static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < MAX_LINE; i++) {
            int value = input.read();
            if (value < 0) throw new EOFException("Bridge disconnected");
            if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII);
            if (value < 32 || value > 126) {
                if (value != '\t') throw new IOException("Non-ASCII bridge protocol");
            }
            bytes.write(value);
        }
        throw new IOException("Bridge line exceeds limit");
    }

    static Welcome welcome(String line) throws IOException {
        try {
            String[] parts = line.split("\t", -1);
            if (parts.length != 8 || !parts[0].equals("WELCOME") || !parts[1].equals("1")
                    || !parts[7].equals("NV12")) throw new IllegalArgumentException();
            int width = Integer.parseInt(parts[2]);
            int height = Integer.parseInt(parts[3]);
            int bytes = Integer.parseInt(parts[4]);
            int slots = Integer.parseInt(parts[5]);
            if (width < 2 || height < 2 || width > 4096 || height > 4096
                    || (width & 1) != 0 || (height & 1) != 0 || slots != 3
                    || bytes != (long) width * height * 3 / 2) throw new IllegalArgumentException();
            Path path = Path.of(new String(Base64.getDecoder().decode(parts[6]), StandardCharsets.UTF_8));
            if (!path.isAbsolute()) throw new IllegalArgumentException();
            return new Welcome(width, height, bytes, slots, path.normalize());
        } catch (RuntimeException ex) {
            throw new IOException("Invalid bridge welcome", ex);
        }
    }

    static Notice notice(String line, long previousSequence) throws IOException {
        try {
            String[] parts = line.split("\t", -1);
            if (parts.length != 4 || !parts[0].equals("FRAME")) throw new IllegalArgumentException();
            long sequence = Long.parseLong(parts[1]);
            int slot = Integer.parseInt(parts[2]);
            long timestamp = Long.parseLong(parts[3]);
            if (sequence <= previousSequence || slot < 0 || slot >= 3 || timestamp < 0)
                throw new IllegalArgumentException();
            return new Notice(sequence, slot, timestamp);
        } catch (RuntimeException ex) {
            throw new IOException("Invalid bridge frame descriptor", ex);
        }
    }

    record Welcome(int width, int height, int bytes, int slots, Path path) {}
    record Notice(long sequence, int slot, long timestamp) {}
}
