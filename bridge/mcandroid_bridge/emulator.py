"""Native-resolution RGB screenshot RPC -> persistent FFmpeg -> NV12 frames.

This proof of concept performs copies and CPU color conversion. It is not a
zero-copy QEMU display backend and does not encode/decode MP4 or H.264.
"""
from __future__ import annotations

import configparser
from pathlib import Path
import subprocess
import threading
import time


def read_token(token=None, token_file=None, discovery_file=None):
    if sum(bool(value) for value in (token, token_file, discovery_file)) > 1:
        raise ValueError("select only one emulator token source")
    if token_file:
        token = Path(token_file).read_text(encoding="utf-8").strip()
    if discovery_file:
        content = Path(discovery_file).read_text(encoding="utf-8")
        config = configparser.ConfigParser(interpolation=None)
        config.read_string("[emulator]\n" + content)
        token = config["emulator"].get("grpc.token", "").strip()
    if not token or any(c.isspace() for c in token):
        raise ValueError("emulator backend requires a non-empty Bearer token (token file or discovery INI)")
    return token


def read_exactly(stream, size):
    result = bytearray()
    while len(result) < size:
        chunk = stream.read(size - len(result))
        if not chunk:
            if not result:
                return None
            raise RuntimeError("FFmpeg produced a truncated NV12 frame")
        result.extend(chunk)
    return result


def write_all(stream, data):
    view = memoryview(data)
    while view:
        count = stream.write(view)
        if not count:
            raise BrokenPipeError("FFmpeg stopped accepting raw video input")
        view = view[count:]


class FFmpegConverter:
    def __init__(self, executable, width, height, fps, publish, bottom_up=False, input_format="rgb24"):
        if input_format not in ("rgb24", "bgr24", "rgb0", "bgr0", "rgba", "bgra"):
            raise ValueError("FFmpeg input format must be rgb24, bgr24, rgb0, bgr0, rgba or bgra")
        filters = (["vflip"] if bottom_up else []) + ["scale=in_range=full:out_range=limited:out_color_matrix=bt709", "format=nv12"]
        command = [str(executable), "-hide_banner", "-loglevel", "error", "-nostdin", "-threads", "1",
                   "-filter_threads", "1", "-f", "rawvideo", "-pixel_format", input_format, "-video_size", f"{width}x{height}",
                   "-framerate", str(fps), "-probesize", "32", "-analyzeduration", "0", "-i", "pipe:0", "-an",
                   "-vf", ",".join(filters), "-threads", "1", "-pix_fmt", "nv12", "-color_range", "tv",
                   "-colorspace", "bt709", "-f", "rawvideo", "-flush_packets", "1", "pipe:1"]
        options = {"creationflags": subprocess.CREATE_NO_WINDOW} if hasattr(subprocess, "CREATE_NO_WINDOW") else {}
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, bufsize=0, **options)
        self.error = None
        self.stderr_tail = b""
        self.stopping = False
        self.frame_bytes = width * height * 3 // 2
        self.input_bytes = width * height * (3 if input_format in ("rgb24", "bgr24") else 4)
        self.completion = threading.Condition()
        self.submitted_frames = self.published_frames = 0

        def output():
            try:
                while True:
                    frame = read_exactly(self.process.stdout, self.frame_bytes)
                    if frame is None:
                        if not self.stopping:
                            raise RuntimeError("FFmpeg output ended")
                        break
                    publish(frame, time.monotonic_ns())
                    with self.completion:
                        self.published_frames += 1
                        self.completion.notify_all()
            except Exception as exc:
                if not self.stopping:
                    self.error = exc

        def errors():
            while True:
                data = self.process.stderr.read(4096)
                if not data:
                    break
                self.stderr_tail = (self.stderr_tail + data)[-8192:]

        self.reader = threading.Thread(target=output, daemon=True, name="ffmpeg-nv12")
        self.errors = threading.Thread(target=errors, daemon=True, name="ffmpeg-errors")
        self.reader.start()
        self.errors.start()

    def submit(self, pixels):
        if len(pixels) != self.input_bytes:
            raise ValueError(f"raw video frame size mismatch: expected {self.input_bytes} bytes, got {len(pixels)}")
        self.check_health()
        write_all(self.process.stdin, pixels)
        with self.completion:
            self.submitted_frames += 1

    def wait_for_output(self, timeout=5):
        """Prevent live display frames queuing inside FFmpeg's async pipeline."""
        deadline = time.monotonic() + timeout
        with self.completion:
            target = self.submitted_frames
            while self.published_frames < target:
                if self.stopping:
                    return False
                self.check_health()
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("FFmpeg did not finish the submitted display frame")
                self.completion.wait(min(.1, remaining))
            return not self.stopping

    def check_health(self):
        if self.error:
            raise RuntimeError("FFmpeg converter failed: " + self.stderr_tail.decode("utf-8", errors="replace")) from self.error
        if self.process.poll() is not None and not self.stopping:
            raise RuntimeError("FFmpeg exited: " + self.stderr_tail.decode("utf-8", errors="replace"))

    def close(self):
        self.stopping = True
        with self.completion:
            self.completion.notify_all()
        # Rawvideo may block in read(0) despite SIGTERM on POSIX. EOF releases it.
        self.process.stdin.close()
        if self.process.poll() is None:
            self.process.terminate()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=3)
        self.reader.join(timeout=2)
        self.errors.join(timeout=2)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()


class EmulatorBackend:
    def __init__(self, width, height, fps, grpc_port=8554, token=None, ffmpeg="ffmpeg", bottom_up=False):
        self.width, self.height, self.fps = width, height, fps
        self.grpc_port, self.token, self.ffmpeg, self.bottom_up = grpc_port, token, ffmpeg, bottom_up
        self.channel = None
        self.converter = None
        self.stream = None
        self.thread = None
        self.error = None
        self.stop_event = threading.Event()

    def _validate(self, image):
        width = image.format.width or image.width
        height = image.format.height or image.height
        if (width, height) != (self.width, self.height):
            raise RuntimeError(f"native emulator display is {width}x{height}; bridge is {self.width}x{self.height}. "
                               "Restart with matching --width/--height and keep orientation fixed.")
        if image.format.format != 2 or len(image.image) != self.width * self.height * 3:
            raise RuntimeError("emulator did not return tightly packed RGB888")

    def start(self, publish):
        try:
            import grpc
            from . import emulator_wire as wire
        except ImportError as exc:
            raise RuntimeError("Install bridge/requirements.txt to use the emulator backend") from exc
        self.wire = wire
        self.metadata = (("authorization", "Bearer " + self.token),)
        self.channel = grpc.insecure_channel(f"127.0.0.1:{self.grpc_port}", options=[
            ("grpc.max_receive_message_length", 4096 * 4096 * 3 + 1024 * 1024),
            ("grpc.max_send_message_length", 64 * 1024),
        ])
        grpc.channel_ready_future(self.channel).result(timeout=10)
        self.controller = wire.Controller(self.channel)
        # Zero requested dimensions explicitly select the emulator's native size.
        self.request = wire.ImageFormat(format=2)
        first = self.controller.getScreenshot(self.request, timeout=15, metadata=self.metadata)
        if not first.image:
            raise RuntimeError("emulator display is inactive; wake/unlock it before starting bridge")
        self._validate(first)
        self.converter = FFmpegConverter(self.ffmpeg, self.width, self.height, self.fps, publish, self.bottom_up)

        def run():
            try:
                self.converter.submit(first.image)
                last = time.monotonic()
                self.stream = self.controller.streamScreenshot(self.request, metadata=self.metadata)
                for shot in self.stream:
                    if self.stop_event.is_set():
                        break
                    if not shot.image:
                        continue
                    now = time.monotonic()
                    if now - last < 1 / self.fps:
                        continue
                    self._validate(shot)
                    self.converter.submit(shot.image)
                    last = now
                if not self.stop_event.is_set():
                    raise RuntimeError("emulator screenshot stream ended")
            except Exception as exc:
                if not self.stop_event.is_set():
                    self.error = exc

        self.thread = threading.Thread(target=run, daemon=True, name="emulator-screenshots")
        self.thread.start()

    def check_health(self):
        if self.error:
            raise RuntimeError("emulator screenshot stream failed") from self.error
        if self.converter:
            self.converter.check_health()

    def touch(self, action, u, v):
        touch = self.wire.Touch(x=round(u * (self.width - 1)), y=round(v * (self.height - 1)),
                                identifier=0, pressure=0 if action == "UP" else 1)
        self.controller.sendTouch(self.wire.TouchEvent(touches=[touch]), timeout=2, metadata=self.metadata)

    def key(self, key):
        name = {"BACK": "GoBack", "HOME": "GoHome", "APP_SWITCH": "AppSwitch"}[key]
        self.controller.sendKey(self.wire.KeyboardEvent(key=name, eventType=2), timeout=2, metadata=self.metadata)

    def text(self, value):
        if any(not 32 <= ord(character) < 127 for character in value):
            raise ValueError("emulator sendKey TEXT supports printable ASCII only; IME integration is not implemented")
        # Upstream advises staying below 1 KiB to avoid overflowing Android input.
        if len(value) > 256:
            raise ValueError("emulator text is limited to 256 characters per command")
        self.controller.sendKey(self.wire.KeyboardEvent(text=value), timeout=2, metadata=self.metadata)

    def stop(self):
        self.stop_event.set()
        if self.stream:
            self.stream.cancel()
        if self.channel:
            self.channel.close()
        if self.converter:
            self.converter.close()
        if self.thread:
            self.thread.join(timeout=3)
