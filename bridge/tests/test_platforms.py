import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from mcandroid_bridge._launch.platforms import Host, architecture
from mcandroid_bridge._launch.qemu_runtime import qemu_command


class PlatformTests(unittest.TestCase):
    def test_all_six_native_accelerators_and_targets(self):
        for system,accel in [('Windows','whpx'),('Darwin','hvf'),('Linux','kvm')]:
            for arch in ('amd64','arm64'):
                with self.subTest(system=system,arch=arch),patch('os.access',return_value=True):
                    host=Host(system,arch)
                    self.assertEqual(accel,host.accelerator(arch))
                    self.assertEqual('tcg',host.accelerator('amd64' if arch=='arm64' else 'arm64'))
                    self.assertIn('aarch64' if arch=='arm64' else 'x86_64',host.qemu_name(arch))
                    with self.assertRaises(ValueError):host.accelerator('amd64' if arch=='arm64' else 'arm64',accel)

    def test_arm_configuration_never_reuses_pc_devices_or_boot_line(self):
        host=Host('Darwin','arm64')
        with self.assertRaises(ValueError):qemu_command('qemu',5900,6000,'.',guest_arch='arm64',host=host,gpu='virtio',kernel='Image')
        cmd=qemu_command('qemu',5900,6000,'.',guest_arch='arm64',host=host,gpu='virtio',
                         kernel='Image',kernel_append='console=ttyAMA0 root=/dev/vda',disk='phone.qcow2')
        self.assertIn('virt,gic-version=3,accel=hvf',cmd)
        self.assertIn('host',cmd)
        self.assertTrue(any(v.startswith('virtio-gpu-pci,') for v in cmd))
        self.assertIn('virtio-net-pci,netdev=net0',cmd)
        self.assertFalse(any('if=ide' in v or 'Nehalem' in v for v in cmd))
        self.assertEqual('console=ttyAMA0 root=/dev/vda',cmd[cmd.index('-append')+1])

    def test_arm_uefi_is_read_only_and_disk_is_snapshot(self):
        cmd=qemu_command('qemu',5900,6000,'.',guest_arch='arm64',host=Host('Windows','arm64'),
                         gpu='virtio',firmware='firmware.fd',disk='space,comma.qcow2')
        self.assertIn('-snapshot',cmd)
        self.assertTrue(any('pflash' in v and 'readonly=on' in v for v in cmd))
        self.assertTrue(any('space,,comma.qcow2' in v for v in cmd))

    def test_platform_validation(self):
        self.assertEqual('arm64',architecture('aarch64'))
        self.assertEqual('amd64',architecture('AMD64'))
        with self.assertRaises(ValueError):architecture('sparc')
        with self.assertRaises(ValueError):Host('Darwin','arm64').accelerator('arm64','whpx')
        with patch('os.access',return_value=False):self.assertEqual('tcg',Host('Linux','amd64').accelerator('amd64'))


@unittest.skipIf(os.name=='nt','POSIX process-group lifecycle')
class PosixGateTests(unittest.TestCase):
    def test_lease_closure_cleans_child_and_grandchild(self):
        with tempfile.TemporaryDirectory() as tmp:
            record=Path(tmp)/'pid.json';grand=Path(tmp)/'grand.json'
            code='import subprocess,sys,time,json; from pathlib import Path; p=subprocess.Popen([sys.executable,"-c","import time;time.sleep(60)"]); Path(sys.argv[1]).write_text(json.dumps({"pid":p.pid}));time.sleep(60)'
            gate=subprocess.Popen([sys.executable,'-m','mcandroid_bridge._launch.posix_gate','--pid-file',str(record),'--',sys.executable,'-c',code,str(grand)],stdin=subprocess.PIPE)
            try:
                gate.stdin.write(b'G');gate.stdin.flush()
                end=time.monotonic()+8
                while not record.exists() or not grand.exists():
                    self.assertIsNone(gate.poll());self.assertLess(time.monotonic(),end);time.sleep(.02)
                ids=[json.loads(record.read_text())['childPid'],json.loads(grand.read_text())['pid']]
                gate.stdin.close();self.assertEqual(0,gate.wait(timeout=8))
                for pid in ids:
                    # Orphan zombies can await init reaping briefly in containers; they are not running.
                    status=Path(f'/proc/{pid}/stat')
                    if status.exists():self.assertEqual('Z',status.read_text().split()[2])
                    else:
                        with self.assertRaises(ProcessLookupError):os.kill(pid,0)
            finally:
                if not gate.stdin.closed:gate.stdin.close()
                if gate.poll() is None:gate.terminate();gate.wait(timeout=8)

    def test_closed_gate_never_launches(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=subprocess.run([sys.executable,'-m','mcandroid_bridge._launch.posix_gate','--pid-file',tmp+'/pid.json','--',sys.executable,'-c','raise Exception("must not launch")'],input=b'',timeout=5)
            self.assertEqual(125,p.returncode);self.assertFalse(Path(tmp,'pid.json').exists())
