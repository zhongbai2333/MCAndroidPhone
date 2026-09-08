import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "bridge"))
from mcandroid_bridge._launch.qemu_runtime import (ANDROID_KERNEL_COMMAND_LINE, QMP_MAX_LINE, QmpClient, QmpError,
                          find_iso, find_qemu, probe_qemu_display, qemu_command, wait_qmp)


def send_message(sock, value):
    sock.sendall((json.dumps(value) + "\r\n").encode("utf-8"))


class FakeQmp:
    def __init__(self, handler, fragmented=False):
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(2)
        self.port = self.listener.getsockname()[1]
        self.errors = []
        self.requests = []
        self.connection = None

        def serve():
            try:
                sock, _ = self.listener.accept()
                self.connection = sock
                with sock, sock.makefile("rb") as stream:
                    sock.settimeout(2)
                    greeting = b'{"QMP":{"version":{},"capabilities":[]}}\r\n'
                    if fragmented:
                        for chunk in (greeting[:5], greeting[5:18], greeting[18:]):
                            sock.sendall(chunk)
                    else:
                        sock.sendall(greeting)
                    capabilities = json.loads(stream.readline())
                    self.requests.append(capabilities)
                    if capabilities["execute"] != "qmp_capabilities":
                        raise AssertionError("capabilities must be negotiated first")
                    send_message(sock, {"event": "RESUME", "data": {}})
                    send_message(sock, {"return": {}, "id": capabilities["id"]})
                    handler(sock, stream, self.requests)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as exc:
                self.errors.append(exc)

        self.thread = threading.Thread(target=serve, daemon=True)
        self.thread.start()

    def close(self):
        if self.connection is not None:
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.listener.close()
        self.thread.join(3)


class CommandTests(unittest.TestCase):
    def test_virgl_uses_gpu_display_and_guest_acceleration(self):
        command = qemu_command("qemu-system-x86_64.exe", None, 1234, ".", kernel="kernel",
                               display_mode="dbus", gpu="virgl", input_mode="touchscreen")
        self.assertIn("dbus,p2p=on,gl=on", command)
        self.assertTrue(any(value.startswith("virtio-vga-gl,") for value in command))
        self.assertNotIn("-vnc", command)
        append = command[command.index("-append")+1]
        self.assertIn("HWACCEL=1 GRALLOC=gbm HWC=drm",append)
        self.assertIn("1080x1920",append)
        with self.assertRaises(ValueError):
            qemu_command("qemu-system-x86_64.exe",5900,1234,".",display_mode="vnc",gpu="virgl")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.project = Path(self.temp.name).resolve() / "project space,comma"
        self.project.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def touch(self, relative):
        path = self.project / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"fixture")
        return path

    def test_display_probe_is_bounded_and_distinguishes_absent_from_unknown(self):
        executable = self.project / "QEMU install/qemu-system-x86_64.exe"
        with patch("mcandroid_bridge._launch.qemu_runtime.subprocess.run") as run:
            run.return_value = Mock(returncode=0, stdout="Available display backend types:\nnone\nsdl\ndbus\n")
            self.assertEqual({"dbus": True, "backends": ["none", "sdl", "dbus"]}, probe_qemu_display(executable))
            self.assertEqual([str(executable), "-display", "help"], run.call_args.args[0])
            self.assertEqual(5, run.call_args.kwargs["timeout"])
            run.return_value.stdout = "Available display backend types:\nnone\nsdl\n"
            self.assertFalse(probe_qemu_display(executable)["dbus"])
            run.return_value.returncode = 1
            self.assertIsNone(probe_qemu_display(executable)["dbus"])
            run.side_effect = subprocess.TimeoutExpired("qemu", 5)
            self.assertIsNone(probe_qemu_display(executable)["dbus"])
            run.reset_mock()
            self.assertIsNone(probe_qemu_display(None)["dbus"])
            run.assert_not_called()

    def test_command_preserves_paths_and_escapes_only_qemu_key_value_commas(self):
        executable = self.touch("QEMU install/qemu-system-x86_64.exe")
        iso = self.touch("images/Android 9,live.iso")
        disk = self.touch("images/Android data,disk.qcow2")
        kernel = self.touch("images/kernel")
        initrd = self.touch("images/initrd.img")
        command = qemu_command(executable, 5999, 4444, self.project / "runtime",
                               iso=iso, disk=disk, kernel=kernel, initrd=initrd)
        self.assertEqual(str(executable), command[0])
        self.assertEqual("pc,accel=whpx:tcg", command[command.index("-machine") + 1])
        self.assertEqual("Nehalem", command[command.index("-cpu") + 1])
        self.assertEqual("none", command[command.index("-vga") + 1])
        self.assertIn("VGA,xres=1080,yres=1920,vgamem_mb=64", command)
        self.assertEqual("127.0.0.1:99", command[command.index("-vnc") + 1])
        self.assertEqual("tcp:127.0.0.1:4444,server=on,wait=off", command[command.index("-qmp") + 1])
        self.assertIn("file=" + str(iso).replace(",", ",,") + ",media=cdrom,readonly=on,format=raw", command)
        self.assertIn("file=" + str(disk).replace(",", ",,") + ",if=ide,format=qcow2", command)
        self.assertIn("-snapshot", command)
        self.assertEqual("d", command[command.index("-boot") + 1])
        self.assertEqual(str(kernel), command[command.index("-kernel") + 1])
        self.assertEqual(str(initrd), command[command.index("-initrd") + 1])
        self.assertEqual(ANDROID_KERNEL_COMMAND_LINE, command[command.index("-append") + 1])
        boot_options = command[command.index("-append") + 1].split()
        self.assertIn("video=Virtual-1:1080x1920-32@60e", boot_options)
        self.assertIn("HWACCEL=0", boot_options)
        self.assertIn("DPI=480", boot_options)
        self.assertNotIn("nomodeset", boot_options)
        self.assertFalse(any(option.startswith("vga=") for option in boot_options))
        self.assertEqual("file:" + str(self.project / "runtime" / "qemu-serial.log"),
                         command[command.index("-serial") + 1])
        self.assertEqual(b"fixture", iso.read_bytes())
        self.assertEqual(b"fixture", disk.read_bytes())
        self.assertFalse((self.project / "runtime").exists())
        for forbidden in ("hostfwd", "0.0.0.0", "-virtfs", "-fsdev", "-incoming"):
            self.assertFalse(any(forbidden in argument for argument in command))

    def test_explicit_accelerators_and_safe_raw_disk(self):
        for accel in ("whpx", "tcg"):
            command = qemu_command(self.project / "qemu-system-x86_64.exe", 5900, 4444,
                                   self.project, disk=self.project / "data.raw", disk_format="raw", accel=accel)
            self.assertIn(f"pc,accel={accel}", command)
            self.assertIn("-snapshot", command)
            self.assertTrue(any(argument.endswith(",if=ide,format=raw") for argument in command))
            self.assertNotIn("-boot", command)

    def test_input_device_selection_uses_one_absolute_input_device(self):
        defaults = dict(executable=self.project / "qemu.exe", vnc_port=5900,
                        qmp_port=4444, runtime=self.project)
        for mode in (None, "mouse", "touchscreen"):
            with self.subTest(mode=mode):
                command = qemu_command(**(defaults | ({} if mode is None else {"input_mode": mode})))
                devices = [value for index, value in enumerate(command) if index and command[index - 1] == "-device"]
                self.assertEqual(mode != "touchscreen", "usb-tablet,bus=usb.0" in devices)
                self.assertEqual(mode == "touchscreen", "virtio-multitouch-pci,id=phone-touch,display=phone-display" in devices)
                display = next(device for device in devices if device.startswith("VGA,"))
                self.assertEqual(mode == "touchscreen", "id=phone-display" in display.split(","))

    def test_dbus_display_is_peer_to_peer_without_a_vnc_listener(self):
        command = qemu_command(self.project / "qemu.exe", None, 4444, self.project, display_mode="dbus")
        self.assertEqual("dbus,p2p=on,gl=off", command[command.index("-display") + 1])
        self.assertNotIn("-vnc", command)
        self.assertIn("tcp:127.0.0.1:4444,server=on,wait=off", command)
        default = qemu_command(self.project / "qemu.exe", 5900, 4444, self.project)
        self.assertEqual("none", default[default.index("-display") + 1])
        self.assertIn("127.0.0.1:0", default)

    def test_virtio_gpu_uses_shared_display_id_and_keeps_software_android(self):
        for input_mode in ("mouse", "touchscreen"):
            with self.subTest(input_mode=input_mode):
                command = qemu_command(self.project / "qemu.exe", None, 4444, self.project,
                                       kernel=self.project / "kernel", display_mode="dbus", gpu="virtio",
                                       width=720, height=1280, input_mode=input_mode)
                self.assertIn("virtio-vga,id=phone-display,xres=720,yres=1280,max_outputs=1,edid=on", command)
                self.assertFalse(any(argument.startswith("VGA,") for argument in command))
                self.assertEqual("none", command[command.index("-vga") + 1])
                self.assertIn("qemu-xhci,id=usb", command)
                boot = command[command.index("-append") + 1].split()
                self.assertIn("HWACCEL=0", boot)
                self.assertFalse(any(argument.startswith(("GRALLOC=", "HWC=")) for argument in boot))
                self.assertEqual(input_mode == "touchscreen", "virtio-multitouch-pci,id=phone-touch,display=phone-display" in command)

    def test_custom_portrait_size_and_density_reach_both_device_and_guest(self):
        command = qemu_command(self.project / "qemu-system-x86_64.exe", 5900, 4444,
                               self.project, kernel=self.project / "kernel",
                               width=720, height=1280, density=320)
        self.assertIn("VGA,xres=720,yres=1280,vgamem_mb=64", command)
        boot_options = command[command.index("-append") + 1].split()
        self.assertIn("video=Virtual-1:720x1280-32@60e", boot_options)
        self.assertIn("DPI=320", boot_options)
        self.assertNotIn("DPI=480", boot_options)
        self.assertFalse(any("1080x1920" in option for option in boot_options))
        self.assertNotIn("nomodeset", boot_options)

    def test_display_setting_boundaries_are_accepted(self):
        for width, height, density in ((320, 320, 120), (4096, 2048, 640), (2048, 4096, 480)):
            with self.subTest(width=width, height=height, density=density):
                command = qemu_command(self.project / "qemu.exe", 5900, 4444, self.project,
                                       kernel=self.project / "kernel",
                                       width=width, height=height, density=density)
                self.assertIn(f"VGA,xres={width},yres={height},vgamem_mb=64", command)
                self.assertIn(f"DPI={density}", command[command.index("-append") + 1].split())

    def test_invalid_options_fail_before_launch(self):
        defaults = dict(executable=self.project / "qemu-system-x86_64.exe", vnc_port=5900,
                        qmp_port=4444, runtime=self.project)
        for override in ({"vnc_port": 5899}, {"qmp_port": 0}, {"qmp_port": 5900},
                         {"width": 4096, "height": 4096},
                         {"cpus": 0}, {"cpus": True}, {"memory": 128}, {"memory": 3.5},
                         {"width": 318}, {"width": 4098}, {"width": 721},
                         {"height": 318}, {"height": 4098}, {"height": 1281},
                         {"width": True}, {"height": 1920.0}, {"width": "1080"},
                         {"density": 119}, {"density": 641}, {"density": True},
                         {"density": 480.0}, {"density": "480"},
                         {"input_mode": "auto"}, {"input_mode": "keyboard"}, {"input_mode": None},
                         {"display_mode": "auto"}, {"display_mode": "spice"}, {"display_mode": None},
                         {"gpu": "auto"}, {"gpu": "virgl"}, {"gpu": None},
                         {"disk_format": "qcow2,cache=unsafe"}, {"accel": "whpx,share=on"},
                         {"initrd": self.project / "initrd"}, {"iso": "bad\nname.iso"}):
            with self.subTest(override=override), self.assertRaises(ValueError):
                qemu_command(**(defaults | override))

    def test_find_stock_qemu_rejects_sdk_fork(self):
        stock = self.touch(".runtime/qemu/qemu-system-x86_64.exe")
        sdk = self.touch("android-sdk/emulator/qemu/windows-x86_64/qemu-system-x86_64.exe")
        with patch.dict(os.environ, {}, clear=True), patch("mcandroid_bridge._launch.qemu_runtime.shutil.which", return_value=str(sdk)):
            self.assertEqual(stock, find_qemu(self.project))
            self.assertIsNone(find_qemu(self.project, sdk))
            self.assertEqual(stock, find_qemu(self.project, stock))
            stock.unlink()
            self.assertIsNone(find_qemu(self.project))

    def test_find_iso_priorities_and_partial_download_exclusion(self):
        old = self.touch(".runtime/images/old.iso")
        newest = self.touch(".runtime/downloads/new.ISO")
        partial = self.touch(".runtime/downloads/incomplete.iso.part")
        override = self.touch("chosen/live.iso")
        os.utime(old, (1, 1))
        os.utime(newest, (2, 2))
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(newest, find_iso(self.project))
            self.assertIsNone(find_iso(self.project, partial))
            with patch.dict(os.environ, {"MCANDROIDPHONE_QEMU_ISO": str(override)}):
                self.assertEqual(override, find_iso(self.project))
                self.assertEqual(old, find_iso(self.project, old))


class QmpTests(unittest.TestCase):
    def setUp(self):
        self.servers = []

    def tearDown(self):
        for server in self.servers:
            server.close()
            self.assertFalse(server.thread.is_alive())
            self.assertEqual([], server.errors)

    def server(self, handler, fragmented=False):
        server = FakeQmp(handler, fragmented)
        self.servers.append(server)
        return server

    def test_fragmented_greeting_async_events_and_wrong_ids_are_skipped(self):
        def handler(sock, stream, requests):
            request = json.loads(stream.readline())
            requests.append(request)
            send_message(sock, {"event": "STOP", "data": {}})
            send_message(sock, {"error": {"desc": "unrelated"}, "id": request["id"] - 1})
            result = (json.dumps({"return": {"status": "running", "running": True}, "id": request["id"]}) + "\n").encode()
            sock.sendall(result[:9])
            sock.sendall(result[9:])

        server = self.server(handler, fragmented=True)
        with QmpClient(server.port) as client:
            self.assertEqual({"status": "running", "running": True}, client.status())
        self.assertEqual(["qmp_capabilities", "query-status"], [request["execute"] for request in server.requests])
        self.assertEqual(2, len({request["id"] for request in server.requests}))

    def test_matching_error_is_reported_without_closing_healthy_connection(self):
        def handler(sock, stream, requests):
            request = json.loads(stream.readline())
            requests.append(request)
            send_message(sock, {"error": {"class": "CommandNotFound", "desc": "missing"}, "id": request["id"]})
            request = json.loads(stream.readline())
            requests.append(request)
            send_message(sock, {"return": {"status": "paused"}, "id": request["id"]})

        server = self.server(handler)
        with QmpClient(server.port) as client:
            with self.assertRaisesRegex(QmpError, "CommandNotFound"):
                client.execute("not-a-command", {"value": 3})
            self.assertEqual({"status": "paused"}, client.status())
        self.assertEqual({"value": 3}, server.requests[1]["arguments"])

    def test_response_timeout_closes_connection(self):
        finish = threading.Event()

        def handler(sock, stream, requests):
            requests.append(json.loads(stream.readline()))
            finish.wait(1)

        server = self.server(handler)
        client = QmpClient(server.port, timeout=0.1)
        try:
            with self.assertRaises(TimeoutError):
                client.status()
            self.assertIsNone(client.socket)
        finally:
            finish.set()
            client.close()

    def test_oversized_response_is_rejected(self):
        def handler(sock, stream, requests):
            requests.append(json.loads(stream.readline()))
            sock.sendall(b"x" * (QMP_MAX_LINE + 1))

        server = self.server(handler)
        with QmpClient(server.port) as client:
            with self.assertRaisesRegex(QmpError, "1 MiB"):
                client.status()

    def test_wait_qmp_checks_process_and_runs_callback(self):
        def handler(sock, stream, requests):
            request = json.loads(stream.readline())
            send_message(sock, {"return": {"status": "running"}, "id": request["id"]})

        server = self.server(handler)
        check = Mock()
        self.assertEqual({"status": "running"}, wait_qmp(server.port, Mock(poll=Mock(return_value=None)), check=check))
        check.assert_called()
        with self.assertRaisesRegex(RuntimeError, "exit 9"):
            wait_qmp(server.port, Mock(poll=Mock(return_value=9)))

    def test_input_validation_does_not_connect(self):
        for port in (0, -1, 65536, True, "4444"):
            with self.assertRaises(ValueError):
                QmpClient(port)
        for timeout in (0, -1, float("inf"), float("nan")):
            with self.assertRaises(ValueError):
                QmpClient(4444, timeout)
        with patch("mcandroid_bridge.qmp.socket.create_connection") as create:
            client = QmpClient(4444)
            with self.assertRaises(ValueError):
                client.execute("bad\ncommand")
            with self.assertRaises(ValueError):
                client.execute("query-status", [])
            create.assert_not_called()


if __name__ == "__main__":
    unittest.main()
