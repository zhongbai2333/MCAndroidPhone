"""Compile the independent Java client and consume actual Python mmap frames.

No Minecraft, Gradle, NCPB, or dependencies are required for the pattern backend.
The emulator option requires bridge/requirements.txt and a running authenticated AVD.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "bridge"))
from mcandroid_bridge.pattern import PatternBackend
from mcandroid_bridge.protocol import BridgeServer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=("pattern", "emulator"), default="pattern")
    parser.add_argument("--width", type=int, default=1080)
    parser.add_argument("--height", type=int, default=1920)
    parser.add_argument("--fps", type=float, default=30)
    parser.add_argument("--frames", type=int, default=90)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--grpc-port", type=int, default=8554)
    parser.add_argument("--grpc-discovery-file", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--rgb-bottom-up", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    classes = ROOT / "build" / "transport-smoke"
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted(source for kind in ("main", "test")
                     for source in (ROOT / "src" / kind / "java/com/zhongbai233/mcandroidphone/core").rglob("*.java"))
    subprocess.run([args.javac, "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)], check=True)
    if args.backend == "pattern":
        backend = PatternBackend(args.width, args.height, args.fps)
    else:
        from mcandroid_bridge.emulator import EmulatorBackend, read_token
        backend = EmulatorBackend(args.width, args.height, args.fps, args.grpc_port,
                                  read_token(discovery_file=args.grpc_discovery_file),
                                  args.ffmpeg, args.rgb_bottom_up)
    errors = []
    with tempfile.TemporaryDirectory(prefix="mcandroidphone-smoke-") as runtime:
        server = BridgeServer(runtime, backend, args.width, args.height, port=0)

        def serve():
            try:
                server.serve_forever()
            except Exception as error:
                errors.append(error)

        worker = threading.Thread(target=serve, name="smoke-bridge", daemon=True)
        worker.start()
        try:
            command = [args.java, "-cp", str(classes), "com.zhongbai233.mcandroidphone.core.BridgeSmoke",
                       str(server.config), str(args.frames)]
            if args.backend == "pattern":
                command.append("--touch")
            result = subprocess.run(command, capture_output=True, text=True, timeout=55)
            if errors:
                raise RuntimeError("Bridge failed during transport smoke") from errors[0]
            if result.returncode:
                raise RuntimeError(result.stderr.strip() or result.stdout.strip())
            report = json.loads(result.stdout.strip().splitlines()[-1])
            report["backend"] = args.backend
            report["targetFps"] = args.fps
            if args.backend == "pattern":
                if backend.position != (.6, .6) or backend.pressed:
                    raise AssertionError("Pattern did not receive complete DOWN/MOVE/UP")
                report["touchRoundTrip"] = True
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(report, ensure_ascii=False))
        finally:
            server.close()
            worker.join(timeout=5)
            if worker.is_alive():
                raise RuntimeError("Bridge did not shut down")


if __name__ == "__main__":
    main()
