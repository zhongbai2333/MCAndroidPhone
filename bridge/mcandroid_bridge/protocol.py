"""Bounded control protocol and immutable, explicitly acknowledged mmap slots."""
from __future__ import annotations

import base64
import binascii
import math
import mmap
import os
from pathlib import Path
import secrets
import socket
import threading
import time
import uuid

MAX_LINE = 8192
SLOTS = 3


class ProtocolError(ValueError):
    pass


def validate_dimensions(width: int, height: int) -> int:
    if not (2 <= width <= 4096 and 2 <= height <= 4096 and not width % 2 and not height % 2):
        raise ValueError("NV12 dimensions must be even and between 2 and 4096")
    return width * height * 3 // 2


def lines(sock, stop, handshake_deadline=None):
    pending = bytearray()
    while not stop.is_set():
        if handshake_deadline is not None and time.monotonic() > handshake_deadline:
            raise ProtocolError("handshake_timeout")
        try:
            chunk = sock.recv(4096)
        except socket.timeout:
            continue
        if not chunk:
            return
        pending.extend(chunk)
        while b"\n" in pending:
            end = pending.index(10)
            if end > MAX_LINE:
                raise ProtocolError("line_too_long")
            raw = bytes(pending[:end])
            del pending[:end + 1]
            try:
                line = raw.decode("ascii")
            except UnicodeDecodeError as exc:
                raise ProtocolError("non_ascii_command") from exc
            if "\r" in line or "\x00" in line:
                raise ProtocolError("invalid_character")
            yield line.split("\t")
            handshake_deadline = None
        if len(pending) > MAX_LINE:
            raise ProtocolError("line_too_long")


class Session:
    def __init__(self, sock, runtime, width, height, backend):
        self.sock, self.width, self.height, self.backend = sock, width, height, backend
        self.frame_bytes = validate_dimensions(width, height)
        self.path = Path(runtime).resolve() / f"frames-{uuid.uuid4().hex}.nv12"
        self.file = self.path.open("x+b")
        self.file.truncate(self.frame_bytes * SLOTS)
        self.mapping = mmap.mmap(self.file.fileno(), 0, access=mmap.ACCESS_WRITE)
        self.lock = threading.RLock()
        self.leases = [None] * SLOTS
        self.sequence = 0
        self.dropped = 0
        self.closed = False
        self.touching = False
        self.last_touch = (0.0, 0.0)

    def send(self, line):
        with self.lock:
            self.sock.sendall((line + "\n").encode("ascii"))

    def welcome(self):
        path = base64.b64encode(str(self.path).encode("utf-8")).decode("ascii")
        self.send(f"WELCOME\t1\t{self.width}\t{self.height}\t{self.frame_bytes}\t{SLOTS}\t{path}\tNV12")

    def publish(self, frame, timestamp_ns=None):
        if len(frame) != self.frame_bytes:
            raise ValueError("source_frame_size_mismatch")
        with self.lock:
            if self.closed:
                return False
            self.sequence += 1
            try:
                slot = self.leases.index(None)
            except ValueError:
                self.dropped += 1
                return False
            start = slot * self.frame_bytes
            # No slot may be written while its matching ACK is outstanding.
            # OS shared mapping visibility precedes the socket notification.
            self.mapping[start:start + self.frame_bytes] = frame
            self.leases[slot] = self.sequence
            self.send(f"FRAME\t{self.sequence}\t{slot}\t{timestamp_ns or time.monotonic_ns()}")
            return True

    def command(self, fields):
        # Retirement drains any in-flight input before a resized backend switches
        # coordinates. Buffered commands from the old socket never reach it.
        with self.lock:
            if self.closed:
                raise ProtocolError("session_retired")
            self._command(fields)

    def _command(self, fields):
        if len(fields) == 3 and fields[0] == "ACK":
            try:
                seq, slot = int(fields[1]), int(fields[2])
            except ValueError as exc:
                raise ProtocolError("invalid_ack") from exc
            with self.lock:
                if not 0 <= slot < SLOTS or self.leases[slot] != seq or seq <= 0:
                    raise ProtocolError("stale_or_invalid_ack")
                self.leases[slot] = None
            return
        if len(fields) == 4 and fields[0] == "TOUCH":
            action = fields[1]
            try:
                u, v = float(fields[2]), float(fields[3])
            except ValueError as exc:
                raise ProtocolError("invalid_coordinates") from exc
            if not all(math.isfinite(x) and 0 <= x <= 1 for x in (u, v)):
                raise ProtocolError("invalid_coordinates")
            if action not in ("DOWN", "MOVE", "UP"):
                raise ProtocolError("invalid_touch_action")
            if (action == "DOWN") == self.touching:
                raise ProtocolError("invalid_touch_sequence")
            # Remember DOWN before the RPC: if delivery succeeded but its response
            # failed, disconnect still attempts pressure=0 rather than sticking.
            self.last_touch = (u, v)
            if action == "DOWN":
                self.touching = True
            self.backend.touch(action, u, v)
            if action == "UP":
                self.touching = False
            return
        if len(fields) == 2 and fields[0] == "KEY" and fields[1] in ("BACK", "HOME", "APP_SWITCH"):
            self.backend.key(fields[1])
            return
        if len(fields) == 2 and fields[0] == "TEXT":
            try:
                raw = base64.b64decode(fields[1], validate=True)
                if len(raw) > 4096:
                    raise ProtocolError("text_too_long")
                value = raw.decode("utf-8")
            except (ValueError, binascii.Error, UnicodeError) as exc:
                raise ProtocolError("invalid_text") from exc
            self.backend.text(value)
            return
        raise ProtocolError("unknown_command")

    def close(self):
        with self.lock:
            if self.closed:
                return
            self.closed = True
            if self.touching:
                try:
                    self.backend.touch("UP", *self.last_touch)
                except Exception:
                    pass
                self.touching = False
            self.mapping.close()
            self.file.close()
            try:
                self.path.unlink()
            except (PermissionError, OSError):
                pass  # Windows readers may still hold this unique mapping.


class BridgeServer:
    def __init__(self, runtime, backend, width=1080, height=1920, port=18765):
        self.frame_bytes = validate_dimensions(width, height)
        self.runtime = Path(runtime).resolve()
        self.runtime.mkdir(parents=True, exist_ok=True)
        self.backend, self.width, self.height = backend, width, height
        self.token = secrets.token_urlsafe(32)
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.resize_lock = threading.Lock()
        self.epoch = 0
        self.reconfiguring = False
        self.cleanup_lock = threading.Lock()
        self.close_lock = threading.Lock()
        self.close_started = False
        self.active = None
        self.latest_frame = None
        self.connection = None
        self.worker = None
        self.workers = set()
        self.pending_cleanup = []
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        self.listener.bind(("127.0.0.1", port))
        self.listener.listen(2)
        self.listener.settimeout(0.3)
        self.port = self.listener.getsockname()[1]
        self.config = self.runtime / "bridge.properties"
        temporary = self.runtime / f"bridge-{uuid.uuid4().hex}.properties.tmp"
        temporary.write_text(f"host=127.0.0.1\nport={self.port}\ntoken={self.token}\n", encoding="ascii")
        try:
            temporary.chmod(0o600)
        except OSError:
            pass
        os.replace(temporary, self.config)

    def publish(self, frame, timestamp_ns=None):
        """Initial fixed-size publisher passed to existing static backends."""
        return self._publish_epoch(0, frame, timestamp_ns)

    def _publish_epoch(self, epoch, frame, timestamp_ns=None):
        with self.lock:
            if epoch != self.epoch or self.reconfiguring or self.stop_event.is_set():
                return False
            if len(frame) != self.frame_bytes:
                raise ValueError("source_frame_size_mismatch")
            # A single retained frame lets a new client see an idle Android
            # display even when streamScreenshot emits only on screen changes.
            self.latest_frame = (bytes(frame), timestamp_ns or time.monotonic_ns())
            session = self.active
            if session is not None:
                try:
                    return session.publish(*self.latest_frame)
                except OSError:
                    try:
                        session.sock.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
        return False

    def reconfigure(self, width, height):
        """Retire the old immutable mapping and return a publisher for this size.

        Call before changing backend input dimensions. Retirement synchronously
        releases old touch, so callers must not hold a backend input lock here.
        Each converter retains its returned callback; late old output is ignored.
        """
        frame_bytes = validate_dimensions(width, height)
        with self.resize_lock:
            with self.lock:
                if self.stop_event.is_set():
                    raise RuntimeError("bridge_closed")
                if (width, height) == (self.width, self.height):
                    epoch = self.epoch
                    return lambda frame, timestamp_ns=None: self._publish_epoch(epoch, frame, timestamp_ns)
                self.reconfiguring = True
                self.epoch += 1
                epoch = self.epoch
                self.latest_frame = None
                session, sock = self.active, self.connection
                self.active = None
            try:
                if sock:
                    try:
                        sock.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                if session:
                    session.close()
                    self._queue_cleanup(session.path)
                if sock:
                    sock.close()
            finally:
                with self.lock:
                    self.width, self.height, self.frame_bytes = width, height, frame_bytes
                    if self.connection is sock:
                        self.connection = None
                    self.reconfiguring = False
            return lambda frame, timestamp_ns=None: self._publish_epoch(epoch, frame, timestamp_ns)

    def _client(self, sock, epoch):
        session = None
        try:
            commands = lines(sock, self.stop_event, time.monotonic() + 5)
            hello = next(commands, None)
            if hello is None or len(hello) != 3 or hello[:2] != ["HELLO", "1"] or not secrets.compare_digest(hello[2], self.token):
                raise ProtocolError("authentication_failed")
            with self.lock:
                if (self.connection is not sock or epoch != self.epoch or self.reconfiguring
                        or self.stop_event.is_set()):
                    raise ProtocolError("session_retired")
                session = Session(sock, self.runtime, self.width, self.height, self.backend)
                session.welcome()
                self.active = session
                if self.latest_frame is not None:
                    session.publish(*self.latest_frame)
            for command in commands:
                session.command(command)
        except Exception as exc:
            # Never echo text/auth material or backend RPC metadata to the peer.
            try:
                line = "ERROR\t" + (str(exc) if isinstance(exc, ProtocolError) else "connection_or_backend_error")
                if session:
                    session.send(line)
                else:
                    sock.sendall((line + "\n").encode("ascii"))
            except OSError:
                pass
        finally:
            # Keep the old session discoverable until its input is drained. A
            # concurrent resize must wait for this close rather than allow a
            # fresh DOWN before the old session's deferred UP is delivered.
            if session:
                session.close()
                self._queue_cleanup(session.path)
            sock.close()
            with self.lock:
                if self.active is session:
                    self.active = None
                if self.connection is sock:
                    self.connection = None
                self.workers.discard(threading.current_thread())

    def serve_forever(self):
        try:
            self.backend.start(self.publish)
            while not self.stop_event.is_set():
                self.backend.check_health()
                try:
                    sock, _ = self.listener.accept()
                except socket.timeout:
                    self._cleanup()
                    continue
                except OSError:
                    if self.stop_event.is_set():
                        break
                    raise
                sock.settimeout(0.5)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                with self.lock:
                    if self.connection is not None or self.reconfiguring:
                        try:
                            sock.sendall(b"ERROR\tbusy\n")
                        except OSError:
                            pass
                        finally:
                            sock.close()
                        continue
                    self.connection = sock
                    self.worker = threading.Thread(target=self._client, args=(sock, self.epoch),
                                                   daemon=True, name="bridge-client")
                    self.workers.add(self.worker)
                    self.worker.start()
        finally:
            self.close()

    def _queue_cleanup(self, path):
        with self.cleanup_lock:
            if path not in self.pending_cleanup:
                self.pending_cleanup.append(path)

    def _cleanup(self):
        with self.cleanup_lock:
            for path in self.pending_cleanup[:]:
                try:
                    path.unlink(missing_ok=True)
                    self.pending_cleanup.remove(path)
                except OSError:
                    pass

    def close(self):
        with self.close_lock:
            if self.close_started:
                return
            self.close_started = True
        self.stop_event.set()
        self.listener.close()
        with self.lock:
            sock = self.connection
            workers = tuple(self.workers)
        if sock:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        for worker in workers:
            if threading.current_thread() is not worker:
                worker.join(timeout=3)
        self.backend.stop()
        self._cleanup()
        try:
            # Do not delete a newer bridge instance's discovery file.
            if f"token={self.token}\n" in self.config.read_text(encoding="ascii"):
                self.config.unlink()
        except OSError:
            pass
