"""Windows QEMU shared framebuffer, with persistent D-Bus touch input.

Only local, explicitly handed-off sockets are used. QEMU owns the original
surface; Win32.Map gives us a read-only mapping handle duplicated into this
process. Dirty notifications replace VNC polling. NV12 conversion still uses
FFmpeg and copies; this is not GPU texture interop or end-to-end zero-copy.
"""
from __future__ import annotations

import base64
import ctypes
import math
import os
import tempfile
import threading
import time

from .qemu import QemuBackend
from .qmp import QmpClient
from .rfb import validate_dimensions
from .dbus_transport import Peer, Variant, windows_socketpair

CONSOLE_PATH = "/org/qemu/Display1/Console_0"
LISTENER_PATH = "/org/qemu/Display1/Listener"
DISPLAY = "org.qemu.Display1."
MAP_INTERFACE = DISPLAY + "Listener.Win32.Map"
# QAPI InputButton.touch. QEMU's MultiTouch End retires the tracking ID but
# does not release BTN_TOUCH. Mouse.Release uses this same bound console.
TOUCH_BUTTON = 9


def pixel_format(pixman, color_order):
    # PIXMAN_TYPE_ARGB/ABGR, 32 bits with RGB888 and optional alpha.
    # Little-endian x8r8g8b8 is bytes B,G,R,X, not RGB24.
    formats = {0x20020888: "bgr0", 0x20028888: "bgra",
               0x20030888: "rgb0", 0x20038888: "rgba"}
    try:
        result = formats[pixman]
    except KeyError:
        raise ValueError(f"Unsupported QEMU shared pixman format 0x{pixman:08x}") from None
    if color_order == "bgr":
        result = {"bgr0": "rgb0", "rgb0": "bgr0", "bgra": "rgba", "rgba": "bgra"}[result]
    return result


def validate_layout(offset, width, height, stride, pixman, color_order):
    validate_dimensions(width, height)
    if not all(isinstance(v, int) and not isinstance(v, bool) for v in (offset, stride)):
        raise ValueError("QEMU mapping offset/stride must be integers")
    if offset < 0 or stride < width * 4 or stride > 4096 * 4 or stride % 4:
        raise ValueError("Invalid QEMU mapping offset/stride")
    size = offset + stride * height
    if size > 128 * 1024 * 1024:
        raise ValueError("QEMU shared mapping exceeds 128 MiB")
    return size, pixel_format(pixman, color_order)


class ReadOnlySurface:
    def __init__(self, handle, offset, width, height, stride, pixman, color_order):
        self.handle, self.address = handle, None
        self.width, self.height, self.stride, self.offset = width, height, stride, offset
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self._map = kernel.MapViewOfFile
        self._map.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_size_t]
        self._map.restype = ctypes.c_void_p
        self._unmap = kernel.UnmapViewOfFile
        self._unmap.argtypes = [ctypes.c_void_p]
        self._unmap.restype = ctypes.c_int
        self._close = kernel.CloseHandle
        self._close.argtypes = [ctypes.c_void_p]
        self._close.restype = ctypes.c_int
        try:
            size, self.format = validate_layout(offset, width, height, stride, pixman, color_order)
            if not isinstance(handle, int) or handle <= 0 or handle >= (1 << 64) - 16:
                raise ValueError("Invalid QEMU shared mapping handle")
            self.address = self._map(handle, 4, 0, 0, size)  # FILE_MAP_READ
            if not self.address:
                raise ctypes.WinError(ctypes.get_last_error())
        except BaseException:
            self.close()
            raise

    def snapshot(self):
        if self.address is None:
            raise RuntimeError("QEMU shared surface is closed")
        start = self.address + self.offset
        row_bytes = self.width * 4
        if self.stride == row_bytes:
            return ctypes.string_at(start, row_bytes * self.height)
        return b"".join(ctypes.string_at(start + row * self.stride, row_bytes) for row in range(self.height))

    def close(self):
        if self.address is not None:
            self._unmap(self.address)
            self.address = None
        if self.handle:
            self._close(self.handle)
            self.handle = None


class PixelSurface:
    """Direct D-Bus fallback for QEMU surfaces that have no shareable handle."""
    def __init__(self, width, height, stride, pixman, data, color_order):
        size, self.format = validate_layout(0, width, height, stride, pixman, color_order)
        if len(data) != size:
            raise ValueError("QEMU scanout pixels do not match the surface layout")
        self.width, self.height, self.stride, self.offset = width, height, width * 4, 0
        if stride == self.stride:
            # The D-Bus decoder already owns immutable bytes. Keep that storage
            # across page flips and conversion instead of copying each scanout
            # into rows and copying the whole surface again for the worker.
            self.pixels = bytes(data)
        else:
            self.pixels = bytearray(width * height * 4)
            self.update(0, 0, width, height, stride, pixman, data, color_order)

    def update(self, x, y, width, height, stride, pixman, data, color_order):
        if (min(x, y, width, height) < 0 or x + width > self.width or y + height > self.height or
                stride < width * 4 or stride > 16384 or len(data) != stride * height or
                pixel_format(pixman, color_order) != self.format):
            raise ValueError("QEMU pixel update is outside the surface or has a different layout")
        if isinstance(self.pixels, bytes):
            self.pixels = bytearray(self.pixels)
        for row in range(height):
            offset = (y + row) * self.stride + x * 4
            self.pixels[offset:offset + width * 4] = data[row * stride:row * stride + width * 4]

    def snapshot(self):
        return bytes(self.pixels)

    def close(self):
        self.pixels = None


# PC set-1 QEMU key numbers for printable US ASCII. D-Bus Keyboard takes qnum,
# not a Unicode keysym. Android text/IME beyond printable ASCII is separate.
_ROWS = (("1234567890-=", 2), ("qwertyuiop[]", 16), ("asdfghjkl;'`", 30), ("zxcvbnm,./", 44))
ASCII_KEYS = {char: code + index for row, code in _ROWS for index, char in enumerate(row)}
ASCII_KEYS.update({"`": 41, "\\": 43, " ": 57})
SHIFTED = dict(zip("!@#$%^&*()_+{}:\"~|<>?", "1234567890-=[];'`\\,./"))


class QemuDbusBackend(QemuBackend):
    def __init__(self, width, height, fps, qemu_pid, qmp_port, ffmpeg="ffmpeg", color_order="rgb", input_mode="touchscreen"):
        super().__init__(width, height, fps, ffmpeg=ffmpeg, qmp_port=qmp_port,
                         color_order=color_order, input_mode=input_mode)
        if os.name != "nt":
            raise ValueError("QEMU shared Win32 display currently requires Windows; use --qemu-display vnc")
        if not isinstance(qemu_pid, int) or isinstance(qemu_pid, bool) or not 0 < qemu_pid <= 0xffffffff:
            raise ValueError("D-Bus display requires the actual --qemu-pid")
        if qmp_port is None:
            raise ValueError("D-Bus display requires --qmp-port for socket handoff")
        self.qemu_pid = qemu_pid
        self.control = self.listener = None
        self._surface_lock = threading.Lock()
        self._surface = None
        self._generation = 0
        self._dirty = threading.Event()
        self._native_format = None
        self._position = (0.0, 0.0)
        self._input_lock = threading.RLock()
        self._pressed_keys = set()
        self.transport_mode = None
        self._announced = None

    def _converter_input_format(self):
        return self._native_format

    def _properties(self):
        return {"Interfaces": Variant("as", [MAP_INTERFACE])}

    def _announce_surface(self, surface):
        state = (self.transport_mode, surface.width, surface.height, surface.format)
        if state != self._announced:
            self._announced = state
            print(f"QEMU direct {self.transport_mode}: {surface.width}x{surface.height}, "
                  f"stride={surface.stride}, {surface.format}", flush=True)

    def _listener_message(self, message):
        try:
            interface, member, body = message.interface, message.member, message.body
            if message.path != LISTENER_PATH:
                raise ValueError("Unknown QEMU listener path")
            if interface == "org.freedesktop.DBus.Properties":
                if member == "GetAll" and len(body) == 1:
                    return "a{sv}", (self._properties() if body[0] == DISPLAY + "Listener" else {},)
                if member == "Get" and body == (DISPLAY + "Listener", "Interfaces"):
                    return "v", (self._properties()["Interfaces"],)
                raise ValueError("Unknown QEMU listener property")
            if interface == MAP_INTERFACE and member == "ScanoutMap":
                surface = ReadOnlySurface(*body, self.color_order)
                with self._surface_lock:
                    if self.stop_event.is_set():
                        surface.close()
                        return "", ()
                    old, self._surface = self._surface, surface
                    self.transport_mode = "shared-map"
                    self._generation += 1
                    if old is not None:
                        old.close()
                    self._dirty.set()
                self._announce_surface(surface)
            elif interface == DISPLAY + "Listener" and member == "Scanout":
                surface = PixelSurface(*body, self.color_order)
                with self._surface_lock:
                    if self.stop_event.is_set():
                        surface.close()
                        return "", ()
                    old, self._surface = self._surface, surface
                    self.transport_mode = "dbus-pixels"
                    self._generation += 1
                    if old is not None:
                        old.close()
                    self._dirty.set()
                self._announce_surface(surface)
            elif interface == DISPLAY + "Listener" and member == "Update":
                with self._surface_lock:
                    if not isinstance(self._surface, PixelSurface):
                        raise ValueError("QEMU pixel update has no matching scanout")
                    self._surface.update(*body, self.color_order)
                    self._dirty.set()
            elif interface == MAP_INTERFACE and member == "UpdateMap":
                x, y, width, height = body
                with self._surface_lock:
                    surface = self._surface
                    if surface is None or min(x, y, width, height) < 0 or x + width > surface.width or y + height > surface.height:
                        raise ValueError("QEMU dirty rectangle is outside the shared surface")
                    self._dirty.set()
            elif interface == DISPLAY + "Listener" and member == "Disable":
                with self._surface_lock:
                    if self._surface is not None:
                        self._surface.close()
                        self._surface = None
                    self._generation += 1
                    self._dirty.set()
            elif interface == DISPLAY + "Listener" and member in ("MouseSet", "CursorDefine"):
                pass  # Android direct-touch has no pointer sprite overlay.
            else:
                raise ValueError(f"Unsupported QEMU direct listener method {interface}.{member}")
            return "", ()
        except Exception as error:
            self.error = error
            self._dirty.set()
            raise

    def start(self, publish):
        if self._started or self.stop_event.is_set():
            raise RuntimeError("QEMU backend instances may only start once")
        self._started, self.publish = True, publish
        try:
            local, remote = windows_socketpair(tempfile.gettempdir())
            self.control = Peer(local, timeout=3)
            try:
                with QmpClient(self.qmp_port, timeout=3) as qmp:
                    qmp.execute("get-win32-socket", {"info": base64.b64encode(remote.share(self.qemu_pid)).decode("ascii"),
                                                      "fdname": "phone-dbus"})
                    qmp.execute("add_client", {"protocol": "@dbus-display", "fdname": "phone-dbus"})
            finally:
                remote.close()
            self.control.authenticate()
            local, remote = windows_socketpair(tempfile.gettempdir())
            self.listener = Peer(local, handler=self._listener_message, timeout=3)
            try:
                self.control.call(CONSOLE_PATH, DISPLAY + "Console", "RegisterListener", "ay", (remote.share(self.qemu_pid),))
            finally:
                remote.close()
            self.listener.authenticate()
        except BaseException:
            self.stop()
            raise

        def run():
            target, generation = 0.0, -1
            try:
                while not self.stop_event.is_set():
                    if not self._dirty.wait(.25):
                        self.check_health()
                        continue
                    if self.stop_event.wait(max(0, target - time.monotonic())):
                        break
                    self.check_health()
                    started = time.monotonic()
                    with self._surface_lock:
                        self._dirty.clear()
                        surface = self._surface
                        if surface is None:
                            continue
                        width, height, fmt = surface.width, surface.height, surface.format
                        current_generation = self._generation
                        pixels = surface.snapshot()
                    if current_generation != generation:
                        # VGA page flips can replace the surface every frame.
                        # Retire conversion/input only when the layout changes;
                        # restarting FFmpeg per Scanout prevents its first output.
                        if self.converter is None or (width, height, fmt) != (self.width, self.height, self._native_format):
                            self._native_format = fmt
                            self._resize(width, height)
                        generation = current_generation
                    with self._converter_lock:
                        converter = self.converter
                    if converter is not None and not self.stop_event.is_set():
                        converter.submit(pixels)
                        # Keep FFmpeg itself bounded as well as the surface
                        # queue. Its async pipeline can otherwise retain dozens
                        # of old frames while the listener continues refreshing.
                        converter.wait_for_output()
                    # At most one latest snapshot is in flight. Conversion
                    # time counts toward the period, instead of adding to it.
                    target = started + 1 / self.fps
            except Exception as error:
                if not self.stop_event.is_set():
                    self.error = error
            finally:
                self._release_touch(best_effort=True)
                self._close_converter()

        self.thread = threading.Thread(target=run, daemon=True, name="qemu-shared-frames")
        self.thread.start()

    def check_health(self):
        if self.error is not None:
            raise RuntimeError(f"QEMU shared display failed: {self.error}") from self.error
        for peer in (self.control, self.listener):
            if peer is not None and peer.error is not None:
                raise RuntimeError(f"QEMU D-Bus connection failed: {peer.error}") from peer.error
            if peer is not None and peer.closed.is_set() and not self.stop_event.is_set():
                raise RuntimeError("QEMU D-Bus connection is closed")
        with self._converter_lock:
            converter = self.converter
        if converter is not None:
            converter.check_health()

    def _input(self, interface, member, signature="", args=()):
        if self.control is None:
            raise RuntimeError("QEMU direct input is not connected")
        return self.control.call(CONSOLE_PATH, DISPLAY + interface, member, signature, args)

    def touch(self, action, u, v):
        if action not in ("DOWN", "MOVE", "UP") or not all(math.isfinite(c) and 0 <= c <= 1 for c in (u, v)):
            raise ValueError("QEMU touch requires DOWN/MOVE/UP and normalized finite coordinates")
        with self._touch_lock:
            if self.stop_event.is_set():
                raise RuntimeError("QEMU backend is stopped")
            if action == "UP":
                self._release_touch()
                return
            self.check_health()
            if action == "DOWN":
                if self._touch_active:
                    raise ValueError("QEMU touch is already down")
                self._touch_active = True  # Retain pending release on uncertain delivery.
            elif not self._touch_active:
                raise ValueError("QEMU touch MOVE requires DOWN")
            self._position = (u * (self.width - 1), v * (self.height - 1))
            if self.input_mode == "touchscreen":
                self._input("MultiTouch", "SendEvent", "utdd", (0 if action == "DOWN" else 1, 0, *self._position))
            else:
                self._input("Mouse", "SetAbsPosition", "uu", tuple(round(c) for c in self._position))
                if action == "DOWN":
                    self._input("Mouse", "Press", "u", (0,))

    def _release_touch(self, best_effort=False):
        with self._touch_lock:
            if not self._touch_active:
                return
            try:
                if self.input_mode == "touchscreen":
                    self._input("MultiTouch", "SendEvent", "utdd", (2, 0, *self._position))
                    self._input("Mouse", "Release", "u", (TOUCH_BUTTON,))
                else:
                    self._input("Mouse", "Release", "u", (0,))
                self._touch_active = False
            except Exception:
                if not best_effort:
                    raise

    def text(self, value):
        if len(value) > 256 or any(not 32 <= ord(c) < 127 for c in value):
            raise ValueError("QEMU text input accepts at most 256 printable ASCII characters")
        with self._input_lock:
            if self.stop_event.is_set():
                raise RuntimeError("QEMU backend is stopped")
            for char in value:
                shifted = char in SHIFTED or char.isupper()
                plain = SHIFTED.get(char, char.lower())
                codes = ([42] if shifted else []) + [ASCII_KEYS[plain]]
                try:
                    for code in codes:
                        self._pressed_keys.add(code)
                        self._input("Keyboard", "Press", "u", (code,))
                finally:
                    self._release_keys()

    def _release_keys(self, best_effort=False):
        with self._input_lock:
            error = None
            # A failed release must not prevent releasing the other keys.
            for code in tuple(self._pressed_keys):
                try:
                    self._input("Keyboard", "Release", "u", (code,))
                    self._pressed_keys.discard(code)
                except Exception as exc:
                    error = exc
            if error is not None and not best_effort:
                raise error

    def stop(self):
        self.stop_event.set()
        self._dirty.set()
        self._release_touch(best_effort=True)
        self._release_keys(best_effort=True)
        for peer in (self.listener, self.control):
            if peer is not None:
                peer.close()
        self._close_converter()
        if self.thread is not None and threading.current_thread() is not self.thread:
            self.thread.join(timeout=3)
        with self._surface_lock:
            if self._surface is not None:
                self._surface.close()
                self._surface = None
