"""Read protocol frames safely and optionally exercise navigation on a live bridge."""
import argparse
import base64
import hashlib
import json
import mmap
from pathlib import Path
import socket
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--frames", type=int, default=10)
    parser.add_argument("--exercise-input", action="store_true", help="Send HOME, APP_SWITCH, BACK and a short touch gesture")
    parser.add_argument("--output", type=Path, help="Save last owned NV12 frame and JSON metadata")
    args = parser.parse_args()
    config = dict(line.split("=", 1) for line in (args.runtime / "bridge.properties").read_text(encoding="ascii").splitlines() if "=" in line)
    if config.get("transport") == "d3d11":
        from .gpu import validate_scanout
        with socket.create_connection((config["host"], int(config["port"])), timeout=180) as peer:
            stream = peer.makefile("rb")
            try:
                peer.sendall(f"HELLO\t2\t{config['token']}\n".encode("ascii"))
                welcome = stream.readline(8193).decode("ascii").strip().split("\t")
                if len(welcome)!=4 or welcome[:2]!=["WELCOME","2"] or welcome[3]!="D3D11":
                    raise RuntimeError("GPU handshake failed")
                for index in range(args.frames):
                    fields = stream.readline(8193).decode("ascii").strip().split("\t")
                    if len(fields)!=11 or fields[0]!="GPUFRAME": raise RuntimeError("No GPU frame")
                    sequence,handle,tw,th,top,x,y,w,h,timestamp = map(int,fields[1:])
                    validate_scanout((handle,tw,th,bool(top),x,y,w,h))
                    peer.sendall(f"GPUACK\t{sequence}\n".encode("ascii"))
                print(json.dumps({"width":w,"height":h,"transport":"D3D11","frames":args.frames,
                                  "validation":"descriptor only; use GPU/MC smoke for actual import"}))
            finally: stream.close()
        return 0
    hashes = []
    started = time.monotonic()
    last = None
    sock = socket.create_connection((config["host"], int(config["port"])), timeout=15)
    stream = sock.makefile("rb")
    try:
        sock.sendall(f"HELLO\t1\t{config['token']}\n".encode("ascii"))
        welcome = stream.readline(8193).decode("ascii").strip().split("\t")
        if len(welcome) != 8 or welcome[:2] != ["WELCOME", "1"] or welcome[7] != "NV12":
            raise RuntimeError("bridge rejected handshake: " + welcome[0])
        width, height, size, count = map(int, welcome[2:6])
        if size != width * height * 3 // 2 or count != 3:
            raise RuntimeError("invalid frame geometry")
        path = Path(base64.b64decode(welcome[6]).decode("utf-8"))
        with path.open("rb") as handle, mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ) as mapping:
            for index in range(args.frames):
                line = stream.readline(8193).decode("ascii").strip().split("\t")
                if len(line) != 4 or line[0] != "FRAME":
                    raise RuntimeError("unexpected frame response: " + line[0])
                seq, slot, timestamp = map(int, line[1:])
                if not 0 <= slot < count:
                    raise RuntimeError("invalid frame slot")
                last = bytes(mapping[slot * size:(slot + 1) * size])
                sock.sendall(f"ACK\t{seq}\t{slot}\n".encode("ascii"))
                hashes.append(hashlib.sha256(last).hexdigest())
                if args.exercise_input:
                    if index == 0:
                        sock.sendall(b"KEY\tHOME\n")
                    elif index == 2:
                        sock.sendall(b"KEY\tAPP_SWITCH\n")
                    elif index == 5:
                        sock.sendall(b"KEY\tBACK\n")
                    elif index == 7:
                        sock.sendall(b"TOUCH\tDOWN\t0.5\t0.5\nTOUCH\tMOVE\t0.5\t0.45\nTOUCH\tUP\t0.5\t0.45\n")
        report = {"width": width, "height": height, "pixel_format": "NV12", "color_matrix": "BT.709 limited",
                  "frames": len(hashes), "distinct_frames": len(set(hashes)), "elapsed_seconds": round(time.monotonic() - started, 3),
                  "input_exercised": args.exercise_input, "last_sha256": hashes[-1] if hashes else None}
        if args.output and last is not None:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(last)
            args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
    finally:
        stream.close()
        sock.close()


if __name__ == "__main__":
    main()
