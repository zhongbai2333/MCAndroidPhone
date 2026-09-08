"""Windows job tests, including an isolated, bounded real descendant check."""
import ctypes
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location("windows_job", Path(__file__).parents[2] / "bridge/mcandroid_bridge/_launch/windows_job.py")
jobs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(jobs)


class WindowsJobUnitTests(unittest.TestCase):
    def fake_kernel(self):
        kernel = Mock()
        kernel.CreateJobObjectW.return_value = 123
        kernel.SetInformationJobObject.return_value = 1
        kernel.CloseHandle.return_value = 1
        return kernel

    def test_close_is_idempotent_and_never_assigns_controller(self):
        kernel = self.fake_kernel()
        with patch.object(jobs, "_load_kernel32", return_value=kernel):
            with jobs.WindowsJob() as job:
                pass
            job.close()
        kernel.CreateJobObjectW.assert_called_once_with(None, None)
        kernel.AssignProcessToJobObject.assert_not_called()
        kernel.CloseHandle.assert_called_once_with(123)
        with self.assertRaisesRegex(RuntimeError, "closed"):
            job.add(object())

    def test_setup_failure_closes_handle_and_raises(self):
        kernel = self.fake_kernel()
        kernel.SetInformationJobObject.return_value = 0
        with patch.object(jobs, "_load_kernel32", return_value=kernel), patch.object(jobs, "_error", return_value=OSError("set limits failed")):
            with self.assertRaisesRegex(OSError, "set limits failed"):
                jobs.WindowsJob()
        kernel.CloseHandle.assert_called_once_with(123)

    def test_non_popen_is_rejected_before_assignment(self):
        kernel = self.fake_kernel()
        with patch.object(jobs, "_load_kernel32", return_value=kernel), jobs.WindowsJob() as job:
            with self.assertRaises(TypeError):
                job.add(object())
        kernel.AssignProcessToJobObject.assert_not_called()

    def test_non_windows_is_explicitly_unsupported(self):
        with patch.object(jobs.os, "name", "posix"), self.assertRaisesRegex(OSError, "requires Windows"):
            jobs.WindowsJob()


@unittest.skipUnless(os.name == "nt", "Windows kernel job integration")
class WindowsJobIntegrationTests(unittest.TestCase):
    def test_job_kills_grandchild_after_direct_child_exits(self):
        # A stdin gate guarantees assignment precedes the grandchild spawn.
        # The grandchild also self-expires, bounding leakage even if cleanup fails.
        launcher_code = """
import os, subprocess, sys
from pathlib import Path
if sys.stdin.buffer.read(1) != b'G':
    raise SystemExit(1)
child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(20)'],
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
Path(sys.argv[1]).write_text(str(child.pid), encoding='ascii')
os._exit(0)
"""
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32]
        kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        kernel.WaitForSingleObject.restype = ctypes.c_uint32
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle.restype = ctypes.c_int
        job = jobs.WindowsJob()
        direct_child = None
        grandchild_handle = None
        try:
            with tempfile.TemporaryDirectory() as directory:
                pid_file = Path(directory) / "grandchild.pid"
                direct_child = subprocess.Popen([sys.executable, "-c", launcher_code, str(pid_file)],
                                                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                                stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
                job.add(direct_child)
                direct_child.stdin.write(b"G")
                direct_child.stdin.flush()
                direct_child.stdin.close()
                self.assertEqual(direct_child.wait(timeout=5), 0)
                grandchild_pid = int(pid_file.read_text(encoding="ascii"))
                self.assertNotIn(grandchild_pid, (os.getpid(), os.getppid(), direct_child.pid))
                grandchild_handle = kernel.OpenProcess(0x00100000, 0, grandchild_pid)  # SYNCHRONIZE only.
                self.assertTrue(grandchild_handle, "Cannot open the test grandchild for waiting.")
                self.assertEqual(kernel.WaitForSingleObject(grandchild_handle, 0), 0x102,
                                 "Grandchild should still be running after direct child exits.")
                job.close()
                self.assertEqual(kernel.WaitForSingleObject(grandchild_handle, 5000), 0,
                                 "Job close must terminate the surviving grandchild.")
        finally:
            job.close()
            if grandchild_handle:
                kernel.CloseHandle(grandchild_handle)
            if direct_child is not None:
                if direct_child.poll() is None:
                    # Only this directly owned test Popen handle is eligible.
                    direct_child.kill()
                    direct_child.wait(timeout=5)
                if direct_child.stdin and not direct_child.stdin.closed:
                    direct_child.stdin.close()


if __name__ == "__main__":
    unittest.main()
