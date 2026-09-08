"""Host/guest selection. OS, CPU architecture and GPU transport are separate capabilities."""
from dataclasses import dataclass
import os
import platform
import shutil
import subprocess


def architecture(value):
    value = value.lower()
    if value in ('amd64', 'x86_64', 'x64'): return 'amd64'
    if value in ('arm64', 'aarch64'): return 'arm64'
    raise ValueError(f'Unsupported CPU architecture: {value}')


@dataclass(frozen=True)
class Host:
    system: str
    arch: str

    @classmethod
    def current(cls):
        system = platform.system()
        if system not in ('Windows', 'Linux', 'Darwin'):
            raise ValueError(f'Unsupported OS: {system}')
        machine = os.environ.get('PROCESSOR_ARCHITEW6432') or platform.machine()
        # A translated x86 Python on Apple silicon must not select an x86 guest by accident.
        if system == 'Darwin':
            try:
                r = subprocess.run(['/usr/sbin/sysctl', '-n', 'hw.optional.arm64'],
                                   capture_output=True, text=True, timeout=3)
                if r.returncode == 0 and r.stdout.strip() == '1': machine = 'arm64'
            except (OSError, subprocess.TimeoutExpired): pass
        return cls(system, architecture(machine))

    @property
    def tag(self): return {'Windows': 'windows', 'Darwin': 'macos', 'Linux': 'linux'}[self.system] + '-' + self.arch

    def accelerator(self, guest, requested='auto'):
        guest = architecture(guest)
        native = {'Windows': 'whpx', 'Darwin': 'hvf', 'Linux': 'kvm'}[self.system]
        if requested == 'auto':
            if guest != self.arch: return 'tcg'
            if native == 'kvm' and not os.access('/dev/kvm', os.R_OK | os.W_OK): return 'tcg'
            return native
        if requested not in ('tcg', native):
            raise ValueError(f'{requested} is not an accelerator for {self.system}')
        if guest != self.arch and requested != 'tcg':
            raise ValueError('Cross-architecture guests require TCG; use a matching Android image for acceleration')
        return requested

    def qemu_name(self, guest):
        target = 'aarch64' if architecture(guest) == 'arm64' else 'x86_64'
        return f'qemu-system-{target}' + ('.exe' if self.system == 'Windows' else '')


def probe(command):
    try:
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True,
                                errors='replace', timeout=8,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        return {'exitCode': result.returncode, 'output': (result.stdout + result.stderr)[-16000:]}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'exitCode': None, 'error': str(error)}


def capabilities():
    host = Host.current()
    result = {'host': host.tag, 'kvmAccessible': os.access('/dev/kvm', os.R_OK | os.W_OK),
              'wsl': host.system == 'Linux' and 'microsoft' in platform.release().lower(),
              'gpuTransport': 'd3d11' if host.system == 'Windows' else 'cpu',
              'zeroCpuCopyValidated': False, 'qemu': {}}
    for guest in ('amd64', 'arm64'):
        name = host.qemu_name(guest)
        executable = shutil.which(name)
        result['qemu'][guest] = {'path': executable}
        if executable:
            result['qemu'][guest].update({key: probe([executable, *args]) for key, args in
                                         {'version':['--version'], 'accelerators':['-accel','help'],
                                          'displays':['-display','help'], 'devices':['-device','help']}.items()})
    if host.system == 'Linux':
        result['gl'] = probe(['glxinfo', '-B'])
        result['egl'] = probe(['eglinfo', '-B'])
    return result
