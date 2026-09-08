"""Own a POSIX process group behind a lifetime pipe; EOF also handles supervisor SIGKILL.

The child is created only after G arrives. It and its descendants share a new
session, so cleanup never signals the parent, a reused name, or an unrelated VM.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time


def signal_owned_group(pid, signum):
    try:
        os.killpg(pid, signum)
    except ProcessLookupError:
        return
    except PermissionError:
        # XNU excludes zombies from killpg's iterator and reports EPERM when
        # only the unreaped leader remains. Do not swallow real permission failures.
        if sys.platform != 'darwin': raise
        status = os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        if status is None or status.si_pid != pid: raise
        result = subprocess.run(['/bin/ps', '-axo', 'pgid=,stat='], capture_output=True,
                                text=True, timeout=3, check=True)
        members = [row.split()[1] for row in result.stdout.splitlines()
                   if len(row.split()) == 2 and row.split()[0] == str(pid)]
        if any(not state.startswith('Z') for state in members): raise


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
        try:
            signal_owned_group(child.pid, signal.SIGTERM)
            time.sleep(.25)
            signal_owned_group(child.pid, signal.SIGKILL)
        finally:
            child.wait(timeout=3)
    return child.returncode if child.returncode >= 0 else 0


if __name__ == '__main__': raise SystemExit(main())
