"""Bounded QEMU Monitor Protocol client restricted to the local machine."""
from __future__ import annotations

import json
import math
import re
import socket
import threading
import time

QMP_MAX_LINE = 1024 * 1024


def _integer(value, name, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be an integer between {minimum} and {maximum}")
    return value


class QmpError(RuntimeError):
    pass


class QmpClient:
    def __init__(self, port, timeout=3):
        self.port = _integer(port, "QMP port", 1, 65535)
        if not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("QMP timeout must be positive and finite")
        self.timeout = timeout
        self.socket = None
        self.buffer = bytearray()
        self.next_id = 0
        self.lock = threading.RLock()

    def connect(self):
        with self.lock:
            if self.socket is not None:
                return self
            try:
                self.socket = socket.create_connection(("127.0.0.1", self.port), timeout=self.timeout)
                self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                greeting = self._read_message(time.monotonic() + self.timeout)
                if not isinstance(greeting.get("QMP"), dict):
                    raise QmpError("QMP greeting missing")
                self.execute("qmp_capabilities")
            except Exception:
                self.close()
                raise
            return self

    def _read_message(self, deadline):
        while True:
            if time.monotonic() >= deadline:
                raise TimeoutError("Timed out waiting for QMP response")
            end = self.buffer.find(b"\n")
            if end >= 0:
                if end > QMP_MAX_LINE:
                    raise QmpError("QMP line exceeds 1 MiB")
                raw = bytes(self.buffer[:end])
                del self.buffer[:end + 1]
                try:
                    result = json.loads(raw)
                except (ValueError, UnicodeError) as exc:
                    raise QmpError("Invalid QMP JSON") from exc
                if not isinstance(result, dict):
                    raise QmpError("QMP message must be an object")
                return result
            if len(self.buffer) > QMP_MAX_LINE:
                raise QmpError("QMP line exceeds 1 MiB")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Timed out waiting for QMP response")
            self.socket.settimeout(remaining)
            try:
                chunk = self.socket.recv(65536)
            except socket.timeout as exc:
                raise TimeoutError("Timed out waiting for QMP response") from exc
            if not chunk:
                raise ConnectionError("QMP disconnected")
            self.buffer.extend(chunk)

    def execute(self, name, args=None):
        if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9_-]*", name):
            raise ValueError("invalid QMP command name")
        if args is not None and not isinstance(args, dict):
            raise ValueError("QMP arguments must be an object")
        with self.lock:
            if self.socket is None:
                self.connect()
            self.next_id += 1
            request = {"execute": name, "id": self.next_id}
            if args is not None:
                request["arguments"] = args
            payload = (json.dumps(request, allow_nan=False) + "\n").encode("utf-8")
            if len(payload) > QMP_MAX_LINE:
                raise ValueError("QMP request exceeds 1 MiB")
            deadline = time.monotonic() + self.timeout
            try:
                self.socket.settimeout(self.timeout)
                self.socket.sendall(payload)
                while True:
                    response = self._read_message(deadline)
                    if "event" in response or response.get("id") != request["id"]:
                        continue
                    if "error" in response:
                        error = response["error"]
                        raise QmpError(f"QMP {name} failed: {error}")
                    if "return" not in response:
                        raise QmpError("QMP response has no result")
                    return response["return"]
            except OSError:
                self.close()
                raise

    def status(self):
        result = self.execute("query-status")
        if not isinstance(result, dict):
            raise QmpError("QMP status must be an object")
        return result

    def close(self):
        with self.lock:
            if self.socket is not None:
                try:
                    self.socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.socket.close()
                self.socket = None
            self.buffer.clear()

    def __enter__(self):
        return self.connect()

    def __exit__(self, *unused):
        self.close()
