"""Interactive diagnostic NV12 picture, deliberately not an Android substitute."""
import threading
import time


class PatternBackend:
    def __init__(self, width, height, fps):
        self.width, self.height, self.fps = width, height, fps
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.position = (0.5, 0.5)
        self.pressed = False
        self.page = 0
        self.text_value = ""
        self.thread = None
        self.error = None
        self.base = bytearray(bytes([32]) * (width * height) + bytes([128, 128]) * (width * height // 4))
        # Luma ramp and colored app tiles; all samples stay in limited range.
        for y in range(height):
            level = 40 + int(60 * y / height)
            self.base[y * width:(y + 1) * width] = bytes([level]) * width
        for index, uv in enumerate(((90, 220), (170, 70), (210, 150))):
            x0 = index * width // 3 // 2 * 2
            x1 = (index + 1) * width // 3 // 2 * 2
            for y in range(height // 8, height // 3):
                self.base[y * width + x0:y * width + x1] = bytes([130 + index * 25]) * (x1 - x0)
            for y in range((height // 8) // 2, (height // 3) // 2):
                start = width * height + y * width
                self.base[start + x0:start + x1] = bytes(uv) * ((x1 - x0) // 2)

    def frame(self, tick):
        with self.lock:
            u, v = self.position
            pressed, page, text_len = self.pressed, self.page, len(self.text_value)
        frame = self.base.copy()
        width, height = self.width, self.height
        radius = max(2, min(width, height) // 40)
        px, py = round(u * (width - 1)), round(v * (height - 1))
        x0, x1 = max(0, px - radius), min(width, px + radius + 1)
        for y in range(max(0, py - radius), min(height, py + radius + 1)):
            frame[y * width + x0:y * width + x1] = bytes([235 if pressed else 190]) * (x1 - x0)
        for y in range(height * 3 // 4, height * 4 // 5):
            frame[y * width:(y + 1) * width] = bytes([70 + page * 50]) * width
        # Moving vertical clock and text-length marker expose stale frames/input.
        x = tick % width
        for y in range(height * 5 // 6, height):
            frame[y * width + x] = 235
        for y in range(height * 4 // 5, height * 5 // 6):
            count = min(width, text_len * max(1, width // 32))
            frame[y * width:y * width + count] = bytes([220]) * count
        return frame

    def start(self, publish):
        def run():
            try:
                tick, target = 0, time.monotonic()
                while not self.stop_event.is_set():
                    publish(self.frame(tick), time.monotonic_ns())
                    tick += 1
                    target += 1 / self.fps
                    self.stop_event.wait(max(0, target - time.monotonic()))
                    if target < time.monotonic() - 1 / self.fps:
                        target = time.monotonic()
            except Exception as exc:
                self.error = exc
        self.thread = threading.Thread(target=run, daemon=True, name="pattern-frames")
        self.thread.start()

    def check_health(self):
        if self.error:
            raise RuntimeError("pattern source failed") from self.error

    def stop(self):
        self.stop_event.set()
        if self.thread and threading.current_thread() is not self.thread:
            self.thread.join(timeout=2)

    def touch(self, action, u, v):
        with self.lock:
            self.position = (u, v)
            self.pressed = action != "UP"

    def key(self, key):
        with self.lock:
            self.page = 0 if key == "HOME" else (self.page + (1 if key == "APP_SWITCH" else -1)) % 3

    def text(self, value):
        with self.lock:
            self.text_value = value
