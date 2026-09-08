"""Real QEMU/VNC/FFmpeg/input/resize smoke, using a disposable 512-byte BIOS guest.

Run with the project's Python. No Minecraft, Android image, SDK, or NASM needed.
All QEMU/bridge/FFmpeg descendants belong to the launcher's Windows Job Object.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import importlib.util
import json
import mmap
import os
from pathlib import Path
import socket
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "bridge"))
sys.path.insert(0, str(ROOT / "scripts"))

FIXTURE_SOURCE_SHA256 = "4d9021e1ef1ee2e69b1bb92c1bb6f76908b936844109a41e2f09030b2af65fa5"
FIXTURE_BINARY_SHA256 = "99e3b9be9b7b2315e23351fec37e2e7f0e1298e5f355017dd2ac044094818ee0"
FIXTURE_BASE64 = '+jHAjtiO0LwAfPv86CAAMcDNFjxydA48Y3X0gDZjfAboDADr6oA2YnwB6AIA6+CAPmJ8AXUXuBMAzRC4AKCOwDH/uQB9oGN8iMTzq8O4AwDNELgAuI7AMf+50Ae4WB/zq8MBBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAVao='


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def fixture_bytes():
    source = ROOT / "scripts/tests/fixtures/qemu_resize.asm"
    if source.is_file():
        digest = hashlib.sha256(source.read_text(encoding="utf-8").encode("utf-8")).hexdigest()
        require(digest == FIXTURE_SOURCE_SHA256, "BIOS fixture source changed; regenerate and verify embedded binary")
    binary = base64.b64decode(FIXTURE_BASE64, validate=True)
    require(len(binary) == 512 and binary[-2:] == b"\x55\xaa", "Invalid embedded BIOS boot sector")
    require(hashlib.sha256(binary).hexdigest() == FIXTURE_BINARY_SHA256, "Embedded BIOS fixture checksum mismatch")
    return binary


class FrameClient:
    def __init__(self, server, check, timeout=5):
        self.check, self.buffer = check, bytearray()
        self.mapping = self.file = None
        self.socket = socket.create_connection(("127.0.0.1", server.port), timeout=timeout)
        try:
            self.socket.sendall(f"HELLO\t1\t{server.token}\n".encode("ascii"))
            welcome = self.line(time.monotonic() + timeout).split("\t")
            require(len(welcome) == 8 and welcome[:2] == ["WELCOME", "1"] and welcome[7] == "NV12",
                    "Bridge did not return an NV12 WELCOME")
            self.width, self.height, self.size, self.slots = map(int, welcome[2:6])
            require(self.size == self.width * self.height * 3 // 2 and self.slots == 3, "Invalid bridge frame layout")
            self.path = Path(base64.b64decode(welcome[6], validate=True).decode("utf-8")).resolve()
            require(self.path.parent == server.runtime, "Bridge mapping escaped this test's runtime")
            self.file = self.path.open("rb")
            self.mapping = mmap.mmap(self.file.fileno(), 0, access=mmap.ACCESS_READ)
            require(len(self.mapping) == self.size * self.slots, "Unexpected mapping size")
        except BaseException:
            self.close()
            raise

    def line(self, deadline):
        while True:
            self.check()
            end = self.buffer.find(b"\n")
            if end >= 0:
                value = bytes(self.buffer[:end])
                del self.buffer[:end + 1]
                return value.decode("ascii")
            require(len(self.buffer) <= 8192, "Bridge protocol line is too long")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Timed out waiting for bridge frame or resize disconnect")
            self.socket.settimeout(min(.25, remaining))
            try:
                data = self.socket.recv(4096)
            except socket.timeout:
                continue
            if not data:
                raise ConnectionError("Bridge retired the framebuffer connection")
            self.buffer.extend(data)

    def frame(self, deadline):
        fields = self.line(deadline).split("\t")
        require(len(fields) == 4 and fields[0] == "FRAME", "Unexpected bridge frame response")
        sequence, slot, timestamp = map(int, fields[1:])
        require(sequence > 0 and 0 <= slot < self.slots, "Invalid frame lease")
        frame = bytes(self.mapping[slot * self.size:(slot + 1) * self.size])
        self.socket.sendall(f"ACK\t{sequence}\t{slot}\n".encode("ascii"))
        return frame

    def text(self, text):
        self.socket.sendall(b"TEXT\t" + base64.b64encode(text.encode("ascii")) + b"\n")

    def close(self):
        if self.mapping is not None:
            self.mapping.close()
            self.mapping = None
        if self.file is not None:
            self.file.close()
            self.file = None
        with contextlib.suppress(OSError):
            self.socket.shutdown(socket.SHUT_RDWR)
        self.socket.close()


def worker(args):
    from mcandroid_bridge.protocol import BridgeServer
    from mcandroid_bridge.qemu import QemuBackend

    runtime = args.runtime.resolve()
    if args.display == "dbus":
        from mcandroid_bridge.qemu_dbus import QemuDbusBackend
        backend = QemuDbusBackend(640, 480, 30, args.qemu_pid, args.qmp_port, args.ffmpeg, input_mode="mouse")
    else:
        backend = QemuBackend(640, 480, 30, args.vnc_port, args.ffmpeg, args.qmp_port)
    server = BridgeServer(runtime / "bridge", backend, 640, 480, port=0)
    backend.set_resize_handler(server.reconfigure)
    errors, clients = [], []
    started, deadline = time.monotonic(), time.monotonic() + 60

    def check():
        if errors:
            raise RuntimeError(f"Bridge worker failed: {errors[0]}") from errors[0]
        backend.check_health()
        if time.monotonic() > deadline:
            raise TimeoutError("QEMU transport smoke exceeded 60 seconds")

    def serve():
        try:
            server.serve_forever()
        except Exception as error:
            errors.append(error)

    thread = threading.Thread(target=serve, daemon=True, name="qemu-smoke-bridge")
    thread.start()

    def connect_size(width, height):
        target = time.monotonic() + 20
        seen, last_error = set(), None
        while time.monotonic() < target:
            check()
            client = None
            try:
                client = FrameClient(server, check)
                native = (client.width, client.height)
                if native not in seen:
                    seen.add(native)
                    print(f"[display] Native {args.display} framebuffer: {client.width}x{client.height}", flush=True)
                if (client.width, client.height) == (width, height):
                    clients.append(client)
                    return client
            except (OSError, RuntimeError) as error:
                # A boot-time resize may retire the immutable mapping between
                # WELCOME and open(). Retry the new session, never the old path.
                last_error = str(error)
                check()
            finally:
                if client is not None and client not in clients:
                    client.close()
            threading.Event().wait(.05)
        raise TimeoutError(f"Bridge did not expose native {width}x{height}; observed={sorted(seen)}; "
                           f"backend={backend.width}x{backend.height}; last_error={last_error}")

    def wait_frame(client, predicate):
        target = time.monotonic() + 15
        while time.monotonic() < target:
            frame = client.frame(target)
            if predicate(frame):
                return frame
        raise TimeoutError("Expected guest framebuffer pixels did not arrive")

    def is_canvas(client, frame):
        luma = frame[:client.width * client.height]
        return len(set(luma)) == 1 and luma[0] > 32

    def description(client, frame):
        return {"width": client.width, "height": client.height, "bytes": len(frame),
                "sha256": hashlib.sha256(frame).hexdigest(), "first_y": frame[0],
                "mapping": client.path.name}

    def resize(client):
        client.text("r")
        target = time.monotonic() + 10
        try:
            while time.monotonic() < target:
                client.frame(target)
        except ConnectionError:
            return
        finally:
            client.close()
        raise RuntimeError("Native resize did not retire the old client connection")

    try:
        # VGA13h is logically 320x200; QEMU exports its doubled scanout as the
        # native 640x400 framebuffer. Preserve that exact transport geometry.
        first = connect_size(640, 400)
        original = wait_frame(first, lambda frame: is_canvas(first, frame))
        first.text("c")
        changed = wait_frame(first, lambda frame: is_canvas(first, frame) and frame != original)
        require(changed[0] != original[0], "BIOS color key did not change native luma")
        result = {"backend": "stock-qemu-" + args.display, "pixel_format": "NV12 BT.709 limited",
                  "fixture_sha256": FIXTURE_BINARY_SHA256,
                  "initial": description(first, original), "after_text_c": description(first, changed)}
        resize(first)
        text_mode = connect_size(720, 400)
        text_frame = text_mode.frame(time.monotonic() + 10)
        result["text_mode"] = description(text_mode, text_frame)
        resize(text_mode)
        returned = connect_size(640, 400)
        returned_frame = wait_frame(returned, lambda frame: frame == changed)
        result["graphics_returned"] = description(returned, returned_frame)
        require(len({first.path, text_mode.path, returned.path}) == 3,
                "Resize/reconnect reused an old framebuffer mapping")
        result["text_input_changed_pixels"] = True
        result["native_resize_sequence"] = [[640, 400], [720, 400], [640, 400]]
        result["both_old_connections_retired"] = True
        result["same_geometry_new_mapping"] = True
        if args.screenshot:
            screenshot = runtime / "qemu-transport-final.png"
            from mcandroid_bridge.qmp import QmpClient
            with QmpClient(args.qmp_port) as control:
                control.execute("screendump", {"filename": str(screenshot), "format": "png"})
            require(screenshot.is_file() and screenshot.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n",
                    "QMP did not save its requested final PNG")
            result["screenshot"] = str(screenshot)
        result["elapsed_seconds"] = round(time.monotonic() - started, 3)
        result["status"] = "passed"
        (runtime / "transport.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, indent=2), flush=True)
    finally:
        for client in clients:
            client.close()
        server.close()
        thread.join(5)
        require(not thread.is_alive(), "Bridge worker did not stop after smoke")


def launch(args):
    specification = importlib.util.spec_from_file_location("phone_quick_test", ROOT / "scripts/quick-test.py")
    launcher = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(launcher)
    executable = launcher.find_qemu(ROOT, args.qemu)
    ffmpeg = launcher.find_ffmpeg(args.ffmpeg)
    require(executable is not None and ffmpeg is not None, "QEMU and FFmpeg must be installed or supplied explicitly")
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(ROOT / "bridge")
    environment["PYTHONUTF8"] = "1"
    environment["PYTHONUNBUFFERED"] = "1"
    with launcher.project_lock(ROOT):
        session = launcher.Session(ROOT, environment, "qemu-transport-smoke")
        state = "failed"
        print(f"[logs] {session.runtime}", flush=True)
        try:
            disk = session.runtime / "bios-resize.raw"
            with disk.open("xb") as output:
                output.write(fixture_bytes())
            vnc_port, qmp_port = (launcher.free_vnc_port() if args.display == "vnc" else None), launcher.free_port()
            identity = str(uuid.uuid4())
            command = launcher.qemu_command(executable, vnc_port, qmp_port, session.runtime,
                                             disk=disk, disk_format="raw", accel=args.accel, memory=256, cpus=1,
                                             display_mode=args.display)
            command[command.index("-name") + 1] = "MCAndroidPhone-transport-" + identity
            command.extend(("-uuid", identity))
            if args.cpu:
                command[command.index("-cpu") + 1] = args.cpu
            process, _log = session.start("qemu", command)
            launcher.wait_qmp(qmp_port, process, timeout=20, check=session.check_logs)
            with launcher.QmpClient(qmp_port) as control:
                require(control.execute("query-uuid").get("UUID") == identity, "QMP identity does not match this test's QEMU")
            session.qemu_port, session.qemu_process, session.qemu_uuid = qmp_port, process, identity
            worker_command = [sys.executable, Path(__file__).resolve(), "--worker", "--runtime", session.runtime,
                              "--qmp-port", qmp_port, "--ffmpeg", ffmpeg, "--display", args.display]
            if args.display == "dbus":
                worker_command.extend(("--qemu-pid", session.child_pid(process)))
            else:
                worker_command.extend(("--vnc-port", vnc_port))
            if args.screenshot:
                worker_command.append("--screenshot")
            log = session.run("transport", worker_command, timeout=75, watch=[("qemu", process)])
            require(hashlib.sha256(disk.read_bytes()).hexdigest() == FIXTURE_BINARY_SHA256,
                    "Snapshot test changed its base BIOS disk")
            result = session.runtime / "transport.json"
            require(result.is_file() and json.loads(result.read_text(encoding="utf-8")).get("status") == "passed",
                    "Transport worker exited without a successful result")
            report = json.loads(result.read_text(encoding="utf-8"))
            report.update(qemu_uuid=identity, qemu_accel=args.accel, qemu_cpu=command[command.index("-cpu") + 1],
                          base_disk_unchanged=True)
            result.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print(log.read_text(encoding="utf-8"), flush=True)
            print(f"[PASS] Real QEMU native resize/text smoke: {result}", flush=True)
            state = "passed"
        finally:
            try:
                session.stop()
            finally:
                session.save(state)
                print(f"[logs] {session.runtime}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qemu", type=Path)
    parser.add_argument("--ffmpeg", type=Path)
    parser.add_argument("--display", choices=("vnc", "dbus"), default="vnc")
    parser.add_argument("--qemu-pid", type=int, help=argparse.SUPPRESS)
    parser.add_argument("--screenshot", action="store_true")
    parser.add_argument("--accel", choices=("tcg", "whpx", "auto"), default="tcg")
    parser.add_argument("--cpu", help="Optional QEMU CPU model for acceleration compatibility checks")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--runtime", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--vnc-port", type=int, help=argparse.SUPPRESS)
    parser.add_argument("--qmp-port", type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        if args.worker:
            require(args.runtime is not None and args.qmp_port and args.ffmpeg and
                    (args.qemu_pid if args.display == "dbus" else args.vnc_port),
                    "Internal worker arguments are incomplete")
            worker(args)
        else:
            launch(args)
        return 0
    except Exception as error:
        print(f"[ERROR] {error}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
