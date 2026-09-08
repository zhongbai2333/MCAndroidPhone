"""Stock QEMU discovery, safe argument construction, and local QMP control."""
from __future__ import annotations

import math
import os
from pathlib import Path
import shutil
import subprocess
import time

from .platforms import Host, architecture
from mcandroid_bridge.qmp import QMP_MAX_LINE, QmpClient, QmpError
ANDROID_KERNEL_TEMPLATE = (
    "root=/dev/ram0 quiet SETUPWIZARD=0 SRC= DATA= "
    "video=Virtual-1:{width}x{height}-32@60e HWACCEL=0 DPI={density} "
    "console=tty0 console=ttyS0,115200"
)
ANDROID_KERNEL_COMMAND_LINE = ANDROID_KERNEL_TEMPLATE.format(width=1080, height=1920, density=480)


def _path(value):
    text = os.fspath(value)
    if not text or any(character in text for character in "\x00\r\n"):
        raise ValueError("invalid QEMU path")
    return Path(text).expanduser().resolve()


def _stock_qemu(path):
    path = _path(path)
    # Android Emulator ships a fork with the same executable name. Its gRPC
    # frontend is a different backend and cannot supply this launch contract.
    if any(part.lower() == "emulator" for part in path.parts):
        return None
    return path if path.is_file() and path.name.lower() in (
        "qemu-system-x86_64", "qemu-system-x86_64.exe") else None


def find_qemu(project, explicit=None):
    project = _path(project)
    if explicit is not None:
        return _stock_qemu(explicit)
    candidates = []
    for name in ("MCANDROIDPHONE_QEMU_EXE", "MCANDROIDPHONE_QEMU"):
        if os.environ.get(name):
            candidates.append(Path(os.environ[name]))
    for root in (project / ".runtime" / "qemu", project / "runtime" / "qemu"):
        candidates.extend((root / "qemu-system-x86_64.exe", root / "bin" / "qemu-system-x86_64.exe",
                           root / "qemu-system-x86_64", root / "bin" / "qemu-system-x86_64"))
        if root.is_dir():
            candidates.extend(sorted(root.glob("*/qemu-system-x86_64.exe")))
    installed = shutil.which("qemu-system-x86_64") or shutil.which("qemu-system-x86_64.exe")
    if installed:
        candidates.append(Path(installed))
    for name in ("ProgramFiles", "ProgramFiles(x86)"):
        if os.environ.get(name):
            candidates.append(Path(os.environ[name]) / "qemu" / "qemu-system-x86_64.exe")
    for candidate in candidates:
        found = _stock_qemu(candidate)
        if found is not None:
            return found
    return None


def find_iso(project, explicit=None):
    def valid(value):
        path = _path(value)
        return path if path.is_file() and path.suffix.lower() == ".iso" else None

    if explicit is not None:
        return valid(explicit)
    if os.environ.get("MCANDROIDPHONE_QEMU_ISO"):
        found = valid(os.environ["MCANDROIDPHONE_QEMU_ISO"])
        if found is not None:
            return found
    project = _path(project)
    candidates = []
    for root in (project / ".runtime" / "images", project / ".runtime" / "downloads",
                 project / "runtime" / "images", project / "runtime" / "downloads"):
        if root.is_dir():
            candidates.extend(path for path in root.iterdir()
                              if path.is_file() and path.suffix.lower() == ".iso")
    return max(candidates, key=lambda path: path.stat().st_mtime).resolve() if candidates else None


def probe_qemu_display(executable):
    """Read compiled display backends without starting a VM; unknown is not false."""
    if executable is None:
        return {"dbus": None, "error": "Stock QEMU not found"}
    try:
        result = subprocess.run([str(_path(executable)), "-display", "help"],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                encoding="utf-8", errors="replace", timeout=5,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        if result.returncode:
            return {"dbus": None, "error": f"QEMU display probe exited {result.returncode}"}
        backends = [line.strip() for line in result.stdout.splitlines()
                    if line.strip() and all(c.isalnum() or c in "-_" for c in line.strip())]
        if not backends:
            return {"dbus": None, "error": "QEMU returned no display backends"}
        return {"dbus": "dbus" in backends, "backends": backends}
    except (OSError, subprocess.TimeoutExpired, ValueError) as error:
        return {"dbus": None, "error": f"QEMU display probe failed: {error}"}


def _integer(value, name, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be an integer between {minimum} and {maximum}")
    return value


def _option_path(value):
    # QEMU key/value options escape literal commas by doubling them. Spaces
    # remain literal because callers pass this list directly to Popen.
    return str(_path(value)).replace(",", ",,")


def qemu_command(executable, vnc_port, qmp_port, runtime, iso=None, disk=None,
                 disk_format="qcow2", accel="auto", memory=4096, cpus=2,
                 kernel=None, initrd=None, width=1080, height=1920, density=480,
                 input_mode="mouse", display_mode="vnc", gpu="vga", guest_arch="amd64",
                 host=None, firmware=None, kernel_append=None):
    guest_arch = architecture(guest_arch)
    # Legacy callers are x86 Windows diagnostics; managed callers always supply the actual host.
    host = host or Host('Windows', 'amd64')
    selected_accel = host.accelerator(guest_arch, accel)
    if guest_arch == 'arm64' and gpu == 'vga': raise ValueError('ARM virt requires virtio GPU')
    if guest_arch == 'arm64' and kernel is not None and not kernel_append:
        raise ValueError('ARM64 direct boot needs explicit kernelAppend for the chosen Android image')
    if guest_arch == 'arm64' and kernel is None and firmware is None:
        raise ValueError('ARM64 disk boot needs read-only AArch64 UEFI firmware')
    if gpu not in ("vga", "virtio", "virgl"):
        raise ValueError("QEMU GPU must be vga, virtio or virgl")
    if display_mode not in ("vnc", "dbus"):
        raise ValueError("QEMU display mode must be vnc or dbus")
    if gpu == "virgl" and display_mode != "dbus":
        raise ValueError("VirGL GPU sharing requires D-Bus display")
    if display_mode == "vnc":
        _integer(vnc_port, "VNC port", 5900, 65535)
    _integer(qmp_port, "QMP port", 1, 65535)
    _integer(memory, "memory MiB", 256, 65536)
    _integer(cpus, "CPU count", 1, 256)
    _integer(width, "display width", 320, 4096)
    _integer(height, "display height", 320, 4096)
    _integer(density, "Android density", 120, 640)
    if width % 2 or height % 2:
        raise ValueError("QEMU display dimensions must be even for NV12")
    if width * height * 8 > 64 * 1024 * 1024:
        raise ValueError("QEMU display exceeds the 64 MiB double-buffered VGA memory limit")
    if display_mode == "vnc" and vnc_port == qmp_port:
        raise ValueError("VNC and QMP must use different ports")
    if accel not in ("auto", "whpx", "tcg", "kvm", "hvf"):
        raise ValueError("accel must be auto, whpx, or tcg")
    if disk_format not in ("raw", "qcow2"):
        raise ValueError("disk format must be raw or qcow2")
    if input_mode not in ("mouse", "touchscreen"):
        raise ValueError("QEMU input mode must be mouse or touchscreen")
    if initrd is not None and kernel is None:
        raise ValueError("initrd requires a kernel")
    runtime = _path(runtime)
    video_device = (f"{'virtio-vga-gl' if gpu == 'virgl' else 'virtio-vga'},id=phone-display,xres={width},yres={height},max_outputs=1,edid=on"
                    if gpu in ("virtio", "virgl") else f"VGA,xres={width},yres={height},vgamem_mb=64" +
                    (",id=phone-display" if input_mode == "touchscreen" else ""))
    if guest_arch == 'arm64':
        video_device = video_device.replace('virtio-vga-gl', 'virtio-gpu-gl-pci').replace('virtio-vga', 'virtio-gpu-pci')
    command = [str(_path(executable)), "-name", "MCAndroidPhone", "-machine",
               ('pc' if guest_arch == 'amd64' else 'virt,gic-version=3') + ',accel=' +
               (selected_accel + ':tcg' if accel == 'auto' and selected_accel != 'tcg' and guest_arch == 'amd64' else selected_accel),
               # Android x86_64 needs SSE4.2; avoid the host's failing WHPX XSAVE path.
               "-cpu", ("Nehalem" if guest_arch == "amd64" else "max" if selected_accel == "tcg" else "host"), "-m", str(memory), "-smp", str(cpus),
               "-display", ("dbus,p2p=on,gl=on" if gpu == "virgl" else "dbus,p2p=on,gl=off") if display_mode == "dbus" else "none", "-vga", "none",
               "-device", video_device,
               "-device", "qemu-xhci,id=usb", "-device",
               "virtio-multitouch-pci,id=phone-touch,display=phone-display" if input_mode == "touchscreen" else "usb-tablet,bus=usb.0",
               "-qmp",
               f"tcp:127.0.0.1:{qmp_port},server=on,wait=off", "-monitor", "none",
               # file: is a legacy chardev filename, not a key/value option.
               "-serial", "file:" + str(runtime / "qemu-serial.log"),
               "-netdev", "user,id=net0", "-device", ("e1000,netdev=net0" if guest_arch == "amd64" else "virtio-net-pci,netdev=net0"), "-no-reboot"]
    if display_mode == "vnc":
        command.extend(("-vnc", f"127.0.0.1:{vnc_port - 5900}"))
    if firmware is not None:
        command.extend(('-drive', 'if=pflash,unit=0,format=raw,readonly=on,file=' + _option_path(firmware)))
    if iso is not None and guest_arch == 'arm64':
        command.extend(('-device', 'virtio-scsi-pci,id=scsi', '-drive', 'if=none,id=cdrom,media=cdrom,readonly=on,format=raw,file=' + _option_path(iso), '-device', 'scsi-cd,drive=cdrom'))
    elif iso is not None:
        command.extend(("-drive", "file=" + _option_path(iso) + ",media=cdrom,readonly=on,format=raw",
                        "-boot", "d"))
    if disk is not None:
        command.extend(("-drive", "file=" + _option_path(disk) + f",if={'ide' if guest_arch == 'amd64' else 'virtio'},format={disk_format}",
                        "-snapshot"))
    if kernel is not None:
        command.extend(("-kernel", str(_path(kernel))))
        if initrd is not None:
            command.extend(("-initrd", str(_path(initrd))))
        append = kernel_append or ANDROID_KERNEL_TEMPLATE.format(width=width, height=height, density=density)
        if gpu == "virgl":
            append = append.replace("HWACCEL=0", "HWACCEL=1 GRALLOC=gbm HWC=drm")
        command.extend(("-append", append))
    return command


def wait_qmp(port, process, timeout=30, check=None):
    if not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
        raise ValueError("QMP wait timeout must be positive and finite")
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        if check is not None:
            check()
        exit_code = process.poll() if process is not None else None
        if exit_code is not None:
            raise RuntimeError(f"QEMU exited before QMP became ready (exit {exit_code})")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            with QmpClient(port, timeout=min(1, remaining)) as client:
                return client.status()
        except (OSError, QmpError) as exc:
            last_error = exc
        time.sleep(min(0.1, max(0, deadline - time.monotonic())))
    raise TimeoutError("QEMU QMP did not become ready") from last_error
