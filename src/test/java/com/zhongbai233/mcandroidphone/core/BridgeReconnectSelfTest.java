package com.zhongbai233.mcandroidphone.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Three real bridge sessions restart sequence numbers and replace their mapped files. */
public final class BridgeReconnectSelfTest {
    private static final int[] WIDTHS = {4, 4, 6};
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCD";
    private int assertions;

    public static void main(String[] args) throws Exception {
        System.out.println("BridgeReconnectSelfTest passed: " + run() + " assertions");
    }

    static int run() throws Exception {
        BridgeReconnectSelfTest test = new BridgeReconnectSelfTest();
        test.reconnect();
        return test.assertions;
    }

    private void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void reconnect() throws Exception {
        Path directory = Files.createTempDirectory("androidphone-reconnect-test-");
        Path config = directory.resolve("bridge.properties");
        Path[] mappings = new Path[WIDTHS.length];
        CountDownLatch[] observed = new CountDownLatch[WIDTHS.length];
        CountDownLatch[] unmapped = new CountDownLatch[WIDTHS.length];
        Frame[] owned = new Frame[WIDTHS.length];
        for (int index = 0; index < WIDTHS.length; index++) {
            mappings[index] = directory.resolve("frames-session-" + index + ".nv12");
            observed[index] = new CountDownLatch(1);
            unmapped[index] = new CountDownLatch(1);
        }
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(8000);
            Files.writeString(config, "host=127.0.0.1\nport=" + server.getLocalPort()
                    + "\ntoken=" + TOKEN + "\n", StandardCharsets.US_ASCII);
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try {
                    for (int index = 0; index < WIDTHS.length; index++) {
                        int width = WIDTHS[index], frameBytes = width * 4 * 3 / 2;
                        byte[] contents = new byte[frameBytes * 3];
                        Arrays.fill(contents, 0, frameBytes, (byte) (31 + index));
                        Files.write(mappings[index], contents);
                        try (Socket peer = server.accept()) {
                            peer.setSoTimeout(6000);
                            BufferedReader input = new BufferedReader(new InputStreamReader(
                                    peer.getInputStream(), StandardCharsets.US_ASCII));
                            PrintWriter output = new PrintWriter(peer.getOutputStream(), true, StandardCharsets.US_ASCII);
                            if (!("HELLO\t1\t" + TOKEN).equals(input.readLine()))
                                throw new AssertionError("Reconnect authentication mismatch");
                            String encoded = Base64.getEncoder().encodeToString(
                                    mappings[index].toString().getBytes(StandardCharsets.UTF_8));
                            output.print("WELCOME\t1\t" + width + "\t4\t" + frameBytes + "\t3\t" + encoded + "\tNV12\n");
                            output.print("FRAME\t1\t0\t" + (index + 1) + "\n");
                            output.flush();
                            if (!"ACK\t1\t0".equals(input.readLine()))
                                throw new AssertionError("Reconnect must ACK each session's first frame");
                            // Do not close a session until the consumer actually owns its frame.
                            if (!observed[index].await(6, TimeUnit.SECONDS))
                                throw new AssertionError("Consumer did not observe reconnect frame " + index);
                        }
                        deleteAfterUnmap(mappings[index]);
                        unmapped[index].countDown();
                    }
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            try (BridgeClient client = new BridgeClient(config)) {
                require(client.connectionEpoch() == 0, "No epoch before first WELCOME");
                client.start();
                long previousEpoch = 0;
                for (int index = 0; index < WIDTHS.length; index++) {
                    owned[index] = receive(client);
                    require(owned[index] != null, "Reconnect frame delivered: " + client.status());
                    require(client.connected(), "Session remains connected until frame is observed");
                    long epoch = client.connectionEpoch();
                    require(epoch > previousEpoch, "A fresh WELCOME advances the connection epoch");
                    require(epoch == index + 1, "Exactly one epoch per accepted connection");
                    require(owned[index].sequence() == 1, "Sequence 1 is accepted again after reconnect");
                    require(owned[index].width() == WIDTHS[index] && owned[index].height() == 4,
                            "New WELCOME dimensions are used for its frame");
                    verifyOwned(owned[index], index);
                    previousEpoch = epoch;
                    observed[index].countDown();
                    require(unmapped[index].await(4, TimeUnit.SECONDS), "Old mapping can be removed after disconnect");
                    require(!Files.exists(mappings[index]), "Retired mapping is deleted");
                    for (int retained = 0; retained <= index; retained++) verifyOwned(owned[retained], retained);
                }
            } finally {
                for (CountDownLatch latch : observed) latch.countDown();
            }
            serving.get(8, TimeUnit.SECONDS);
            // Owned copies remain valid even after all sessions and the client are closed.
            for (int index = 0; index < WIDTHS.length; index++) verifyOwned(owned[index], index);
        } finally {
            for (CountDownLatch latch : observed) latch.countDown();
            for (Frame frame : owned) if (frame != null) frame.close();
            for (Path path : mappings) deleteAfterUnmap(path);
            Files.deleteIfExists(config);
            Files.deleteIfExists(directory);
        }
    }

    private Frame receive(BridgeClient client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            Frame frame = client.pollFrame();
            if (frame != null) return frame;
            Thread.sleep(5);
        }
        return null;
    }

    private void verifyOwned(Frame frame, int session) {
        require(frame.connectionEpoch() == session + 1, "Owned frame retains its original connection epoch");
        ByteBuffer pixels = frame.pixels();
        require(pixels.isDirect() && pixels.isReadOnly(), "Reconnect pixels retain owned buffer semantics");
        require(pixels.remaining() == WIDTHS[session] * 4 * 3 / 2, "Owned byte length matches this session");
        while (pixels.hasRemaining())
            require((pixels.get() & 255) == 31 + session, "Owned pixels survive later mappings and cleanup");
    }

    private static void deleteAfterUnmap(Path path) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int retry = 0; retry < 120; retry++) {
            try { Files.deleteIfExists(path); return; }
            catch (IOException exception) { lastFailure = exception; Thread.sleep(25); }
        }
        throw lastFailure;
    }
}
