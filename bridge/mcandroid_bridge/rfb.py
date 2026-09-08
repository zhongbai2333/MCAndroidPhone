"""Small loopback-only RFB client for QEMU's VNC display (RFC 6143).

Only None security and Raw, CopyRect, DesktopSize are negotiated. Pixels stay
in one BGRX framebuffer; an update is fully applied before a consumer sees it.
Protocol reference: https://www.rfc-editor.org/rfc/rfc6143.html
"""
from __future__ import annotations

import math
import re
import socket
import struct
import threading
import time


MAX_DIMENSION = 4096
MAX_PAYLOAD = MAX_DIMENSION * MAX_DIMENSION * 4
MAX_UPDATE_BYTES = MAX_PAYLOAD * 2
MAX_RECTANGLES = 8192
MAX_TEXT_BYTES = 1024 * 1024
PIXEL_FORMAT = struct.pack("!BBBBHHHBBB3x", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0)


class RfbProtocolError(RuntimeError):
    pass


def validate_dimensions(width, height):
    if not (isinstance(width, int) and isinstance(height, int)
            and 2 <= width <= MAX_DIMENSION and 2 <= height <= MAX_DIMENSION):
        raise RfbProtocolError(f"VNC display dimensions must be between 2 and {MAX_DIMENSION}: {width}x{height}")
    if width % 2 or height % 2:
        raise RfbProtocolError(f"VNC native display is {width}x{height}; NV12 requires even dimensions. "
                               "Change the guest display mode; no scaling or padding is applied.")


class RfbClient:
    def __init__(self, port=5900, timeout=10):
        if not isinstance(port, int) or not 1 <= port <= 65535:
            raise ValueError("VNC port must be between 1 and 65535")
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("VNC timeout must be positive and finite")
        self.port, self.timeout = port, timeout
        self.socket = None
        self.width = self.height = 0
        self.canvas = bytearray()
        self.name = ""
        self.version = None
        self.stopped = threading.Event()
        self._write_lock = threading.Lock()
        self._input_lock = threading.RLock()
        self._resizing = False
        self._mask = 0
        self._position = (0, 0)
        self._pressed_keys = set()

    def _receive(self, size, idle=False):
        if not 0 <= size <= MAX_PAYLOAD:
            raise RfbProtocolError(f"VNC payload exceeds limit: {size}")
        data = bytearray(size)
        view = memoryview(data)
        offset = 0
        deadline = time.monotonic() + self.timeout
        connection = self.socket
        while offset < size:
            if self.stopped.is_set() or connection is None:
                raise RfbProtocolError("VNC connection stopped")
            try:
                count = connection.recv_into(view[offset:], min(size - offset, 256 * 1024))
            except socket.timeout:
                # An unchanged desktop may legally leave an incremental request
                # unanswered forever. Once a message starts, its payload is timed.
                if idle and offset == 0:
                    continue
                if time.monotonic() < deadline:
                    continue
                raise RfbProtocolError("VNC timed out while receiving a message") from None
            except OSError as error:
                raise RfbProtocolError("VNC connection closed during receive") from error
            if not count:
                raise RfbProtocolError("VNC server disconnected during receive")
            offset += count
            if offset < size and time.monotonic() > deadline:
                raise RfbProtocolError("VNC timed out while receiving a message")
        return data

    def _send(self, data):
        with self._write_lock:
            connection = self.socket
            if connection is None:
                raise RfbProtocolError("VNC connection is not open")
            try:
                connection.sendall(data)
            except OSError as error:
                raise RfbProtocolError("VNC connection failed during send") from error

    def _reason(self):
        size, = struct.unpack("!I", self._receive(4))
        if size > 64 * 1024:
            raise RfbProtocolError("VNC failure reason exceeds 64 KiB")
        return self._receive(size).decode("utf-8", errors="replace")

    def connect(self, on_resize=None):
        if self.socket is not None or self.stopped.is_set():
            raise RuntimeError("RFB client instances may only connect once")
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            self.socket.settimeout(self.timeout)
            self.socket.connect(("127.0.0.1", self.port))
            self.socket.settimeout(min(.25, self.timeout))
            banner = bytes(self._receive(12))
            if not re.fullmatch(rb"RFB 003\.\d{3}\n", banner):
                raise RfbProtocolError(f"Unsupported VNC protocol banner: {banner!r}")
            minor = int(banner[8:11])
            # RFC 6143 Appendix A: unknown 3.x versions fall back to 3.3.
            self.version = minor if minor in (7, 8) else 3
            self._send(f"RFB 003.{self.version:03d}\n".encode("ascii"))
            if self.version == 3:
                security, = struct.unpack("!I", self._receive(4))
                if security == 0:
                    raise RfbProtocolError("VNC rejected connection: " + self._reason())
                if security != 1:
                    raise RfbProtocolError("Local QEMU VNC must offer None security; authentication is unsupported")
            else:
                count = self._receive(1)[0]
                if not count:
                    raise RfbProtocolError("VNC rejected connection: " + self._reason())
                if 1 not in self._receive(count):
                    raise RfbProtocolError("Local QEMU VNC must offer None security; authentication is unsupported")
                self._send(b"\x01")
                if self.version == 8:
                    result, = struct.unpack("!I", self._receive(4))
                    if result:
                        raise RfbProtocolError("VNC security failed: " + self._reason())
            # None security has no SecurityResult under RFB 3.3 or 3.7.
            self._send(b"\x01")  # Shared session; do not evict a local viewer.
            width, height, _format, name_size = struct.unpack("!HH16sI", self._receive(24))
            validate_dimensions(width, height)
            if name_size > 64 * 1024:
                raise RfbProtocolError("VNC desktop name exceeds 64 KiB")
            self.name = self._receive(name_size).decode("utf-8", errors="replace")
            self._resize(width, height, on_resize)
            self._send(b"\x00\x00\x00\x00" + PIXEL_FORMAT)
            self._send(struct.pack("!BBHiii", 2, 0, 3, 0, 1, -223))
        except BaseException:
            self.close()
            raise
        return self.width, self.height

    def _resize(self, width, height, on_resize):
        validate_dimensions(width, height)
        with self._input_lock:
            self._resizing = True
            self.release_pointer()
        try:
            # The bridge drains input from the old epoch here. Do not hold its
            # input lock while it synchronously calls touch('UP') back into us.
            if on_resize is not None:
                on_resize(width, height)
            with self._input_lock:
                if self.stopped.is_set():
                    raise RfbProtocolError("VNC connection stopped during display resize")
                canvas = bytearray(width * height * 4)
                retained_width, retained_height = min(width, self.width), min(height, self.height)
                for row in range(retained_height):
                    old = row * self.width * 4
                    new = row * width * 4
                    canvas[new:new + retained_width * 4] = self.canvas[old:old + retained_width * 4]
                self.width, self.height, self.canvas = width, height, canvas
                self._position = (min(self._position[0], width - 1), min(self._position[1], height - 1))
        finally:
            with self._input_lock:
                self._resizing = False

    def request_update(self, incremental=True):
        self._send(struct.pack("!BBHHHH", 3, bool(incremental), 0, 0, self.width, self.height))

    def _rectangle(self, x, y, width, height):
        if width <= 0 or height <= 0 or x + width > self.width or y + height > self.height:
            raise RfbProtocolError(f"VNC rectangle {x},{y} {width}x{height} is outside {self.width}x{self.height}")

    def read_update(self, on_resize=None, idle=True):
        """Apply a complete update. Return (pixels_changed, desktop_resized)."""
        for _ in range(256):
            message = self._receive(1, idle=idle)[0]
            if message == 0:
                break
            if message == 2:  # Bell has no payload.
                continue
            if message == 3:
                length, = struct.unpack("!3xI", self._receive(7))
                if length > MAX_TEXT_BYTES:
                    raise RfbProtocolError("VNC clipboard payload exceeds 1 MiB")
                self._receive(length)  # Clipboard synchronization is intentionally disabled.
                continue
            raise RfbProtocolError(f"Unsupported VNC server message: {message}")
        else:
            raise RfbProtocolError("Too many VNC ancillary messages without a framebuffer update")
        count, = struct.unpack("!xH", self._receive(3))
        if count > MAX_RECTANGLES:
            raise RfbProtocolError(f"VNC update has too many rectangles: {count}")
        changed, resized, payload = False, False, count * 12
        for index in range(count):
            x, y, width, height, encoding = struct.unpack("!HHHHi", self._receive(12))
            if encoding == -223:
                if index != count - 1:
                    raise RfbProtocolError("VNC DesktopSize must be the last rectangle in an update")
                self._resize(width, height, on_resize)
                # Await a new full update before publishing a resized canvas.
                changed, resized = False, True
                continue
            self._rectangle(x, y, width, height)
            if encoding == 0:
                size = width * height * 4
                payload += size
                if payload > MAX_UPDATE_BYTES:
                    raise RfbProtocolError("VNC framebuffer update exceeds payload limit")
                pixels = self._receive(size)
                stride = width * 4
                for row in range(height):
                    target = ((y + row) * self.width + x) * 4
                    self.canvas[target:target + stride] = pixels[row * stride:(row + 1) * stride]
            elif encoding == 1:
                payload += 4
                if payload > MAX_UPDATE_BYTES:
                    raise RfbProtocolError("VNC framebuffer update exceeds payload limit")
                source_x, source_y = struct.unpack("!HH", self._receive(4))
                self._rectangle(source_x, source_y, width, height)
                # Copy bottom-up when regions overlap vertically; a bytearray
                # slice snapshots each row for horizontal overlap as well.
                rows = range(height - 1, -1, -1) if y > source_y else range(height)
                for row in rows:
                    source = ((source_y + row) * self.width + source_x) * 4
                    target = ((y + row) * self.width + x) * 4
                    self.canvas[target:target + width * 4] = self.canvas[source:source + width * 4]
            else:
                raise RfbProtocolError(f"Unrequested VNC rectangle encoding: {encoding}")
            changed = True
        return changed, resized

    def rgb_frame(self):
        rgb = bytearray(self.width * self.height * 3)
        rgb[0::3] = self.canvas[2::4]
        rgb[1::3] = self.canvas[1::4]
        rgb[2::3] = self.canvas[0::4]
        return rgb

    def touch(self, action, u, v):
        if action not in ("DOWN", "MOVE", "UP") or not (math.isfinite(u) and math.isfinite(v)):
            raise ValueError("VNC touch requires DOWN/MOVE/UP and finite coordinates")
        with self._input_lock:
            if self.stopped.is_set() or not self.width:
                raise RuntimeError("VNC display is not connected")
            if self._resizing and action != "UP":
                raise RuntimeError("VNC display is resizing; retry input after reconnect")
            x = round(max(0, min(1, u)) * (self.width - 1))
            y = round(max(0, min(1, v)) * (self.height - 1))
            # A MOVE without a preceding DOWN is motion, never a new press.
            mask = 1 if action == "DOWN" else (0 if action == "UP" else self._mask)
            # A send timeout does not prove the guest missed a DOWN. Remember
            # it first so disconnect can still send a release after ambiguity.
            if action == "DOWN":
                self._mask = 1
            self._position = (x, y)
            self._send(struct.pack("!BBHH", 5, mask, x, y))
            self._mask = mask

    def release_pointer(self):
        with self._input_lock:
            if self._mask:
                self._send(struct.pack("!BBHH", 5, 0, *self._position))
                self._mask = 0

    def key_chord(self, keysyms):
        with self._input_lock:
            if self.stopped.is_set() or not self.width:
                raise RuntimeError("VNC display is not connected")
            pressed, failure = [], None
            try:
                for keysym in keysyms:
                    if not isinstance(keysym, int) or not 0 <= keysym <= 0xffffffff:
                        raise ValueError("Invalid VNC keysym")
                    pressed.append(keysym)
                    self._pressed_keys.add(keysym)
                    self._send(struct.pack("!BBHI", 4, 1, 0, keysym))
            except Exception as error:
                failure = error
            finally:
                for keysym in reversed(pressed):
                    try:
                        self._send(struct.pack("!BBHI", 4, 0, 0, keysym))
                        self._pressed_keys.discard(keysym)
                    except Exception as error:
                        failure = failure or error
            if failure:
                raise failure

    def close(self):
        self.stopped.set()
        with self._input_lock:
            try:
                self.release_pointer()
            except (OSError, RfbProtocolError):
                pass
            for keysym in list(self._pressed_keys):
                try:
                    self._send(struct.pack("!BBHI", 4, 0, 0, keysym))
                except (OSError, RfbProtocolError):
                    pass
            self._mask = 0
            self._pressed_keys.clear()
        connection, self.socket = self.socket, None
        if connection is not None:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
