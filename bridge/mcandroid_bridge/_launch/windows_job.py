"""Own launched Windows processes and their future descendants in one job.

The caller must assign a process before allowing it to launch children (for
example with a stdin gate). Adding an already running process cannot capture
children it created earlier. This module never attaches the current controller.

API layout and behavior:
https://learn.microsoft.com/windows/win32/api/jobapi2/nf-jobapi2-setinformationjobobject
https://learn.microsoft.com/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information
https://learn.microsoft.com/windows/win32/api/winnt/ns-winnt-jobobject_basic_limit_information
"""
from __future__ import annotations

import ctypes
import os
import subprocess


_DWORD = ctypes.c_uint32
_HANDLE = ctypes.c_void_p
_SIZE_T = ctypes.c_size_t
_KILL_ON_JOB_CLOSE = 0x00002000
_EXTENDED_LIMIT_INFORMATION = 9


class _BasicLimitInformation(ctypes.Structure):
    _fields_ = [
        ("PerProcessUserTimeLimit", ctypes.c_int64),
        ("PerJobUserTimeLimit", ctypes.c_int64),
        ("LimitFlags", _DWORD),
        ("MinimumWorkingSetSize", _SIZE_T),
        ("MaximumWorkingSetSize", _SIZE_T),
        ("ActiveProcessLimit", _DWORD),
        ("Affinity", _SIZE_T),
        ("PriorityClass", _DWORD),
        ("SchedulingClass", _DWORD),
    ]


class _IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_uint64) for name in (
        "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
        "ReadTransferCount", "WriteTransferCount", "OtherTransferCount",
    )]


class _ExtendedLimitInformation(ctypes.Structure):
    _fields_ = [
        ("BasicLimitInformation", _BasicLimitInformation),
        ("IoInfo", _IoCounters),
        ("ProcessMemoryLimit", _SIZE_T),
        ("JobMemoryLimit", _SIZE_T),
        ("PeakProcessMemoryUsed", _SIZE_T),
        ("PeakJobMemoryUsed", _SIZE_T),
    ]


def _load_kernel32():
    if os.name != "nt":
        raise OSError("WindowsJob requires Windows; process cleanup is unavailable on this platform.")
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateJobObjectW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p]
    kernel32.CreateJobObjectW.restype = _HANDLE
    kernel32.SetInformationJobObject.argtypes = [_HANDLE, ctypes.c_int, ctypes.c_void_p, _DWORD]
    kernel32.SetInformationJobObject.restype = ctypes.c_int
    kernel32.AssignProcessToJobObject.argtypes = [_HANDLE, _HANDLE]
    kernel32.AssignProcessToJobObject.restype = ctypes.c_int
    kernel32.GetProcessId.argtypes = [_HANDLE]
    kernel32.GetProcessId.restype = _DWORD
    kernel32.CloseHandle.argtypes = [_HANDLE]
    kernel32.CloseHandle.restype = ctypes.c_int
    return kernel32


def _error(action, code=None):
    code = ctypes.get_last_error() if code is None else code
    return OSError(code, f"{action} failed (Windows error {code}); cannot guarantee owned-process cleanup.")


class WindowsJob:
    """Unnamed, non-inheritable KILL_ON_JOB_CLOSE job for owned Popen objects."""

    def __init__(self):
        self._handle = None
        self._kernel32 = _load_kernel32()
        # NULL security attributes make this handle non-inheritable. An unnamed
        # job cannot accidentally open a job owned by a different controller.
        handle = self._kernel32.CreateJobObjectW(None, None)
        if not handle:
            raise _error("CreateJobObjectW")
        limits = _ExtendedLimitInformation()
        limits.BasicLimitInformation.LimitFlags = _KILL_ON_JOB_CLOSE
        if not self._kernel32.SetInformationJobObject(
                handle, _EXTENDED_LIMIT_INFORMATION, ctypes.byref(limits), ctypes.sizeof(limits)):
            error = _error("SetInformationJobObject")
            self._kernel32.CloseHandle(handle)
            raise error
        self._handle = handle

    def add(self, process):
        """Assign a launched Popen process; failure is explicit with no fallback."""
        if self._handle is None:
            raise RuntimeError("Cannot add a process to a closed WindowsJob.")
        if not isinstance(process, subprocess.Popen):
            raise TypeError("WindowsJob.add requires an owned subprocess.Popen object.")
        if process.pid in (os.getpid(), os.getppid()):
            raise ValueError("Refusing to assign the test controller or its parent to the job.")
        handle = getattr(process, "_handle", None)
        if handle is None:
            raise ValueError("Popen object has no Windows process handle.")
        actual_pid = self._kernel32.GetProcessId(int(handle))
        if not actual_pid:
            raise _error("GetProcessId")
        if actual_pid != process.pid or actual_pid in (os.getpid(), os.getppid()):
            raise ValueError("Popen process handle does not identify the owned child.")
        if not self._kernel32.AssignProcessToJobObject(self._handle, int(handle)):
            raise _error("AssignProcessToJobObject")

    def close(self):
        """Kill all assigned processes/descendants, even after a launcher exits."""
        if self._handle is not None:
            if not self._kernel32.CloseHandle(self._handle):
                raise _error("CloseHandle for WindowsJob")
            self._handle = None

    def __enter__(self):
        if self._handle is None:
            raise RuntimeError("Cannot enter a closed WindowsJob.")
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def __del__(self):
        try:
            self.close()
        except Exception:
            # Explicit close reports errors. Destructors cannot safely do so.
            pass
