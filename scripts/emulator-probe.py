#!/usr/bin/env python3
"""Wait for a local emulator's native RGB display and print launch dimensions.

Screenshot readiness does not guarantee that Android has finished booting. This
helper only reads emulator discovery and screenshots; it never authorizes ADB.
"""
from __future__ import annotations

import argparse
import configparser
import json
import math
from pathlib import Path
import sys
import time


class ProbeError(RuntimeError):
    """A safe diagnostic that contains neither credentials nor RPC details."""


def read_discovery(discovery_file, token_reader):
    """Return the validated local gRPC port and bridge-validated Bearer token."""
    try:
        content = Path(discovery_file).read_text(encoding="utf-8")
        config = configparser.ConfigParser(interpolation=None)
        config.read_string("[emulator]\n" + content)
        port_text = config["emulator"].get("grpc.port", "").strip()
    except (OSError, UnicodeError, configparser.Error):
        raise ProbeError("Cannot read the emulator discovery INI.") from None
    if not port_text.isascii() or not port_text.isdecimal() or len(port_text) > 5:
        raise ProbeError("Discovery INI must contain a numeric grpc.port in 1..65535.")
    port = int(port_text)
    if not 1 <= port <= 65535:
        raise ProbeError("Discovery INI grpc.port must be in 1..65535.")
    try:
        token = token_reader(discovery_file=str(discovery_file))
    except (OSError, UnicodeError, ValueError, configparser.Error):
        raise ProbeError("Discovery INI has no usable Bearer token; restart the emulator with token authentication.") from None
    return port, token


def validate_snapshot(snapshot):
    """Return native even dimensions, or None when the display is inactive."""
    if not snapshot.image:
        return None
    width = snapshot.format.width or snapshot.width
    height = snapshot.format.height or snapshot.height
    if not (2 <= width <= 4096 and 2 <= height <= 4096):
        raise ProbeError("Emulator display dimensions must each be in 2..4096.")
    if width % 2 or height % 2:
        raise ProbeError("Emulator display width and height must both be even for NV12.")
    if snapshot.format.format != 2 or len(snapshot.image) != width * height * 3:
        raise ProbeError("Emulator did not return a complete, tightly packed RGB888 screenshot.")
    return width, height


def wait_for_snapshot(controller, request, metadata, grpc_port, timeout, *,
                      rpc_errors=(), clock=time.monotonic, sleep=time.sleep):
    """Retry inactive displays/transient RPC failures within a monotonic deadline."""
    if not math.isfinite(timeout) or timeout <= 0:
        raise ProbeError("Timeout must be a positive finite number of seconds.")
    deadline = clock() + timeout
    last_reason = "the emulator display is inactive"
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            raise ProbeError("Timed out waiting for emulator screenshot readiness: " + last_reason + ".")
        try:
            snapshot = controller.getScreenshot(request, timeout=min(5.0, remaining), metadata=metadata)
        except rpc_errors:
            # Do not stringify gRPC exceptions: they may include sensitive metadata.
            last_reason = "the local screenshot RPC is unavailable or authentication failed"
        else:
            dimensions = validate_snapshot(snapshot)
            if dimensions is not None:
                return {"width": dimensions[0], "height": dimensions[1], "grpcPort": grpc_port}
            last_reason = "the emulator display is inactive; wake or unlock it"
        remaining = deadline - clock()
        if remaining > 0:
            sleep(min(0.5, remaining))


def run_probe(project_root, discovery_file, timeout):
    bridge_path = Path(project_root).resolve() / "bridge"
    if not (bridge_path / "mcandroid_bridge" / "emulator_wire.py").is_file():
        raise ProbeError("Project root does not contain the emulator bridge.")
    sys.path.insert(0, str(bridge_path))
    try:
        import grpc
        from mcandroid_bridge.emulator import read_token
        from mcandroid_bridge import emulator_wire as wire
    except ImportError:
        raise ProbeError("Install bridge/requirements.txt in the Python environment used by this script.") from None
    grpc_port, token = read_discovery(discovery_file, read_token)
    # Discovery selects only a port. It can never redirect this probe off-host.
    channel = grpc.insecure_channel(f"127.0.0.1:{grpc_port}", options=[
        ("grpc.max_receive_message_length", 4096 * 4096 * 3 + 1024 * 1024),
        ("grpc.max_send_message_length", 64 * 1024),
    ])
    try:
        controller = wire.Controller(channel)
        # Omitted (zero) width and height request the actual native resolution.
        return wait_for_snapshot(controller, wire.ImageFormat(format=2),
                                 (("authorization", "Bearer " + token),), grpc_port,
                                 timeout, rpc_errors=(grpc.RpcError,))
    finally:
        channel.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--discovery-file", required=True, type=Path)
    parser.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args(argv)
    try:
        if not math.isfinite(args.timeout) or args.timeout <= 0:
            raise ProbeError("Timeout must be a positive finite number of seconds.")
        result = run_probe(args.project_root, args.discovery_file, args.timeout)
    except ProbeError as exc:
        print("Emulator probe: " + str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Emulator probe: cancelled.", file=sys.stderr)
        return 130
    except Exception:
        # Unexpected dependency/runtime failures are also sanitized, with no traceback.
        print("Emulator probe: failed while reading the local emulator display.", file=sys.stderr)
        return 1
    print(json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
