package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real sockets and mapped files: runnable without Minecraft or third-party test libraries. */
public final class CoreSelfTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        ownership();
        validation();
        gpuDescriptors();
        socketRoundTrip();
        assertions += BridgeReconnectSelfTest.run();
        System.out.println("CoreSelfTest passed: " + assertions + " assertions");
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void gpuDescriptors() throws Exception {
        String valid="GPUFRAME\t1\t42\t1080\t1920\t0\t0\t0\t1080\t1920\t123";
        var frame=GpuFrame.parse(valid,0);
        require(frame.width()==1080 && frame.height()==1920 && !frame.topDown(),"GPU geometry/origin");
        String[] parts=valid.split("\t");
        int[] indexes={1,2,3,4,5,6,7,8,9,10};
        String[] values={"0","-1","4097","0","2","-1","1920","2147483647","1921","-1"};
        for(int i=0;i<indexes.length;i++) {
            String[] invalid=parts.clone();invalid[indexes[i]]=values[i];
            try {GpuFrame.parse(String.join("\t",invalid),0);throw new AssertionError("Bad GPU descriptor accepted");}
            catch(IOException expected){assertions++;}
        }
        try {GpuFrame.parse(valid,1);throw new AssertionError("Repeated GPU frame accepted");}
        catch(IOException expected){assertions++;}
    }

    private static void rejects(String line) throws Exception {
        try { Wire.welcome(line); throw new AssertionError("Accepted invalid welcome"); }
        catch (IOException expected) { assertions++; }
    }

    private static void ownership() {
        AtomicInteger releases = new AtomicInteger();
        LatestFrame mailbox = new LatestFrame();
        Frame first = new Frame(ByteBuffer.allocateDirect(24), 4, 4, 1, releases::incrementAndGet);
        Frame second = new Frame(ByteBuffer.allocateDirect(24), 4, 4, 2, releases::incrementAndGet);
        mailbox.publish(first);
        mailbox.publish(second);
        require(releases.get() == 1, "Replaced frame must be released");
        try (Frame received = mailbox.poll()) {
            require(received == second, "Only the latest frame should be delivered");
            require(received.pixels().isDirect(), "Upload must use a direct buffer");
            require(received.pixels().isReadOnly(), "Consumer must not modify pooled data");
            require(mailbox.poll() == null, "Ownership transfers exactly once");
        }
        second.close();
        mailbox.close();
        require(releases.get() == 2, "Release must be idempotent");
        try { second.pixels(); throw new AssertionError("Use-after-release accepted"); }
        catch (IllegalStateException expected) { assertions++; }
    }

    private static void validation() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(Path.of("frame.bin").toAbsolutePath()
                .toString().getBytes(StandardCharsets.UTF_8));
        String welcome = "WELCOME\t1\t1080\t1920\t3110400\t3\t" + encoded + "\tNV12";
        require(Wire.welcome(welcome).bytes() == 3110400, "1080p NV12 size");
        rejects(welcome.replace("1080", "1079"));
        rejects(welcome.replace("3110400", "3110401"));
        rejects(welcome.replace("1920", "99999"));
        rejects(welcome.replace("NV12", "RGBA"));
        rejects(welcome.replace("WELCOME\t1", "WELCOME\t2"));
        rejects(welcome.replace(encoded, "Li4vZnJhbWUuYmlu"));
        require(Wire.notice("FRAME\t2\t0\t100", 1).sequence() == 2, "Frame descriptor");
        for (String line : new String[]{"FRAME\t1\t0\t100", "FRAME\t2\t3\t100", "FRAME\t2\t0\t-1"}) {
            try { Wire.notice(line, 1); throw new AssertionError("Accepted invalid notice"); }
            catch (IOException expected) { assertions++; }
        }
        try {
            Wire.readLine(new ByteArrayInputStream("x".repeat(Wire.MAX_LINE).getBytes(StandardCharsets.US_ASCII)));
            throw new AssertionError("Unbounded line accepted");
        } catch (IOException expected) { assertions++; }
        try (BridgeClient client = new BridgeClient(Path.of("unused.properties"))) {
            try { client.touch("DOWN", Double.NaN, 0); throw new AssertionError("NaN touch"); }
            catch (IllegalArgumentException expected) { assertions++; }
            try { client.text("a".repeat(4097)); throw new AssertionError("Unbounded text"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
    }

    private static void socketRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("androidphone-core-test-");
        Path mapped = dir.resolve("frames.bin");
        Path config = dir.resolve("bridge.properties");
        String token = "abcdefghijklmnopqrstuvwxyz0123456789ABCD";
        CountDownLatch inputReceived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             FileChannel writer = FileChannel.open(mapped, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            writer.position(71).write(ByteBuffer.wrap(new byte[]{0}));
            Files.writeString(config, "host=127.0.0.1\nport=" + server.getLocalPort() + "\ntoken=" + token + "\n");
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));
                    PrintWriter out = new PrintWriter(peer.getOutputStream(), true, StandardCharsets.US_ASCII);
                    if (!("HELLO\t1\t" + token).equals(in.readLine())) throw new AssertionError("Authentication mismatch");
                    out.print("WELCOME\t1\t4\t4\t24\t3\t" + Base64.getEncoder().encodeToString(
                            mapped.toString().getBytes(StandardCharsets.UTF_8)) + "\tNV12\n");
                    out.flush();
                    for (int sequence = 1; sequence <= 80; sequence++) {
                        int slot = sequence % 3;
                        byte[] data = new byte[24];
                        java.util.Arrays.fill(data, (byte) sequence);
                        ByteBuffer source = ByteBuffer.wrap(data);
                        long offset = slot * 24L;
                        while (source.hasRemaining()) offset += writer.write(source, offset);
                        out.print("FRAME\t" + sequence + "\t" + slot + "\t" + System.nanoTime() + "\n");
                        out.flush();
                        String expectedAck = "ACK\t" + sequence + "\t" + slot;
                        while (true) {
                            String line = in.readLine();
                            if (line == null) throw new EOFException();
                            if (line.equals(expectedAck)) break;
                            if (line.equals("KEY\tBACK")) inputReceived.countDown();
                            else throw new AssertionError("Unexpected input: " + line);
                        }
                        Thread.sleep(2);
                    }
                    releaseServer.await(5, TimeUnit.SECONDS);
                } catch (Exception ex) { throw new CompletionException(ex); }
            });
            try (BridgeClient client = new BridgeClient(config)) {
                client.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                long last = -1;
                boolean keySent = false;
                while (last < 80 && System.nanoTime() < deadline) {
                    if (client.connected() && !keySent) { client.key("BACK"); keySent = true; }
                    try (Frame frame = client.pollFrame()) {
                        if (frame != null) {
                            require(frame.sequence() > last, "Frames must advance");
                            require(frame.width() == 4 && frame.height() == 4, "Dimensions preserved");
                            ByteBuffer pixels = frame.pixels();
                            while (pixels.hasRemaining()) require((pixels.get() & 255) == frame.sequence(), "Torn mapped frame");
                            last = frame.sequence();
                        }
                    }
                    Thread.sleep(7);
                }
                require(last == 80, "Latest frame eventually delivered: " + client.status());
                require(inputReceived.await(1, TimeUnit.SECONDS), "Control channel round trip");
                releaseServer.countDown();
            } finally { releaseServer.countDown(); }
            serving.get(6, TimeUnit.SECONDS);
        } finally {
            // Arena mapping must close after disconnect, including on Windows.
            IOException lastDeleteFailure = null;
            for (int retry = 0; retry < 40; retry++) {
                try { Files.deleteIfExists(mapped); lastDeleteFailure = null; break; }
                catch (IOException ex) { lastDeleteFailure = ex; Thread.sleep(25); }
            }
            if (lastDeleteFailure != null) throw lastDeleteFailure;
            Files.deleteIfExists(config);
            Files.deleteIfExists(dir);
        }
    }
}
