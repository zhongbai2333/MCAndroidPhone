"""QEMU VNC framebuffer -> persistent FFmpeg RGB/NV12 conversion.

No Android SDK, gRPC, video encoding, or implicit display scaling is involved.
The guest must expose an even native display size no larger than 4096x4096.
"""
from __future__ import annotations

import math
import threading
import time

from .emulator import FFmpegConverter
from .rfb import RfbClient, validate_dimensions


# QMP hardware qcodes map to Linux KEY_BACK (158) / KEY_HOMEPAGE (172),
# which AOSP Generic.kl maps to BACK / HOME. QEMU's VNC keysym lookup does
# not reliably expose XF86 navigation keys. Alt+Tab is a guest desktop
# shortcut; recent-task behavior can vary by image.
# https://github.com/qemu/qemu/blob/master/pc-bios/keymaps/en-us
# https://source.android.com/docs/core/interaction/input/key-layout-files
NAVIGATION_QCODES = {"BACK": ("ac_back",), "HOME": ("ac_home",), "APP_SWITCH": ("alt", "tab")}


class QemuBackend:
    def __init__(self, width, height, fps, vnc_port=5900, ffmpeg="ffmpeg", qmp_port=None, color_order="rgb",
                 input_mode="mouse"):
        validate_dimensions(width, height)
        if not math.isfinite(fps) or not 0 < fps <= 120:
            raise ValueError("QEMU frame rate must be between 0 and 120 FPS")
        if qmp_port is not None and (not isinstance(qmp_port, int) or not 1 <= qmp_port <= 65535):
            raise ValueError("QMP port must be between 1 and 65535")
        if color_order not in ("rgb", "bgr"):
            raise ValueError("QEMU color order must be rgb or bgr")
        if input_mode not in ("mouse", "touchscreen"):
            raise ValueError("QEMU input mode must be mouse or touchscreen")
        if input_mode == "touchscreen" and qmp_port is None:
            raise ValueError("QEMU touchscreen requires --qmp-port")
        self.width, self.height, self.fps = width, height, fps
        self.ffmpeg = ffmpeg
        self.color_order = color_order
        self.rfb = RfbClient(vnc_port)
        self.qmp_port = qmp_port
        self._qmp_lock = threading.Lock()
        self.input_mode = input_mode
        self._touch_lock = threading.RLock()
        self._touch_active = False
        self._touch_id = -1
        self.resize_handler = None
        self.publish = None
        self.converter = None
        self._converter_lock = threading.Lock()
        self.stop_event = threading.Event()
        self.first_frame = threading.Event()
        self.frames_received = 0
        self.last_frame_time = None
        self.thread = None
        self.error = None
        self._started = False
        self._epoch = 0

    def set_resize_handler(self, handler):
        if self._started:
            raise RuntimeError("Set the QEMU resize handler before starting the backend")
        if not callable(handler):
            raise TypeError("QEMU resize handler must be callable")
        self.resize_handler = handler

    def _close_converter(self):
        with self._converter_lock:
            # Every retirement invalidates delayed output, including a VNC
            # failure before its first frame (not only display-size changes).
            self._epoch += 1
            converter, self.converter = self.converter, None
        if converter is not None:
            converter.close()

    def _resize(self, width, height):
        # RfbClient has released the pointer but still uses old coordinates.
        # Reconfigure synchronously drains old input and returns an epoch-bound
        # publish callback. Never hold a backend lock across this callback.
        if self.resize_handler is not None:
            publish = self.resize_handler(width, height)
            if not callable(publish):
                raise TypeError("QEMU resize handler must return a publish callback")
        elif (width, height) == (self.width, self.height):
            publish = self.publish
        else:
            raise RuntimeError(f"QEMU native display is {width}x{height}; install a resize handler "
                               "to reconfigure bridge dimensions without scaling")
        self._release_touch()
        self._close_converter()
        epoch = self._epoch
        self.width, self.height = width, height
        if self.stop_event.is_set():
            return

        def converted(frame, timestamp):
            if self.stop_event.is_set() or epoch != self._epoch:
                return False
            result = publish(frame, timestamp)
            self.frames_received += 1
            self.last_frame_time = time.monotonic()
            self.first_frame.set()
            return result

        # Android-x86 9's Bochs software framebuffer can swap red and blue.
        # Correct it in the existing native conversion, without a Python pixel copy.
        converter = FFmpegConverter(self.ffmpeg, width, height, self.fps, converted,
                                    input_format=self._converter_input_format())
        with self._converter_lock:
            stopping = self.stop_event.is_set()
            if not stopping:
                self.converter = converter
        if stopping:
            converter.close()

    def _converter_input_format(self):
        return self.color_order + "24"

    def start(self, publish):
        if self._started or self.stop_event.is_set():
            raise RuntimeError("QEMU backend instances may only start once")
        self._started, self.publish = True, publish
        try:
            if self.qmp_port is not None:
                self._qmp_transaction()
            self.rfb.connect(self._resize)
        except BaseException:
            self.stop()
            raise

        def run():
            full_update = True
            target = time.monotonic()
            try:
                while not self.stop_event.wait(max(0, target - time.monotonic())):
                    self.rfb.request_update(incremental=not full_update)
                    changed, resized = self.rfb.read_update(self._resize, idle=not full_update)
                    full_update = resized or (full_update and not changed)
                    if changed and not self.stop_event.is_set():
                        with self._converter_lock:
                            converter = self.converter
                        if converter is not None:
                            converter.submit(self.rfb.rgb_frame())
                    # One outstanding request at a time, at most fps pulls.
                    # Never skip rectangles or queue stale framebuffer updates.
                    target = time.monotonic() + 1 / self.fps
            except Exception as error:
                if not self.stop_event.is_set():
                    self.error = error
            finally:
                self._release_touch(best_effort=True)
                self.rfb.close()
                self._close_converter()

        self.thread = threading.Thread(target=run, daemon=True, name="qemu-vnc-frames")
        self.thread.start()

    def check_health(self):
        if self.error:
            raise RuntimeError(f"QEMU VNC backend failed: {self.error}") from self.error
        with self._converter_lock:
            converter = self.converter
        if converter is not None:
            converter.check_health()

    def touch(self, action, u, v):
        if self.input_mode == "mouse":
            self.rfb.touch(action, u, v)
            return
        if action not in ("DOWN", "MOVE", "UP") or not all(math.isfinite(c) and 0 <= c <= 1 for c in (u, v)):
            raise ValueError("QEMU touch requires DOWN/MOVE/UP and normalized finite coordinates")
        with self._touch_lock:
            if self.stop_event.is_set():
                raise RuntimeError("QEMU backend is stopped")
            if action == "UP":
                self._release_touch()
                return
            if self.error is not None:
                raise RuntimeError(f"QEMU VNC backend failed: {self.error}") from self.error
            if action == "DOWN":
                if self._touch_active:
                    raise ValueError("QEMU touch is already down")
                self._touch_id = (self._touch_id + 1) % 65536
                # An uncertain response may still have delivered the contact.
                self._touch_active = True
            elif not self._touch_active:
                raise ValueError("QEMU touch MOVE requires DOWN")
            phase = "begin" if action == "DOWN" else "update"
            events = [self._multitouch_event(phase)]
            if action == "DOWN":
                events.append({"type": "btn", "data": {"button": "touch", "down": True}})
            events.extend(self._multitouch_event("data", axis, round(value * 32767))
                          for axis, value in (("x", u), ("y", v)))
            self._qmp_transaction("input-send-event", {"device": "phone-display", "events": events})

    def _multitouch_event(self, phase, axis="x", value=0):
        return {"type": "mtt", "data": {"type": phase, "slot": 0,
                "tracking-id": -1 if phase == "end" else self._touch_id, "axis": axis, "value": value}}

    def _release_touch(self, best_effort=False):
        with self._touch_lock:
            if not self._touch_active:
                return
            try:
                self._qmp_transaction("input-send-event", {"device": "phone-display", "events": [
                    self._multitouch_event("end"),
                    {"type": "btn", "data": {"button": "touch", "down": False}}]}, allow_stopped=True)
                self._touch_active = False
            except Exception:
                # Keep the contact pending so stop/disconnect can retry a failed release.
                if not best_effort:
                    raise

    def _qmp_transaction(self, command=None, arguments=None, allow_stopped=False):
        # A QEMU TCP monitor accepts one active client. Release it between
        # commands so the process manager can inspect identity and shut down.
        from .qmp import QmpClient
        with self._qmp_lock:
            if self.stop_event.is_set() and not allow_stopped:
                raise RuntimeError("QEMU backend is stopped")
            controller = QmpClient(self.qmp_port, timeout=2)
            try:
                controller.connect()
                if command is not None:
                    return controller.execute(command, arguments)
            finally:
                controller.close()

    def key(self, key):
        if key not in NAVIGATION_QCODES:
            raise ValueError(f"Unsupported QEMU navigation key: {key}")
        if self.qmp_port is None:
            raise RuntimeError("QEMU Android navigation requires a loopback QMP connection; configure --qmp-port")
        # QEMU releases send-key chords automatically after hold-time, even if
        # the monitor client disconnects before the response reaches us.
        self._qmp_transaction("send-key", {"keys": [{"type": "qcode", "data": code}
                                                for code in NAVIGATION_QCODES[key]], "hold-time": 80})

    def text(self, value):
        if len(value) > 256 or any(not 32 <= ord(character) < 127 for character in value):
            raise ValueError("QEMU text input accepts at most 256 printable ASCII characters")
        for character in value:
            self.rfb.key_chord((ord(character),))

    def stop(self):
        self.stop_event.set()
        self._release_touch(best_effort=True)
        self.rfb.close()  # Interrupts a pending incremental read immediately.
        self._close_converter()  # Also interrupts a blocked FFmpeg stdin write.
        with self._qmp_lock:
            pass  # In-flight input closes its short-lived connection before stop returns.
        if self.thread is not None and threading.current_thread() is not self.thread:
            self.thread.join(timeout=3)
