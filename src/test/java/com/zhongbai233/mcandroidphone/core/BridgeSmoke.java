package com.zhongbai233.mcandroidphone.core;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Cross-language smoke: args are bridge.properties, number of frames, optional --touch. */
public final class BridgeSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Expected bridge.properties path");
        int target = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(45);
        int received = 0, width = 0, height = 0;
        long last = -1, checksum = 0;
        try (BridgeClient client = new BridgeClient(Path.of(args[0]))) {
            client.start();
            while (received < target && System.nanoTime() < deadline) {
                try (Frame frame = client.pollFrame()) {
                    if (frame != null) {
                        width = frame.width(); height = frame.height(); last = frame.sequence();
                        if (!frame.pixels().isDirect() || frame.pixels().remaining() != width * height * 3 / 2)
                            throw new AssertionError("Invalid shared frame");
                        checksum += Byte.toUnsignedInt(frame.pixels().get(0));
                        received++;
                        if (args.length > 2 && args[2].equals("--touch")) {
                            if (received == 2) client.touch("DOWN", .4, .4);
                            if (received == 4) client.touch("MOVE", .6, .6);
                            if (received == 6) client.touch("UP", .6, .6);
                        }
                    }
                }
                Thread.sleep(2);
            }
            if (received < target) throw new AssertionError("Only " + received + " frames: " + client.status());
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf(java.util.Locale.ROOT,
                "{\"frames\":%d,\"width\":%d,\"height\":%d,\"lastSequence\":%d,\"seconds\":%.3f,\"checksum\":%d}%n",
                received, width, height, last, seconds, checksum);
    }
}
