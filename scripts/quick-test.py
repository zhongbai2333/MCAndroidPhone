"""One-command local testing. Python 3.10+; all setup is scoped to this project."""
from __future__ import annotations

import argparse
import contextlib
import datetime
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "bridge"))
from mcandroid_bridge._launch.windows_job import WindowsJob
from mcandroid_bridge._launch.qemu_runtime import find_qemu, find_iso, probe_qemu_display, qemu_command, wait_qmp, QmpClient
WINDOWS = os.name == "nt"
MAX_LOG_BYTES = 64 * 1024 * 1024


def say(message):
    print(message, flush=True)


def show_log_tail(path, lines=14):
    """Keep diagnostics bounded even if a failing subprocess floods its log."""
    try:
        with path.open("rb") as source:
            source.seek(0, os.SEEK_END)
            source.seek(max(0, source.tell() - 64 * 1024))
            tail = source.read().decode("utf-8", errors="replace").splitlines()[-lines:]
        say(f"[tail] {path}")
        say("\n".join(tail))
    except OSError as error:
        say(f"[tail] Cannot read {path}: {error}")


def read_properties(path):
    result = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
            if "=" in line and not line.lstrip().startswith(("#", "!")):
                key, value = line.split("=", 1)
                result[key.strip()] = value.strip()
    return result


def first_file(candidates):
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return Path(candidate).resolve()
    return None


def find_java(explicit=None):
    homes = [explicit, os.environ.get("JAVA_HOME")]
    found = shutil.which("javac")
    if found:
        homes.append(Path(found).resolve().parent.parent)
    for drive in ("C", "D", "E"):
        for vendor in ("Zulu", "Eclipse Adoptium", "Java", "Microsoft"):
            base = Path(f"{drive}:/Program Files") / vendor
            if base.is_dir():
                homes.extend(sorted(base.glob("*"), reverse=True))
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "jdks"
    if cache.is_dir():
        homes.extend(path.parent for path in cache.glob("*/release"))
        homes.extend(path.parent for path in cache.glob("*/*/release"))
    for home in homes:
        if not home:
            continue
        home = Path(home)
        java, javac = home / "bin/java.exe", home / "bin/javac.exe"
        if not WINDOWS:
            java, javac = home / "bin/java", home / "bin/javac"
        version = read_properties(home / "release").get("JAVA_VERSION", "").strip('"')
        # The Minecraft project explicitly targets JDK 25; avoid silently choosing an older JDK.
        if java.is_file() and javac.is_file() and re.match(r"25(?:\D|$)", version):
            return home.resolve()
        if explicit and str(home) == str(explicit):
            raise RuntimeError("--java-home must contain a complete JDK 25 (java and javac)")
    raise RuntimeError("JDK 25 not found. Install it once or pass --java-home PATH; current PATH/JAVA_HOME are checked automatically.")


def find_sdk(explicit=None):
    candidates = [explicit, os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    command = shutil.which("emulator")
    if command:
        candidates.append(Path(command).resolve().parent.parent)
    local = read_properties(ROOT / "local.properties").get("sdk.dir")
    if local:
        candidates.append(local.replace("\\:", ":").replace("\\\\", "\\"))
    candidates.append(Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "Android/Sdk")
    for drive in ("C", "D", "E"):
        candidates.extend((Path(f"{drive}:/AndroidSdk"), Path(f"{drive}:/Android/Sdk")))
    for candidate in candidates:
        if candidate and (Path(candidate) / "emulator/emulator.exe").is_file():
            return Path(candidate).resolve()
    return None


def find_ffmpeg(explicit=None):
    if explicit:
        result = first_file([explicit])
        if result is None:
            raise RuntimeError("--ffmpeg does not point to a file")
        return result
    candidates = [shutil.which("ffmpeg"), os.environ.get("FFMPEG")]
    for drive in ("C", "D", "E"):
        candidates.extend((Path(f"{drive}:/Program Files/ffmpeg/bin/ffmpeg.exe"), Path(f"{drive}:/ffmpeg/bin/ffmpeg.exe")))
    return first_file(candidates)


def avd_home():
    if os.environ.get("ANDROID_AVD_HOME"):
        return Path(os.environ["ANDROID_AVD_HOME"])
    for name in ("ANDROID_USER_HOME", "ANDROID_EMULATOR_HOME"):
        if os.environ.get(name):
            return Path(os.environ[name]) / "avd"
    return Path(os.environ.get("USERPROFILE", str(Path.home()))) / ".android/avd"


def choose_avd(sdk, requested=None):
    home = avd_home()
    choices = []
    for ini in sorted(home.glob("*.ini")):
        values = read_properties(ini)
        directory = Path(values.get("path", str(home / (ini.stem + ".avd"))))
        config = read_properties(directory / "config.ini")
        image = config.get("image.sysdir.1")
        if not image:
            continue
        image_path = Path(image)
        if not image_path.is_absolute():
            image_path = sdk / image_path
        if (image_path / "system.img").is_file():
            choices.append((ini.stem, config))
    if requested:
        choices = [entry for entry in choices if entry[0] == requested]
    if not choices:
        raise RuntimeError("No usable AVD/system image found" + (f" for {requested}" if requested else "")
                           + ". Create one in Android Studio once, or use 'test-phone.cmd pattern' / 'test-phone.cmd quick'.")
    choices.sort(key=lambda entry: (entry[1].get("hw.lcd.width") != "1080", entry[0]))
    return choices[0][0]


def gradle_command(project):
    properties = read_properties(project / "gradle/wrapper/gradle-wrapper.properties")
    match = re.search(r"/(gradle-([\d.]+)-(?:bin|all))\.zip", properties.get("distributionUrl", ""))
    if match:
        distribution, version = match.groups()
        cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "wrapper/dists" / distribution
        matches = sorted(cache.glob(f"*/gradle-{version}/bin/gradle.bat"))
        if matches:
            return matches[0]
    return project / ("gradlew.bat" if WINDOWS else "gradlew")


def gradle_invocation(project, java_home):
    """Invoke Gradle's actual Java launcher, so batch/shell quoting cannot change path arguments."""
    java = java_home / "bin/java.exe"
    selected = gradle_command(project)
    if selected.parent.name == "bin":
        libraries = selected.parent.parent / "lib"
        main = sorted(libraries.glob("gradle-gradle-cli-main-*.jar"))
        agents = sorted((libraries / "agents").glob("gradle-instrumentation-agent-*.jar"))
        if main:
            return [java, "-Xmx64m", "-Xms64m", *([f"-javaagent:{agents[0]}"] if agents else []), "-jar", main[0]]
    wrapper = project / "gradle/wrapper/gradle-wrapper.jar"
    if not wrapper.is_file():
        raise RuntimeError(f"Gradle wrapper missing: {wrapper}")
    return [java, "-Xmx64m", "-classpath", wrapper, "org.gradle.wrapper.GradleWrapperMain"]


@contextlib.contextmanager
def project_lock(project):
    directory = project / ".runtime"
    directory.mkdir(exist_ok=True)
    handle = (directory / "quicktest.lock").open("a+b")
    handle.seek(0)
    try:
        if WINDOWS:
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        handle.close()
        raise RuntimeError("Another quick-test session is already using this project. Close it before starting a second one.")
    try:
        # On Windows even reading another session's locked byte fails; acquire before touching it.
        if handle.read(1) == b"":
            handle.write(b"0")
            handle.flush()
        yield
    finally:
        handle.close()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def free_console_pair():
    for port in range(5556, 5683, 2):
        with socket.socket() as first, socket.socket() as second:
            try:
                first.bind(("127.0.0.1", port))
                second.bind(("127.0.0.1", port + 1))
                return port
            except OSError:
                continue
    raise RuntimeError("No free emulator console/ADB port pair")


def free_vnc_port():
    for port in range(5900, 6000):
        with socket.socket() as sock:
            try:
                sock.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    raise RuntimeError("No free local QEMU VNC port in 5900..5999")


class Session:
    def __init__(self, project, env, mode):
        self.project, self.env, self.mode = project, env, mode
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
        self.runtime = project / ".runtime/quicktest" / stamp
        self.runtime.mkdir(parents=True)
        self.processes = []
        self.child_records = {}
        self.job = WindowsJob()
        self.qemu_port = None
        self.qemu_process = None
        self.qemu_uuid = None

    def start(self, name, command, cwd=None):
        log_path = self.runtime / f"{name}.log"
        output = log_path.open("wb")
        flags = (subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP) if WINDOWS else 0
        child_record = self.runtime / f"{name}-{uuid.uuid4().hex}.process.json"
        try:
            # Popen sequences preserve spaces in SDK, project and Java paths. No shell interpolation.
            # Gate execution until the wrapper belongs to our job, before it can spawn any descendants.
            # A Windows venv python.exe can redirect into another process. The
            # stdlib-only gate uses the base interpreter so its PID is Popen.pid.
            gate_python = getattr(sys, "_base_executable", None) or sys.executable
            gated = [gate_python, "-m", "mcandroid_bridge._launch.gate",
                     "--pid-file", str(child_record), "--", *map(str, command)]
            process = subprocess.Popen(gated, cwd=cwd or self.project, env=self.env,
                                       stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                                       creationflags=flags)
            try:
                self.job.add(process)
                process.stdin.write(b"G")
                process.stdin.flush()
                process.stdin.close()
            except Exception:
                process.kill()
                process.wait(timeout=10)
                raise
        finally:
            output.close()
        self.processes.append((name, process))
        self.child_records[process.pid] = child_record
        self.save("running")
        return process, log_path

    def child_pid(self, process, timeout=5):
        """Wait for the owned gate's native child PID, never substitute its wrapper PID."""
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("Child PID timeout must be positive and finite")
        if not any(owned is process for _name, owned in self.processes):
            raise ValueError("Child PID requested for a process outside this session")
        path = self.child_records.get(process.pid)
        if path is None:
            raise RuntimeError("Managed process has no child PID record")
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.check_logs()
            if process.poll() is not None:
                raise RuntimeError("Managed process exited before its child PID became ready")
            try:
                with path.open("rb") as record_file:
                    raw = record_file.read(4097)
            except FileNotFoundError:
                time.sleep(min(.02, max(0, deadline - time.monotonic())))
                continue
            try:
                if len(raw) > 4096:
                    raise ValueError("record exceeds limit")
                record = json.loads(raw)
                pid = record["childPid"]
                if (type(record["wrapperPid"]) is not int or record["wrapperPid"] != process.pid
                        or type(pid) is not int or not 0 < pid <= 0xffffffff or pid == process.pid):
                    raise ValueError("invalid or mismatched process identity")
            except (ValueError, KeyError, TypeError) as error:
                raise RuntimeError(f"Invalid managed child PID record: {path}") from error
            return pid
        raise TimeoutError(f"Managed child PID did not become ready within {timeout}s: {path}")

    def save(self, state):
        (self.runtime / "session.json").write_text(json.dumps({
            "mode": self.mode, "state": state,
            "processes": [{"name": name, "pid": process.pid, "exitCode": process.poll()}
                          for name, process in self.processes]
        }, indent=2), encoding="utf-8")

    def check_logs(self):
        # Only this session's own logs; launch() finally closes its job on failure.
        for path in self.runtime.glob("*.log"):
            if path.stat().st_size > MAX_LOG_BYTES:
                show_log_tail(path)
                raise RuntimeError(f"Log exceeded {MAX_LOG_BYTES // (1024 * 1024)} MiB: {path}. "
                                   "Stopping the owned test session to prevent runaway disk usage.")

    def run(self, name, command, cwd=None, timeout=None, watch=()):
        say(f"[run] {name}")
        process, log = self.start(name, command, cwd)
        start = time.monotonic()
        last_notice = start
        while process.poll() is None:
            self.check_logs()
            for other_name, other in watch:
                if other.poll() is not None:
                    show_log_tail(self.runtime / f"{other_name}.log")
                    raise RuntimeError(f"{other_name} exited while {name} was running. See {self.runtime}")
            elapsed = time.monotonic() - start
            if timeout and elapsed > timeout:
                show_log_tail(log)
                raise RuntimeError(f"{name} timed out after {timeout}s. See {log}")
            if time.monotonic() - last_notice >= 20:
                say(f"[wait] {name}: {int(elapsed)}s (log: {log.name})")
                last_notice = time.monotonic()
            time.sleep(.2)
        self.check_logs()
        if process.returncode != 0:
            show_log_tail(log)
            raise RuntimeError(f"{name} failed (exit {process.returncode}). Full log: {log}")
        return log

    def stop(self):
        if self.qemu_port is not None and self.qemu_process is not None and self.qemu_process.poll() is None:
            # Only set after this Session started and verified its own QEMU process.
            with contextlib.suppress(Exception):
                with QmpClient(self.qemu_port, timeout=2) as control:
                    if control.execute("query-uuid").get("UUID") == self.qemu_uuid:
                        control.execute("quit")
        # The job tracks descendants even if emulator/Gradle's launcher already exited.
        self.job.close()
        failures = []
        for name, process in reversed(self.processes):
            try:
                process.wait(timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    process.kill()
                    process.wait(timeout=5)
                except (OSError, subprocess.TimeoutExpired):
                    failures.append(name)
        # These are only disposable files created in this session's unique runtime, never user captures.
        for path in (self.runtime / "bridge").glob("frames-*.nv12"):
            try:
                path.unlink()
            except OSError:
                pass
        try:
            (self.runtime / "bridge/bridge.properties").unlink(missing_ok=True)
        except OSError:
            pass
        if failures:
            raise RuntimeError("Could not finish cleanup for: " + ", ".join(failures))


def dependencies(session):
    requirements = session.project / "bridge/requirements.txt"
    candidates = [session.project / ".venv/Scripts/python.exe", session.project / "bridge/.venv/Scripts/python.exe"]
    probe = "import importlib.metadata as m; assert m.version('grpcio') == '1.78.0'; assert m.version('protobuf') == '6.33.5'"
    for python in [*candidates, Path(sys.executable)]:
        if python.is_file():
            result = subprocess.run([str(python), "-c", probe], capture_output=True, timeout=15, env=session.env)
            if result.returncode == 0:
                say(f"[ready] Python bridge dependencies: {python}")
                return python
    python = candidates[0]
    if not python.is_file():
        session.run("python-venv", [sys.executable, "-m", "venv", "--copies", session.project / ".venv"], timeout=120)
    session.run("python-dependencies", [python, "-m", "pip", "install", "--disable-pip-version-check",
                "--retries", "2", "--timeout", "20", "-r", requirements], timeout=300)
    session.run("python-dependencies-check", [python, "-c", probe], timeout=20)
    return python


def emulator(session, python, sdk, avd, ffmpeg, args):
    grpc_port, console = free_port(), free_console_pair()
    session.env["ANDROID_AVD_HOME"] = str(avd_home())
    session.env["ANDROID_SDK_ROOT"] = str(sdk)
    command = [sdk / "emulator/emulator.exe", "-avd", avd, "-port", console, "-grpc", grpc_port,
               "-grpc-use-token", "-read-only", "-no-snapshot-load", "-no-snapshot-save", "-no-window",
               "-no-audio", "-gpu", "auto", "-camera-front", "none", "-camera-back", "none", "-no-boot-anim"]
    say(f"[start] Android AVD: {avd} (read-only)")
    process, log = session.start("emulator", command)
    directory = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "Temp/avd/running"
    deadline = time.monotonic() + 90
    discovery = None
    while time.monotonic() < deadline:
        session.check_logs()
        if process.poll() is not None and process.returncode != 0:
            show_log_tail(log)
            raise RuntimeError(f"Emulator exited during startup. See {log}")
        for ini in directory.glob("pid_*.ini"):
            values = read_properties(ini)
            if values.get("grpc.port") == str(grpc_port) and values.get("port.serial") == str(console) and values.get("grpc.token"):
                discovery = ini
                break
        if discovery:
            break
        time.sleep(.5)
    if not discovery:
        show_log_tail(log)
        raise RuntimeError(f"Emulator gRPC did not start within 90s. See {log}")
    probe_log = session.run("android-display-ready", [python, session.project / "scripts/emulator-probe.py",
                            "--project-root", session.project, "--discovery-file", discovery, "--timeout", "120"],
                            timeout=135)
    dimensions = json.loads(probe_log.read_text(encoding="utf-8").strip().splitlines()[-1])
    say(f"[ready] Android native display: {dimensions['width']}x{dimensions['height']}")
    return process, ["--grpc-port", str(grpc_port), "--grpc-discovery-file", str(discovery),
                     "--ffmpeg", str(ffmpeg), "--width", str(dimensions["width"]), "--height", str(dimensions["height"])]


def start_qemu(session, args):
    color_order = args.qemu_color_order
    if color_order not in ("auto", "rgb", "bgr"):
        raise ValueError("QEMU color order must be auto, rgb or bgr")
    input_mode = args.qemu_input
    if input_mode not in ("auto", "mouse", "touchscreen"):
        raise ValueError("QEMU input mode must be auto, mouse or touchscreen")
    display_mode = args.qemu_display
    if display_mode not in ("auto", "dbus", "vnc"):
        raise ValueError("QEMU display mode must be auto, dbus or vnc")
    if display_mode == "auto":
        display_mode = "dbus" if WINDOWS else "vnc"
    gpu = args.qemu_gpu
    if gpu not in ("auto", "vga", "virtio", "virgl"):
        raise ValueError("QEMU GPU must be auto, vga, virtio or virgl")
    executable = find_qemu(ROOT, args.qemu_exe)
    if executable is None:
        raise RuntimeError("Stock QEMU not found. Set --qemu-exe PATH or place the runtime in .runtime/qemu/bin; see docs/qemu.md.")
    iso = None if args.qemu_bios or (args.qemu_disk and not args.qemu_iso) else find_iso(ROOT, args.qemu_iso)
    disk = args.qemu_disk.resolve() if args.qemu_disk else None
    if args.qemu_iso and iso is None:
        raise RuntimeError("--qemu-iso must point to an existing ISO image")
    if disk is not None and not disk.is_file():
        raise RuntimeError("--qemu-disk must point to an existing disk image")
    if not args.qemu_bios and iso is None and disk is None:
        raise RuntimeError("No Android-x86 ISO/disk found. Pass --qemu-iso PATH or --qemu-disk PATH; --qemu-bios tests only QEMU firmware.")
    kernel, initrd = args.qemu_kernel, args.qemu_initrd
    if iso is not None and kernel is None and disk is None:
        # Prepared Live ISO assets bypass the boot menu. Only a matching sibling directory is used.
        prepared = iso.parent / iso.stem
        if (prepared / "kernel").is_file() and (prepared / "initrd.img").is_file():
            kernel, initrd = prepared / "kernel", prepared / "initrd.img"
    for part in (kernel, initrd):
        if part is not None and not part.is_file():
            raise RuntimeError(f"QEMU boot file does not exist: {part}")
    if color_order == "auto":
        # Verified Android 9 direct-kernel software display profiles need red/blue correction.
        # Interpret those bytes as BGR in the existing FFmpeg conversion, without another copy.
        color_order = "bgr" if kernel is not None else "rgb"
    if input_mode == "auto":
        input_mode = "touchscreen" if kernel is not None else "mouse"
    if gpu == "auto":
        gpu = "virtio" if display_mode == "dbus" and kernel is not None else "vga"
    if gpu == "virgl":
        if args.qemu_bios:
            raise ValueError("VirGL GPU mode needs a GPU-capable guest; use virtio for BIOS tests")
        if args.qemu_color_order == "bgr":
            raise ValueError("VirGL uses native D3D11 colors; BGR correction is only for the software framebuffer")
        if not WINDOWS or display_mode != "dbus":
            raise RuntimeError("D3D11 GPU sharing requires Windows and --qemu-display dbus")
        angle = ROOT / ".runtime/angle/bin"
        if not all((angle / name).is_file() for name in ("libEGL.dll", "libGLESv2.dll")):
            raise RuntimeError("ANGLE runtime missing; run scripts/setup-angle.ps1 or see docs/gpu.md")
        session.env["PATH"] = str(angle) + os.pathsep + session.env.get("PATH", "")
        color_order = "rgb"
    vnc_port, qmp_port = (free_vnc_port() if display_mode == "vnc" else None), free_port()
    while vnc_port == qmp_port:
        qmp_port = free_port()
    command = qemu_command(executable, vnc_port, qmp_port, session.runtime, iso=iso, disk=disk,
                           disk_format=args.qemu_disk_format, accel=args.qemu_accel,
                           memory=args.qemu_memory, cpus=args.qemu_cpus, kernel=kernel, initrd=initrd,
                           width=args.qemu_width, height=args.qemu_height, density=args.qemu_density,
                           input_mode=input_mode, display_mode=display_mode, gpu=gpu)
    vm_uuid = str(uuid.uuid4())
    command.extend(("-uuid", vm_uuid))
    say(f"[start] Stock QEMU: {executable}")
    say(f"[guest] {iso or disk or 'firmware diagnostic (not Android)'}")
    say(f"[input] QEMU {input_mode}")
    say(f"[gpu] QEMU {gpu}")
    if kernel is not None:
        say(f"[display] Android target: {args.qemu_width}x{args.qemu_height}, {args.qemu_density} dpi")
    process, log = session.start("qemu", command)
    try:
        state = wait_qmp(qmp_port, process, timeout=30, check=session.check_logs)
    except Exception:
        show_log_tail(log)
        raise
    session.qemu_port = qmp_port
    session.qemu_process, session.qemu_uuid = process, vm_uuid
    if display_mode == "dbus":
        try:
            native_pid = session.child_pid(process)
        except Exception:
            show_log_tail(log)
            raise
        endpoint = ["--qemu-pid", str(native_pid)]
        say(f"[ready] QEMU QMP: {state.get('status', 'unknown')}; direct D-Bus display, QEMU PID {native_pid}")
    else:
        endpoint = ["--vnc-port", str(vnc_port)]
        say(f"[ready] QEMU QMP: {state.get('status', 'unknown')}; VNC 127.0.0.1:{vnc_port}")
    return process, [*endpoint, "--qmp-port", str(qmp_port),
                     "--width", "640", "--height", "480", "--qemu-color-order", color_order,
                     "--qemu-input", input_mode, "--qemu-display", "d3d11" if gpu == "virgl" else display_mode]


def use_managed_runtime(args):
    return args.mode in ("qemu", "pattern") and not getattr(args, "external_bridge", False) and not getattr(args, "qemu_bios", False)


def managed_game(session, args, java_home, ffmpeg):
    """Launch Minecraft only. The mod starts its own embedded bridge and native runtime on item use."""
    command = [*gradle_invocation(ROOT, java_home), "--console=plain", "--no-daemon", "runClient",
               f"-PphoneRuntimeBackend={args.mode}", f"-PphoneRuntimePython={sys.executable}"]
    if args.mode == "qemu":
        if args.qemu_display not in ("auto", "dbus") or args.qemu_gpu not in ("auto", "virtio", "virgl") or args.qemu_input not in ("auto", "touchscreen") or args.qemu_color_order != "auto":
            raise ValueError("Managed mode supports D-Bus virtio/virgl touchscreen. Use --external-bridge for legacy diagnostic profiles.")
        command.append(f"-PphoneRuntimeGpu={'virtio' if args.qemu_gpu == 'auto' else args.qemu_gpu}")
        mappings = {"Qemu": "qemu_exe", "Iso": "qemu_iso", "Disk": "qemu_disk", "DiskFormat": "qemu_disk_format",
                    "Kernel": "qemu_kernel", "Initrd": "qemu_initrd", "Width": "qemu_width", "Height": "qemu_height",
                    "Density": "qemu_density", "Memory": "qemu_memory", "Cpus": "qemu_cpus", "Accel": "qemu_accel"}
        for suffix, name in mappings.items():
            value = getattr(args, name, None)
            if value is not None:
                if isinstance(value, Path): value = value.resolve()
                command.append(f"-PphoneRuntime{suffix}={value}")
        if ffmpeg: command.append(f"-PphoneRuntimeFfmpeg={ffmpeg}")
    screenshot = session.runtime / "android-phone.png"
    if args.smoke: command.append("-PphoneSmoke=true")
    elif args.world_smoke:
        command.extend(("-PphoneWorldSmoke=true", f"-PphoneSmokeScreenshot={screenshot}"))
        if args.mode == "pattern": command.append("-PphonePatternSmoke=true")
    say("[managed] Starting Minecraft only. Right-click the phone to boot its JAR-owned runtime.")
    log = session.run("minecraft", command, timeout=300 if args.world_smoke else 600 if args.smoke else None)
    if args.smoke or args.world_smoke:
        game_log = ROOT / "run/logs/latest.log"
        marker = "ANDROIDPHONE_WORLD_SMOKE_OK" if args.world_smoke else "ANDROIDPHONE_SMOKE_OK"
        if not game_log.is_file() or marker not in game_log.read_text(encoding="utf-8", errors="replace"):
            show_log_tail(log); raise RuntimeError(f"Minecraft did not report {marker}")
        if args.world_smoke:
            if not screenshot.is_file() or screenshot.stat().st_size == 0: raise RuntimeError("World smoke screenshot missing")
            say(f"[PASS] Phone world smoke completed. Screenshot: {screenshot}")
        else: say("[PASS] Standalone Minecraft title screen reached; runtime was not prestarted.")


def launch(args):
    java_home = find_java(args.java_home)
    sdk = find_sdk(args.sdk)
    ffmpeg = find_ffmpeg(args.ffmpeg)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    env["PYTHONPATH"] = str(ROOT / "bridge")
    env["PYTHONUTF8"] = "1"
    env["PYTHONUNBUFFERED"] = "1"
    say(f"[ready] Project: {ROOT}")
    say(f"[ready] JDK 25: {java_home}")
    if args.mode == "check":
        avd = choose_avd(sdk, args.avd) if sdk else None
        qemu = find_qemu(ROOT, args.qemu_exe)
        say(json.dumps({"python": sys.executable, "sdk": str(sdk) if sdk else None,
                        "avd": avd, "ffmpeg": str(ffmpeg) if ffmpeg else None,
                        "gradle": str(gradle_command(ROOT)),
                        "qemu": str(qemu) if qemu else None, "qemuIso": str(find_iso(ROOT)),
                        "qemuDisplayAuto": "dbus" if WINDOWS else "vnc",
                        "qemuDisplayCapabilities": probe_qemu_display(qemu)}, indent=2))
        return
    avd = None
    if args.mode == "android":
        if not sdk or not ffmpeg:
            raise RuntimeError("Android mode needs an installed Emulator SDK and FFmpeg. Pass --sdk / --ffmpeg, or run 'test-phone.cmd pattern'.")
        avd = choose_avd(sdk, args.avd)
    if args.mode == "qemu" and not use_managed_runtime(args) and args.qemu_gpu != "virgl" and ffmpeg is None:
        raise RuntimeError("QEMU mode needs FFmpeg; pass --ffmpeg PATH.")
    with project_lock(ROOT):
        session = Session(ROOT, env, args.mode)
        say(f"[logs] {session.runtime}")
        state = "failed"
        try:
            if args.mode == "quick":
                log = session.run("transport-smoke", [sys.executable, ROOT / "scripts/transport-smoke.py",
                    "--java", java_home / "bin/java.exe", "--javac", java_home / "bin/javac.exe",
                    "--output", session.runtime / "transport.json"], timeout=90)
                say(log.read_text(encoding="utf-8").strip())
                session.run("core-tests", [java_home / "bin/java.exe", "-cp", ROOT / "build/transport-smoke",
                            "com.zhongbai233.mcandroidphone.core.CoreSelfTest"], timeout=30)
                test_log = session.run("bridge-tests", [sys.executable, "-m", "unittest", "discover", "-s", ROOT / "bridge/tests", "-v"], timeout=45)
                say("\n".join(test_log.read_text(encoding="utf-8", errors="replace").splitlines()[-4:]))
                say("[PASS] Core/protocol and 1080p30 frame/input round-trip checks completed; optional test skips are shown above.")
            elif use_managed_runtime(args):
                managed_game(session, args, java_home, ffmpeg)
            else:
                say("[ready] Standalone MC Android Phone (no companion mods required)")
                python = dependencies(session) if args.mode == "android" else Path(sys.executable)
                watch = []
                extra = ["--width", "1080", "--height", "1920"]
                if args.mode == "android":
                    process, extra = emulator(session, python, sdk, avd, ffmpeg, args)
                elif args.mode == "qemu":
                    process, extra = start_qemu(session, args)
                    if ffmpeg: extra.extend(("--ffmpeg", str(ffmpeg)))
                    watch.append(("qemu", process))
                bridge, bridge_log = session.start("bridge", [python, "-m", "mcandroid_bridge", "--backend",
                    {"android": "emulator", "qemu": "qemu"}.get(args.mode, "pattern"), "--runtime", session.runtime / "bridge",
                    "--port", "0", "--fps", "30", *extra])
                config = session.runtime / "bridge/bridge.properties"
                deadline = time.monotonic() + 45
                while not config.is_file():
                    session.check_logs()
                    if bridge.poll() is not None or time.monotonic() > deadline:
                        show_log_tail(bridge_log)
                        raise RuntimeError(f"Bridge did not start. See {bridge_log}")
                    time.sleep(.2)
                # Actually receive a shared-memory frame before asking Minecraft to start.
                session.run("bridge-first-frame", [python, "-m", "mcandroid_bridge.smoke", "--runtime",
                            session.runtime / "bridge", "--frames", "1"], timeout=210 if args.mode == "qemu" and args.qemu_gpu == "virgl" else 45, watch=[*watch, ("bridge", bridge)])
                watch.append(("bridge", bridge))
                say("[ready] Bridge frame received. Starting Minecraft.")
                if args.world_smoke:
                    say("[smoke] Creating a new temporary creative world to test phone use, frame upload and screen projection.")
                elif not args.smoke:
                    say("In a creative world: open Android Phones, or /give @s mcandroidphone:android_phone.")
                    say("Hold the Android Phone in your main hand and right-click to connect/open. F8 toggles the connection.")
                command = [*gradle_invocation(ROOT, java_home), "--console=plain", "--no-daemon", "runClient",
                           f"-PbridgeConfig={config}"]
                screenshot = session.runtime / "android-phone.png"
                if args.smoke:
                    command.append("-PphoneSmoke=true")
                elif args.world_smoke:
                    command.extend(("-PphoneWorldSmoke=true", f"-PphoneSmokeScreenshot={screenshot}"))
                    if args.mode == "pattern":
                        command.append("-PphonePatternSmoke=true")
                timeout = 300 if args.world_smoke else (600 if args.smoke else None)
                minecraft_log = session.run("minecraft", command, timeout=timeout, watch=watch)
                if args.smoke or args.world_smoke:
                    game_log = ROOT / "run/logs/latest.log"
                    marker = "ANDROIDPHONE_WORLD_SMOKE_OK" if args.world_smoke else "ANDROIDPHONE_SMOKE_OK"
                    if not game_log.is_file() or marker not in game_log.read_text(encoding="utf-8", errors="replace"):
                        show_log_tail(minecraft_log)
                        show_log_tail(game_log)
                        raise RuntimeError(f"Minecraft exited without the expected smoke marker: {marker}")
                    if args.world_smoke:
                        if not screenshot.is_file() or screenshot.stat().st_size == 0:
                            show_log_tail(game_log)
                            raise RuntimeError(f"World smoke completed without its screenshot: {screenshot}")
                        say(f"[PASS] Phone world smoke completed. Screenshot: {screenshot}")
                    else:
                        say("[PASS] Android/pattern bridge and Minecraft startup smoke completed.")
                else:
                    say("[done] Minecraft closed.")
            state = "passed"
        finally:
            try:
                session.stop()
            finally:
                session.save(state)
                say(f"[logs] {session.runtime}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", nargs="?", choices=("android", "qemu", "pattern", "quick", "check"), default="qemu")
    smoke_options = parser.add_mutually_exclusive_group()
    smoke_options.add_argument("--smoke", action="store_true", help="Automatically exit after Minecraft title-screen checks")
    smoke_options.add_argument("--world-smoke", action="store_true",
                               help="Test phone use and rendering in a new temporary creative world, save a screenshot, then exit")
    parser.add_argument("--external-bridge", action="store_true", help="Legacy diagnostics: start runtime before Minecraft instead of letting the JAR own it")
    parser.add_argument("--avd")
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--ffmpeg", type=Path)
    parser.add_argument("--qemu-exe", type=Path)
    media = parser.add_mutually_exclusive_group()
    media.add_argument("--qemu-iso", type=Path)
    media.add_argument("--qemu-bios", action="store_true", help="Only test QEMU firmware; does not boot Android")
    parser.add_argument("--qemu-disk", type=Path, help="Existing disk; writes go to a disposable QEMU snapshot")
    parser.add_argument("--qemu-disk-format", choices=("qcow2", "raw"), default="qcow2")
    parser.add_argument("--qemu-accel", choices=("auto", "whpx", "tcg"), default="auto")
    parser.add_argument("--qemu-memory", type=int, default=4096, help="Guest RAM in MiB")
    parser.add_argument("--qemu-cpus", type=int, default=2)
    parser.add_argument("--qemu-width", type=int, default=1080, help="Guest display width; direct Android kernel boot required to force it")
    parser.add_argument("--qemu-height", type=int, default=1920, help="Guest display height; direct Android kernel boot required to force it")
    parser.add_argument("--qemu-density", type=int, default=480, help="Android UI density for direct kernel boot")
    parser.add_argument("--qemu-color-order", choices=("auto", "rgb", "bgr"), default="auto",
                        help="auto corrects the Android 9 direct-kernel color quirk; rgb disables correction")
    parser.add_argument("--qemu-input", choices=("auto", "mouse", "touchscreen"), default="auto",
                        help="auto selects a touchscreen for direct Android kernel boot and a mouse for other guests")
    parser.add_argument("--qemu-display", choices=("auto", "dbus", "vnc"), default="auto",
                        help="auto selects direct D-Bus display on Windows and VNC elsewhere")
    parser.add_argument("--qemu-gpu", choices=("auto", "vga", "virtio", "virgl"), default="auto",
                        help="auto selects virtio-vga 2D for D-Bus direct Android kernel boot, otherwise standard VGA")
    parser.add_argument("--qemu-kernel", type=Path)
    parser.add_argument("--qemu-initrd", type=Path)
    args = parser.parse_args()
    if args.world_smoke and args.mode not in ("android", "pattern", "qemu"):
        parser.error("--world-smoke requires android, qemu or pattern mode")
    if args.qemu_bios and any((args.qemu_disk, args.qemu_kernel, args.qemu_initrd)):
        parser.error("--qemu-bios cannot be combined with a disk or direct kernel")
    try:
        launch(args)
        return 0
    except KeyboardInterrupt:
        say("[stopped] Test cancelled; owned background processes cleaned up.")
        return 130
    except Exception as error:
        say(f"[ERROR] {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
