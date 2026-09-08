"""Internal gate: parent assigns this process to its Windows job before permitting a child."""
import os
import json
from pathlib import Path
import subprocess
import sys


def main(arguments=None):
    arguments = list(sys.argv[1:] if arguments is None else arguments)
    pid_file = None
    if len(arguments) >= 2 and arguments[0] == "--pid-file":
        pid_file = Path(arguments[1])
        arguments = arguments[2:]
    if len(arguments) < 2 or arguments[0] != "--":
        raise SystemExit("Internal helper: expected -- command arguments")
    if sys.stdin.buffer.read(1) != b"G":
        return 125
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    # argv is always passed as an argument list; callers launch executables/Java, never command strings.
    with subprocess.Popen(arguments[1:], stdin=subprocess.DEVNULL, creationflags=flags) as child:
        if pid_file is not None:
            try:
                # Publish only complete records. The parent chooses a unique session path
                # and verifies the wrapper PID before using the child's native PID.
                temporary = pid_file.with_name(pid_file.name + ".tmp")
                with temporary.open("x", encoding="utf-8") as output:
                    json.dump({"wrapperPid": os.getpid(), "childPid": child.pid}, output)
                os.replace(temporary, pid_file)
            except BaseException:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
                raise
        return child.wait()


if __name__ == "__main__":
    raise SystemExit(main())
