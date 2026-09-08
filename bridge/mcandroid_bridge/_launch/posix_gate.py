"""Own a POSIX process group behind a lifetime pipe; EOF also handles supervisor SIGKILL.

The child is created only after G arrives. It and its descendants share a new
session, so cleanup never signals the parent, a reused name, or an unrelated VM.
"""
import argparse
import contextlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pid-file', required=True, type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.command or args.command[0] != '--': raise ValueError('Expected -- executable arguments')
    if not hasattr(os, "waitid"):
        raise RuntimeError("POSIX process ownership requires waitid; on macOS install Python 3.13 or newer")
    if os.read(sys.stdin.fileno(), 1) != b'G': return 125
    stopped = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, lambda *_: stopped.set())
    def lease():
        try:
            while os.read(sys.stdin.fileno(), 1): pass
        finally: stopped.set()
    threading.Thread(target=lease, daemon=True).start()
    child = subprocess.Popen(args.command[1:], stdin=subprocess.DEVNULL, start_new_session=True)
    try:
        temporary = args.pid_file.with_suffix('.tmp')
        with temporary.open('x', encoding='utf-8') as output:
            json.dump({'wrapperPid': os.getpid(), 'childPid': child.pid}, output)
        os.replace(temporary, args.pid_file)
        # Do not reap the leader before group cleanup: its reserved PID prevents
        # reuse while we still need to address surviving grandchildren.
        while not stopped.wait(.05):
            status = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            if status is not None: break
    finally:
        with contextlib.suppress(ProcessLookupError): os.killpg(child.pid, signal.SIGTERM)
        time.sleep(.25)
        with contextlib.suppress(ProcessLookupError): os.killpg(child.pid, signal.SIGKILL)
        child.wait(timeout=3)
    return child.returncode if child.returncode >= 0 else 0


if __name__ == '__main__': raise SystemExit(main())
