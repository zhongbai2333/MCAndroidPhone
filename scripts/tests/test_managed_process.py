"""PID signaling tests use bounded Python children, never QEMU or Minecraft."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[2] / "bridge/mcandroid_bridge/_launch/gate.py"
SPEC = importlib.util.spec_from_file_location("managed_process_gate", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)
sys.path.insert(0, str(SCRIPT.parent))
from windows_job import WindowsJob


class ManagedProcessTests(unittest.TestCase):
    def test_closed_gate_never_spawns_or_publishes_a_pid(self):
        with mock.patch.object(gate.sys, "stdin", mock.Mock(buffer=io.BytesIO(b""))), \
             mock.patch.object(gate.subprocess, "Popen") as popen:
            self.assertEqual(125, gate.main(["--pid-file", "unused.json", "--", "unused.exe"]))
            popen.assert_not_called()

    def test_record_publish_failure_terminates_only_its_child(self):
        child = mock.MagicMock(pid=42002)
        child.__enter__.return_value = child
        with tempfile.TemporaryDirectory() as temporary, \
             mock.patch.object(gate.sys, "stdin", mock.Mock(buffer=io.BytesIO(b"G"))), \
             mock.patch.object(gate.subprocess, "Popen", return_value=child), \
             mock.patch.object(gate.os, "replace", side_effect=OSError("atomic publish failed")):
            with self.assertRaisesRegex(OSError, "atomic publish failed"):
                gate.main(["--pid-file", str(Path(temporary) / "state.json"), "--", "unused.exe"])
        child.terminate.assert_called_once_with()
        child.wait.assert_called_once_with(timeout=5)

    @unittest.skipUnless(os.name == "nt", "Real child belongs to a Windows job")
    def test_atomic_record_reports_actual_python_child_not_wrapper(self):
        with tempfile.TemporaryDirectory(prefix="phone-gate-pid-") as temporary, WindowsJob() as job:
            record, evidence = Path(temporary) / "record.json", Path(temporary) / "child.txt"
            code = "import os,sys,time; from pathlib import Path; Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(.05)"
            # Match Session.start: a venv redirector is not the native process.
            native_python = getattr(sys, "_base_executable", None) or sys.executable
            process = subprocess.Popen([native_python, str(SCRIPT), "--pid-file", str(record), "--",
                                        native_python, "-c", code, str(evidence)],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                job.add(process)
                _stdout, stderr = process.communicate(b"G", timeout=5)
                self.assertEqual(0, process.returncode, stderr.decode(errors="replace"))
                data = json.loads(record.read_text(encoding="utf-8"))
                self.assertEqual(process.pid, data["wrapperPid"])
                self.assertEqual(int(evidence.read_text()), data["childPid"])
                self.assertNotEqual(process.pid, data["childPid"])
                self.assertFalse(record.with_name(record.name + ".tmp").exists())
            finally:
                job.close()
                process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
