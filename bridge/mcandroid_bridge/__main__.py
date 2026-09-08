import argparse
from pathlib import Path
import sys

from .pattern import PatternBackend
from .protocol import BridgeServer, validate_dimensions


def main():
    parser = argparse.ArgumentParser(description="MCAndroidPhone local NV12 frame/input bridge")
    parser.add_argument("--backend", choices=("pattern", "emulator", "qemu"), default="pattern")
    parser.add_argument("--runtime", type=Path, default=Path("runtime"))
    parser.add_argument("--width", type=int, default=1080)
    parser.add_argument("--height", type=int, default=1920)
    parser.add_argument("--fps", type=float, default=30)
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--grpc-port", type=int, default=8554)
    parser.add_argument("--vnc-port", type=int, default=5900, help="QEMU VNC TCP port on 127.0.0.1")
    parser.add_argument("--qmp-port", type=int, help="QEMU QMP TCP port on 127.0.0.1, for display handoff and guest navigation")
    parser.add_argument("--qemu-display", choices=("vnc", "dbus", "d3d11"), default="vnc",
                        help="dbus uses direct Windows shared framebuffer; requires QEMU -display dbus,p2p=on,gl=off")
    parser.add_argument("--qemu-pid", type=int, help="Actual QEMU process ID for Windows shared display sockets")
    parser.add_argument("--grpc-token", help="Bearer token; prefer token file to avoid shell history")
    parser.add_argument("--grpc-token-file", type=Path)
    parser.add_argument("--grpc-discovery-file", type=Path, help="Android Emulator pid_*_info.ini containing grpc.token")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--qemu-color-order", choices=("rgb", "bgr"), default="rgb",
                        help="Use bgr to correct Android-x86 Bochs software framebuffer colors")
    parser.add_argument("--qemu-input", choices=("mouse", "touchscreen"), default="mouse",
                        help="touchscreen requires virtio-multitouch bound to display id phone-display")
    orientation = parser.add_mutually_exclusive_group()
    orientation.add_argument("--rgb-bottom-up", action="store_true", help="Flip rows for emulator implementations with bottom-up RGB output")
    orientation.add_argument("--rgb-top-down", action="store_false", dest="rgb_bottom_up", help="Use top-down RGB rows (default, verified on Android Emulator 37.1.11)")
    parser.set_defaults(rgb_bottom_up=False)
    args = parser.parse_args()
    try:
        validate_dimensions(args.width, args.height)
        if not 1 <= args.fps <= 120:
            raise ValueError("--fps must be between 1 and 120")
        if not 0 <= args.port <= 65535 or not 1 <= args.grpc_port <= 65535 or not 1 <= args.vnc_port <= 65535:
            raise ValueError("invalid port")
        if args.qmp_port is not None and not 1 <= args.qmp_port <= 65535:
            raise ValueError("invalid QMP port")
        if args.backend == "qemu" and args.qemu_display == "d3d11":
            from .gpu import GpuBackend, GpuServer
            factory = lambda: GpuBackend(args.width, args.height, args.fps, args.qemu_pid, args.qmp_port,
                                         color_order="rgb", input_mode=args.qemu_input)
            server = GpuServer(args.runtime, factory, args.port)
            print(f"MCAndroidPhone GPU bridge: D3D11 leases, no FFmpeg/pixel mmap; {server.config}", flush=True)
            server.serve_forever()
            return 0
        if args.backend == "pattern":
            backend = PatternBackend(args.width, args.height, args.fps)
        elif args.backend == "emulator":
            from .emulator import EmulatorBackend, read_token
            token = read_token(args.grpc_token, args.grpc_token_file, args.grpc_discovery_file)
            backend = EmulatorBackend(args.width, args.height, args.fps, args.grpc_port, token, args.ffmpeg, args.rgb_bottom_up)
        else:
            from .qemu import QemuBackend
            if args.qemu_display == "dbus":
                from .qemu_dbus import QemuDbusBackend
                backend = QemuDbusBackend(args.width, args.height, args.fps, args.qemu_pid, args.qmp_port,
                                          args.ffmpeg, color_order=args.qemu_color_order, input_mode=args.qemu_input)
            else:
                backend = QemuBackend(args.width, args.height, args.fps, args.vnc_port, args.ffmpeg, args.qmp_port,
                                      color_order=args.qemu_color_order, input_mode=args.qemu_input)
        server = BridgeServer(args.runtime, backend, args.width, args.height, args.port)
        if args.backend == "qemu":
            backend.set_resize_handler(server.reconfigure)
        print(f"MCAndroidPhone {args.backend}: {args.width}x{args.height}, at most {args.fps:g} fps, NV12 BT.709 limited", flush=True)
        print(f"Local discovery: {server.config} (127.0.0.1:{server.port}); token is stored in this file", flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except Exception as exc:
        print(f"Bridge failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
