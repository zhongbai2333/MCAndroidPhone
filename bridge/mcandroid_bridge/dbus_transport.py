"""Small bounded D-Bus peer transport and Windows AF_UNIX socket pairs.

Only the local peer protocol is implemented: no message bus, service discovery,
file-descriptor passing, or authentication over a public network.
"""
from __future__ import annotations

import ctypes
from dataclasses import dataclass
from functools import lru_cache
import os
from pathlib import Path
import socket
import struct
import threading
import time
import uuid


# Maximum supported 4096x4096 BGRA surface plus method argument metadata.
MAX_BODY = 64 * 1024 * 1024 + 4096
MAX_HEADER = 64 * 1024


class DbusError(RuntimeError):
    pass


@dataclass(frozen=True)
class Variant:
    signature: str
    value: object


@dataclass(frozen=True)
class Message:
    kind: int
    flags: int
    serial: int
    headers: dict
    signature: str
    body: tuple

    @property
    def path(self):
        return self.headers.get(1, "")

    @property
    def interface(self):
        return self.headers.get(2, "")

    @property
    def member(self):
        return self.headers.get(3, "")


@lru_cache(maxsize=256)
def signature_types(signature):
    if not isinstance(signature, str) or len(signature) > 255:
        raise DbusError("invalid D-Bus signature")
    position = 0

    def one(depth=0):
        nonlocal position
        if depth > 32 or position >= len(signature):
            raise DbusError("invalid or deeply nested D-Bus signature")
        code = signature[position]
        position += 1
        if code in "ybnqiuxtdhso gv".replace(" ", ""):
            return (code,)
        if code == "a":
            return (code, one(depth + 1))
        if code in "({":
            end = ")" if code == "(" else "}"
            children = []
            while position < len(signature) and signature[position] != end:
                children.append(one(depth + 1))
            if position >= len(signature) or not children or (code == "{" and len(children) != 2):
                raise DbusError("invalid D-Bus container signature")
            position += 1
            return (code, tuple(children))
        raise DbusError("unsupported D-Bus type " + code)

    result = []
    while position < len(signature):
        result.append(one())
    return tuple(result)


def alignment(node):
    code = node[0]
    if code in "ygv":
        return 1
    if code in "nq":
        return 2
    if code in "biuhsoa":
        return 4
    return 8


class Writer:
    def __init__(self, endian="<", base=0):
        self.endian, self.base = endian, base
        self.data = bytearray()

    def align(self, count):
        self.data.extend(b"\0" * (-(self.base + len(self.data)) % count))

    def value(self, node, value):
        code = node[0]
        self.align(alignment(node))
        formats = {"y": "B", "b": "I", "n": "h", "q": "H", "i": "i", "u": "I",
                   "x": "q", "t": "Q", "d": "d", "h": "I"}
        if code in formats:
            if code == "b":
                value = int(bool(value))
            self.data.extend(struct.pack(self.endian + formats[code], value))
        elif code in "sog":
            raw = value.encode("ascii" if code == "g" else "utf-8")
            if b"\0" in raw:
                raise DbusError("D-Bus strings may not contain NUL")
            self.data.extend(struct.pack(self.endian + ("B" if code == "g" else "I"), len(raw)))
            self.data.extend(raw + b"\0")
        elif code == "v":
            if not isinstance(value, Variant):
                raise DbusError("variant requires Variant(signature, value)")
            nodes = signature_types(value.signature)
            if len(nodes) != 1:
                raise DbusError("variant requires one complete type")
            self.value(("g",), value.signature)
            self.value(nodes[0], value.value)
        elif code == "a":
            length_position = len(self.data)
            self.data.extend(b"\0" * 4)
            self.align(alignment(node[1]))
            start = len(self.data)
            if node[1] == ("y",):
                self.data.extend(bytes(value))
            else:
                values = value.items() if isinstance(value, dict) else value
                for child in values:
                    self.value(node[1], child)
            struct.pack_into(self.endian + "I", self.data, length_position, len(self.data) - start)
        else:
            if len(value) != len(node[1]):
                raise DbusError("D-Bus struct arity mismatch")
            for child, item in zip(node[1], value):
                self.value(child, item)


class Reader:
    def __init__(self, data, endian="<", base=0):
        self.data = memoryview(data)
        self.endian, self.base, self.position = endian, base, 0

    def take(self, size):
        if size < 0 or self.position + size > len(self.data):
            raise DbusError("truncated D-Bus value")
        data = self.data[self.position:self.position + size]
        self.position += size
        return data

    def align(self, count):
        self.take(-(self.base + self.position) % count)

    def value(self, node):
        code = node[0]
        self.align(alignment(node))
        formats = {"y": "B", "b": "I", "n": "h", "q": "H", "i": "i", "u": "I",
                   "x": "q", "t": "Q", "d": "d", "h": "I"}
        if code in formats:
            fmt = self.endian + formats[code]
            value, = struct.unpack(fmt, self.take(struct.calcsize(fmt)))
            if code == "b":
                if value not in (0, 1):
                    raise DbusError("invalid D-Bus boolean")
                return bool(value)
            return value
        if code in "sog":
            size = self.value(("y" if code == "g" else "u",))
            data = bytes(self.take(size))
            if bytes(self.take(1)) != b"\0" or b"\0" in data:
                raise DbusError("invalid D-Bus string terminator")
            return data.decode("ascii" if code == "g" else "utf-8")
        if code == "v":
            signature = self.value(("g",))
            nodes = signature_types(signature)
            if len(nodes) != 1:
                raise DbusError("invalid D-Bus variant signature")
            return Variant(signature, self.value(nodes[0]))
        if code == "a":
            length = self.value(("u",))
            self.align(alignment(node[1]))
            end = self.position + length
            if end > len(self.data):
                raise DbusError("truncated D-Bus array")
            if node[1] == ("y",):
                return bytes(self.take(length))
            values = []
            while self.position < end:
                values.append(self.value(node[1]))
            if self.position != end:
                raise DbusError("D-Bus array length mismatch")
            return dict(values) if node[1][0] == "{" else values
        return tuple(self.value(child) for child in node[1])


def encode_values(signature, args, *, base=0):
    nodes = signature_types(signature)
    if len(nodes) != len(args):
        raise DbusError("D-Bus method argument count mismatch")
    writer = Writer(base=base)
    for node, value in zip(nodes, args):
        writer.value(node, value)
    return bytes(writer.data)


def encode_message(kind, serial, headers, signature="", args=(), flags=0):
    body = encode_values(signature, args)
    if len(body) > MAX_BODY:
        raise DbusError("D-Bus message body exceeds limit")
    fields = list(headers)
    if signature:
        fields.append((8, Variant("g", signature)))
    writer = Writer(base=16)
    for field in fields:
        writer.value(signature_types("(yv)")[0], field)
    header = bytes(writer.data)
    if len(header) > MAX_HEADER:
        raise DbusError("D-Bus header exceeds limit")
    return (struct.pack("<BBBBIII", ord("l"), kind, flags, 1, len(body), serial, len(header))
            + header + b"\0" * (-(16 + len(header)) % 8) + body)


def decode_message(fixed, rest):
    if len(fixed) != 16 or fixed[0] not in (ord("l"), ord("B")) or fixed[3] != 1:
        raise DbusError("invalid D-Bus message header")
    endian = "<" if fixed[0] == ord("l") else ">"
    _, kind, flags, _, body_length, serial, header_length = struct.unpack(endian + "BBBBIII", fixed)
    if kind not in (1, 2, 3, 4) or not serial or body_length > MAX_BODY or header_length > MAX_HEADER:
        raise DbusError("invalid or oversized D-Bus message")
    expected = header_length + (-(16 + header_length) % 8) + body_length
    if len(rest) != expected:
        raise DbusError("D-Bus message length mismatch")
    reader = Reader(rest[:header_length], endian, base=16)
    headers = {}
    while reader.position < header_length:
        key, value = reader.value(signature_types("(yv)")[0])
        if key in headers:
            raise DbusError("duplicate D-Bus header field")
        headers[key] = value.value
    body_offset = header_length + (-(16 + header_length) % 8)
    body = Reader(rest[body_offset:], endian)
    signature = headers.get(8, "")
    values = tuple(body.value(node) for node in signature_types(signature))
    if body.position != body_length:
        raise DbusError("D-Bus body length mismatch")
    return Message(kind, flags, serial, headers, signature, values)


class Peer:
    def __init__(self, sock, handler=None, timeout=5):
        self.socket, self.handler, self.timeout = sock, handler, timeout
        self.closed = threading.Event()
        self._write_lock = threading.Lock()
        self._lock = threading.Lock()
        self._pending = {}
        self._serial = 0
        self._buffer = bytearray()
        self._thread = None
        self.error = None

    def _read_auth_line(self):
        deadline = time.monotonic() + self.timeout
        while b"\r\n" not in self._buffer:
            if len(self._buffer) > 8192 or time.monotonic() > deadline:
                raise DbusError("D-Bus authentication response exceeded limit")
            data = self.socket.recv(4096)
            if not data:
                raise DbusError("D-Bus authentication connection closed")
            self._buffer.extend(data)
        line, _, rest = self._buffer.partition(b"\r\n")
        self._buffer = bytearray(rest)
        return bytes(line)

    def authenticate(self):
        if self._thread is not None:
            raise DbusError("D-Bus peer already authenticated")
        self.socket.settimeout(self.timeout)
        self.socket.sendall(b"\0AUTH ANONYMOUS 6d63616e64726f696470686f6e65\r\n")
        line = self._read_auth_line()
        if not line.startswith(b"OK "):
            raise DbusError("D-Bus anonymous authentication rejected")
        self.socket.sendall(b"BEGIN\r\n")
        self.socket.settimeout(.25)
        self._thread = threading.Thread(target=self._reader, name="dbus-peer", daemon=True)
        self._thread.start()
        return self

    authenticate_anonymous = authenticate

    def set_handler(self, handler):
        self.handler = handler

    def _next_serial(self):
        with self._lock:
            self._serial = self._serial % 0xFFFFFFFF + 1
            return self._serial

    def _send(self, message):
        with self._write_lock:
            if self.closed.is_set():
                raise DbusError("D-Bus peer is closed") from self.error
            self.socket.sendall(message)

    def call(self, path, interface, member, signature="", args=(), timeout=None):
        if threading.current_thread() is self._thread:
            raise DbusError("synchronous call from D-Bus handler would deadlock")
        serial = self._next_serial()
        waiter = [threading.Event(), None]
        with self._lock:
            self._pending[serial] = waiter
        try:
            self._send(encode_message(1, serial, [(1, Variant("o", path)), (2, Variant("s", interface)),
                                                (3, Variant("s", member))], signature, args))
            if not waiter[0].wait(self.timeout if timeout is None else timeout):
                self.close()
                raise TimeoutError("D-Bus call timed out: " + interface + "." + member)
            reply = waiter[1]
            if reply is None:
                raise DbusError("D-Bus connection failed") from self.error
            if reply.kind == 3:
                raise DbusError(str(reply.headers.get(4, "D-Bus error")) + ": " + str(reply.body))
            return reply.body
        finally:
            with self._lock:
                self._pending.pop(serial, None)

    def _receive(self, count, idle=False):
        deadline = time.monotonic() + self.timeout
        while len(self._buffer) < count:
            if self.closed.is_set():
                raise DbusError("D-Bus peer closed")
            try:
                data = self.socket.recv(min(count - len(self._buffer), 256 * 1024))
            except socket.timeout:
                if idle and not self._buffer:
                    deadline = time.monotonic() + self.timeout
                    continue
                if time.monotonic() < deadline:
                    continue
                raise DbusError("D-Bus message receive timed out") from None
            if not data:
                raise DbusError("D-Bus peer disconnected")
            self._buffer.extend(data)
            if len(self._buffer) < count and time.monotonic() > deadline:
                raise DbusError("D-Bus message receive timed out")
        data = bytes(self._buffer[:count])
        del self._buffer[:count]
        return data

    def _reader(self):
        try:
            while not self.closed.is_set():
                fixed = self._receive(16, idle=True)
                if fixed[0] not in (ord("l"), ord("B")):
                    raise DbusError("invalid D-Bus endianness")
                endian = "<" if fixed[0] == ord("l") else ">"
                body_length, _, header_length = struct.unpack(endian + "III", fixed[4:])
                if body_length > MAX_BODY or header_length > MAX_HEADER:
                    raise DbusError("D-Bus message exceeded limit")
                size = header_length + (-(16 + header_length) % 8) + body_length
                message = decode_message(fixed, self._receive(size))
                if message.kind in (2, 3):
                    with self._lock:
                        waiter = self._pending.get(message.headers.get(5))
                        if waiter is not None:
                            waiter[1] = message
                            waiter[0].set()
                elif message.kind == 1:
                    if self.closed.is_set():
                        break
                    try:
                        if message.interface == "org.freedesktop.DBus.Peer" and message.member == "Ping":
                            signature, args = "", ()
                        elif self.handler is not None:
                            signature, args = self.handler(message)
                        else:
                            raise DbusError("D-Bus method has no handler")
                        if not (message.flags & 1):
                            self._send(encode_message(2, self._next_serial(), [(5, Variant("u", message.serial))], signature, args))
                    except Exception as error:
                        if not (message.flags & 1):
                            self._send(encode_message(3, self._next_serial(), [(4, Variant("s", "org.qemu.Client.Error")),
                                                                            (5, Variant("u", message.serial))], "s", (str(error),)))
        except Exception as error:
            if not self.closed.is_set():
                self.error = error
        finally:
            self.close()

    def join(self, timeout=3):
        """Wait outside application locks; false means a handler is still active."""
        if self._thread is not None and threading.current_thread() is not self._thread:
            self._thread.join(timeout)
            return not self._thread.is_alive()
        return self._thread is None or not self._thread.is_alive()

    def close(self, timeout=3):
        self.closed.set()
        try:
            self.socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.socket.close()
        with self._lock:
            for waiter in self._pending.values():
                waiter[0].set()
        return self.join(timeout)


DbusConnection = Peer


def windows_socketpair(socket_dir):
    """Connected AF_UNIX sockets; unlike socket.socketpair on CPython Windows."""
    if os.name != "nt":
        return socket.socketpair(socket.AF_UNIX)
    directory = Path(socket_dir).resolve()
    if not directory.is_dir():
        raise ValueError("socket_dir must already exist")
    path = directory / ("db-" + uuid.uuid4().hex[:12])
    raw_path = os.fsencode(path)
    if len(raw_path) >= 108:
        raise ValueError("Windows AF_UNIX path exceeds 107 bytes; choose a shorter runtime directory")
    address = ctypes.create_string_buffer(struct.pack("<H", 1) + raw_path + b"\0" * (108 - len(raw_path)))
    ws = ctypes.WinDLL("ws2_32", use_last_error=True)
    for name in ("bind", "connect"):
        method = getattr(ws, name)
        method.argtypes = [ctypes.c_size_t, ctypes.c_void_p, ctypes.c_int]
        method.restype = ctypes.c_int
    ws.accept.argtypes = [ctypes.c_size_t, ctypes.c_void_p, ctypes.c_void_p]
    ws.accept.restype = ctypes.c_size_t
    ws.WSAGetLastError.restype = ctypes.c_int
    listener = socket.socket(family=1, type=socket.SOCK_STREAM)
    client = None
    bound = False
    try:
        if ws.bind(listener.fileno(), address, 110) != 0:
            raise ctypes.WinError(ws.WSAGetLastError())
        bound = True
        listener.listen(1)
        client = socket.socket(family=1, type=socket.SOCK_STREAM)
        if ws.connect(client.fileno(), address, 110) != 0:
            raise ctypes.WinError(ws.WSAGetLastError())
        accepted = ws.accept(listener.fileno(), None, None)
        if accepted == ctypes.c_size_t(-1).value:
            raise ctypes.WinError(ws.WSAGetLastError())
        server = socket.socket(family=1, type=socket.SOCK_STREAM, fileno=accepted)
        return client, server
    except Exception:
        if client is not None:
            client.close()
        raise
    finally:
        listener.close()
        if bound:
            path.unlink(missing_ok=True)
