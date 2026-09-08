package com.zhongbai233.mcandroidphone.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Local authenticated control plus leased shared-memory frames; never blocks the render thread. */
public final class BridgeClient implements AutoCloseable {
    private final Path config;
    private final LatestFrame latest = new LatestFrame();
    private final java.util.concurrent.atomic.AtomicReference<GpuFrame> latestGpu = new java.util.concurrent.atomic.AtomicReference<>();
    private volatile long lastGpuPoll;
    private volatile boolean gpuTransport;
    public boolean gpuTransport() { return gpuTransport; }
    public GpuFrame pollGpuFrame() {
        lastGpuPoll = System.nanoTime();
        return latestGpu.getAndSet(null);
    }
    private void clearGpu() {
        GpuFrame previous = latestGpu.getAndSet(null);
        if (previous != null) previous.close();
    }
    private record InputSession(Socket socket, ArrayBlockingQueue<String> queue) {}
    private volatile InputSession inputSession;
    private volatile boolean running, closed, connected;
    private volatile long connectionEpoch;
    private volatile String status = "Stopped";
    private volatile Socket socket;
    private Thread worker;

    public BridgeClient(Path config) { this.config = config.toAbsolutePath(); }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Client is closed; create a new client");
        if (running) return;
        running = true;
        worker = Thread.ofPlatform().daemon().name("androidphone-frames").start(this::run);
    }

    public Frame pollFrame() { return latest.poll(); }
    public boolean connected() { return connected; }
    /** Increases for every accepted WELCOME, including reconnects at the same resolution. */
    public long connectionEpoch() { return connectionEpoch; }
    public String status() { return status; }

    public void touch(String phase, double u, double v) {
        if (!Set.of("DOWN", "MOVE", "UP").contains(phase) || !Double.isFinite(u)
                || !Double.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1)
            throw new IllegalArgumentException("Invalid touch");
        enqueue("TOUCH\t" + phase + "\t" + u + "\t" + v);
    }

    public void key(String key) {
        if (!Set.of("BACK", "HOME", "APP_SWITCH").contains(key))
            throw new IllegalArgumentException("Unsupported Android key");
        enqueue("KEY\t" + key);
    }

    public void text(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw new IllegalArgumentException("Text exceeds 4096 UTF-8 bytes");
        enqueue("TEXT\t" + Base64.getEncoder().encodeToString(bytes));
    }

    private void enqueue(String command) {
        InputSession active = inputSession;
        if (active == null) return;
        // Do not silently lose UP; a full input queue terminates the session and bridge releases touch.
        // Capture this session's queue and socket together: reconnect must never replay an old gesture.
        if (!active.queue().offer(command)) {
            try { active.socket().close(); } catch (IOException ignored) { }
        }
    }

    private void run() {
        while (running) {
            try {
                status = "Connecting: " + config;
                session();
            } catch (Exception ex) {
                if (running) status = "Waiting for bridge: " + ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : " - " + ex.getMessage());
            } finally {
                connected = false;
                inputSession = null;
                disconnectSocket();
                latest.close();
                clearGpu();
            }
            if (running) {
                try { Thread.sleep(1000); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
            }
        }
        status = "Stopped";
    }

    private void session() throws IOException, InterruptedException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(config)) { properties.load(in); }
        InetAddress address = InetAddress.getByName(properties.getProperty("host", "127.0.0.1"));
        if (!address.isLoopbackAddress()) throw new IOException("Bridge must be on loopback");
        int port = Integer.parseInt(properties.getProperty("port", "18765"));
        String token = properties.getProperty("token", "");
        if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new IOException("Missing or invalid bridge token");
        if ("d3d11".equals(properties.getProperty("transport"))) {
            gpuSession(address, port, token);
            return;
        }
        gpuTransport = false;
        try (Socket active = new Socket()) {
            socket = active;
            if (!running) return;
            active.connect(new InetSocketAddress(address, port), 2000);
            active.setTcpNoDelay(true);
            active.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(active.getInputStream());
            OutputStream out = new BufferedOutputStream(active.getOutputStream());
            send(out, "HELLO\t1\t" + token);
            Wire.Welcome welcome = Wire.welcome(Wire.readLine(in));
            // A static Android desktop may legitimately produce no changed frames for minutes.
            active.setSoTimeout(0);
            try (Arena arena = Arena.ofConfined();
                 FileChannel channel = FileChannel.open(welcome.path(), StandardOpenOption.READ)) {
                long length = (long) welcome.bytes() * welcome.slots();
                if (channel.size() != length) throw new IOException("Shared frame file has wrong size");
                ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, length, arena).asByteBuffer();
                ArrayBlockingQueue<ByteBuffer> pool = new ArrayBlockingQueue<>(3);
                for (int i = 0; i < 3; i++) pool.add(ByteBuffer.allocateDirect(welcome.bytes()));
                if (!running) return;
                ArrayBlockingQueue<String> sessionQueue = new ArrayBlockingQueue<>(128);
                inputSession = new InputSession(active, sessionQueue);
                long epoch = ++connectionEpoch;
                connected = true;
                status = "Connected " + welcome.width() + "x" + welcome.height() + " NV12";
                Thread sender = Thread.ofPlatform().daemon().name("androidphone-input").start(() -> {
                    try {
                        while (running && !active.isClosed()) {
                            String command = sessionQueue.poll(250, TimeUnit.MILLISECONDS);
                            if (command != null) send(out, command);
                        }
                    } catch (IOException ex) {
                        try { active.close(); } catch (IOException ignored) { }
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                });
                try {
                    long previousSequence = -1;
                    while (running && !active.isClosed()) {
                        Wire.Notice notice = Wire.notice(Wire.readLine(in), previousSequence);
                        previousSequence = notice.sequence();
                        ByteBuffer pixels = pool.poll();
                        if (pixels != null) {
                            pixels.clear();
                            pixels.put(mapped.slice(notice.slot() * welcome.bytes(), welcome.bytes()));
                            pixels.flip();
                            latest.publish(new Frame(pixels, welcome.width(), welcome.height(), notice.sequence(), epoch,
                                    () -> pool.offer(pixels)));
                        }
                        // ACK only after copy finishes; the bridge may now reuse this slot.
                        send(out, "ACK\t" + notice.sequence() + "\t" + notice.slot());
                    }
                } finally {
                    connected = false;
                    inputSession = null;
                    active.close();
                    sender.interrupt();
                    sender.join(1000);
                }
            }
        }
    }

    private void gpuSession(InetAddress address, int port, String token) throws IOException, InterruptedException {
        gpuTransport = true;
        long sourceProcess = 0;
        try (Socket active = new Socket()) {
            socket = active;
            if (!running) return;
            active.connect(new InetSocketAddress(address, port), 2000);
            active.setTcpNoDelay(true);
            active.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(active.getInputStream());
            OutputStream out = new BufferedOutputStream(active.getOutputStream());
            send(out, "HELLO\t2\t" + token);
            String[] welcome = Wire.readLine(in).split("\t", -1);
            if (welcome.length != 4 || !welcome[0].equals("WELCOME") || !welcome[1].equals("2") || !welcome[3].equals("D3D11"))
                throw new IOException("Invalid GPU welcome");
            long pid = Long.parseLong(welcome[2]);
            if (pid <= 0 || pid > 0xffffffffL) throw new IOException("Invalid GPU bridge PID");
            sourceProcess = Win32Handles.openSource((int)pid);
            active.setSoTimeout(0);
            ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(128);
            inputSession = new InputSession(active, queue);
            long epoch = ++connectionEpoch;
            connected = true;
            status = "Connected D3D11 (waiting for GPU frame)";
            Thread sender = Thread.ofPlatform().daemon().name("androidphone-gpu-input").start(() -> {
                try {
                    while (running && !active.isClosed()) {
                        if (System.nanoTime() - lastGpuPoll > TimeUnit.MILLISECONDS.toNanos(500)) clearGpu();
                        String command = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (command != null) send(out, command);
                    }
                } catch (Exception error) {
                    try { active.close(); } catch (IOException ignored) {}
                }
            });
            try {
                long previous = 0;
                while (running && !active.isClosed()) {
                    GpuFrame.Notice notice = GpuFrame.parse(Wire.readLine(in), previous);
                    previous = notice.sequence();
                    Runnable ack = () -> {
                        try { send(out, "GPUACK\t" + notice.sequence()); }
                        catch (IOException error) { try { active.close(); } catch (IOException ignored) {} }
                    };
                    // When no phone is being drawn, immediately return the producer lease.
                    // No hidden-window GPU copy, and no stalled Android waiting for MC to render.
                    if (System.nanoTime() - lastGpuPoll > TimeUnit.MILLISECONDS.toNanos(500)) {
                        ack.run();
                        continue;
                    }
                    long handle = Win32Handles.duplicate(sourceProcess, notice.handle());
                    GpuFrame frame = new GpuFrame(notice, handle, epoch, ack);
                    GpuFrame old = latestGpu.getAndSet(frame);
                    if (old != null) old.close();
                    status = "Connected " + notice.width() + "x" + notice.height() + " D3D11";
                }
            } finally {
                connected = false;
                inputSession = null;
                active.close();
                clearGpu();
                sender.interrupt();
                sender.join(1000);
            }
        } finally { Win32Handles.close(sourceProcess); }
    }

    private static void send(OutputStream out, String line) throws IOException {
        synchronized (out) {
            out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    private void disconnectSocket() {
        Socket active = socket;
        if (active != null) try { active.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        closed = true;
        running = false;
        connected = false;
        inputSession = null;
        disconnectSocket();
        if (worker != null) worker.interrupt();
        latest.close();
                clearGpu();
    }
}
