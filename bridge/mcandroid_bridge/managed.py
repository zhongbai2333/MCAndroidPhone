"""JAR-owned runtime supervisor. stdin EOF is the JVM lifetime lease.

Loaded from the bridge ZIP inside the mod, never from the user's scripts folder.
Native executables and Android media remain user-provided external files.
"""
import argparse
import contextlib
import json
import os
import re
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from ._launch.platforms import Host, architecture, probe
from ._launch.windows_job import WindowsJob
from ._launch.qemu_runtime import qemu_command, wait_qmp, QmpClient

def properties(path):
    # Java writes an intentionally simple UTF-8 request (no escapes/continuations).
    result={}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            key,separator,value=line.partition("=")
            if not separator: raise ValueError("Invalid runtime property")
            result[key.strip()]=value.strip()
    return result

def executable(value,candidates,name):
    if value:
        path=Path(value).expanduser().resolve()
        if not path.is_file():raise ValueError(f"{name} does not exist: {path}")
        return path
    for path in candidates:
        if path.is_file():return path.resolve()
    found=shutil.which(name)
    if found:return Path(found).resolve()
    raise ValueError(f"Missing {name}; set its path in config/mcandroidphone-runtime.properties")

def port():
    with socket.socket() as peer:
        peer.bind(("127.0.0.1",0));return peer.getsockname()[1]

class Session:
    def __init__(self,path,stop):
        self.path=path;self.stop=stop;self.children=[];self.records={};self.job=WindowsJob() if os.name == 'nt' else None
        self.qemu=None;self.qmp=None;self.vm_uuid=None;self.env=os.environ.copy()
        self.phase="starting";self.error=None
    def save(self):
        data={"state":self.phase,"error":self.error,"supervisorPid":os.getpid(),
              "processes":[{"name":name,"pid":p.pid,"childPid":self.records.get(p.pid),"exitCode":p.poll()}
                           for name,p in self.children]}
        temporary=self.path/"session.json.tmp"
        temporary.write_text(json.dumps(data,indent=2),encoding="utf-8")
        os.replace(temporary,self.path/"session.json")
    def check(self):
        if self.stop.is_set():raise InterruptedError("Minecraft closed the runtime lease")
        for name,p in self.children:
            if p.poll() is not None:raise RuntimeError(f"{name} exited ({p.returncode}); see {name}.log")
        for log in self.path.glob("*.log"):
            if log.stat().st_size>64*1024*1024:raise RuntimeError(f"Runtime log limit exceeded: {log.name}")
    def start(self,name,command):
        self.check()
        record=self.path/(name+".process.json")
        native=getattr(sys,"_base_executable",None) or sys.executable
        args=[native,"-u","-m",("mcandroid_bridge._launch.gate" if os.name == "nt" else "mcandroid_bridge._launch.posix_gate"),"--pid-file",str(record),"--",*map(str,command)]
        with (self.path/(name+".log")).open("wb") as output:
            p=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,
                               cwd=self.path,env=self.env,creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.children.append((name,p))
        try:
            if self.job is not None:self.job.add(p)
            p.stdin.write(b"G");p.stdin.flush()
            if self.job is not None:p.stdin.close()
        except BaseException:
            p.kill();p.wait(timeout=5);raise
        deadline=time.monotonic()+10
        while not record.is_file():
            self.check()
            if time.monotonic()>deadline:raise TimeoutError(f"No process identity for {name}")
            self.stop.wait(.02)
        data=json.loads(record.read_text(encoding="utf-8"))
        child=data.get("childPid")
        if data.get("wrapperPid")!=p.pid or type(child) is not int or child<=0 or child==p.pid:
            raise RuntimeError("Invalid owned process identity")
        self.records[p.pid]=child;self.save()
        return p,child
    def close(self):
        if self.qemu is not None and self.qemu.poll() is None and self.qmp is not None:
            with contextlib.suppress(Exception):
                with QmpClient(self.qmp,timeout=1) as control:
                    if control.execute("query-uuid").get("UUID")==self.vm_uuid:control.execute("quit")
            with contextlib.suppress(subprocess.TimeoutExpired):self.qemu.wait(timeout=2)
        if self.job is not None:self.job.close()
        else:
            for _,p in self.children:
                if p.stdin is not None:
                    with contextlib.suppress(OSError):p.stdin.close()
        for name,p in reversed(self.children):
            try:p.wait(timeout=5)
            except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
        # Only volatile files allocated inside this unique JAR-owned session are removed.
        # Wait until every child has exited; the JVM releases its mmap before poweroff.
        directory=self.path/"bridge"
        if directory.is_dir() and not directory.is_symlink():
            for file in directory.iterdir():
                if file.is_symlink() or file.resolve().parent != directory.resolve():continue
                if file.name != "bridge.properties" and not re.fullmatch(r"frames-[0-9a-f]{32}\.nv12",file.name):continue
                for attempt in range(20):
                    try:file.unlink(missing_ok=True);break
                    except PermissionError:
                        if attempt==19:print(f"Transient file still in use: {file.name}",flush=True)
                        else:time.sleep(.1)
        if self.error is None:self.phase="stopped"
        self.save()

def launch(session,config):
    backend=config.get("backend","qemu")
    if backend not in ("pattern","qemu"):raise ValueError("backend must be qemu or pattern")
    host=Host.current()
    root=Path(config["root"]).resolve()
    guest=architecture(config.get('guestArch',host.arch))
    print(f'ANDROIDPHONE_HOST: {host.tag}; guest={guest}',flush=True)
    width=int(config.get("width","1080"));height=int(config.get("height","1920"))
    from .protocol import validate_dimensions
    validate_dimensions(width,height)
    native=getattr(sys,"_base_executable",None) or sys.executable
    bridge=[native,"-u","-m","mcandroid_bridge","--backend",backend,"--port","0",
            "--runtime",session.path/"bridge","--width",str(width),"--height",str(height),"--fps","30"]
    if backend=="qemu":
        qemu_name=host.qemu_name(guest)
        qemu=executable(config.get("qemu"),[root/('qemu/bin/'+qemu_name),root/('qemu/'+qemu_name)],qemu_name)
        gpu=config.get("gpu","virtio")
        if gpu not in ("virtio","virgl"):raise ValueError("gpu must be virtio or virgl")
        if gpu=='virgl' and host.system!='Windows':
            raise ValueError('GPU texture transport is not validated on this platform; set gpu=virtio for CPU display. Run native GPU probes before enabling DMA-BUF/IOSurface.')
        display=config.get('display','auto')
        if display=='auto':display='dbus' if host.system=='Windows' else 'vnc'
        if display not in ('vnc','dbus'):raise ValueError('display must be auto, dbus or vnc')
        if display=='dbus' and host.system!='Windows':raise ValueError('Use display=vnc until the Unix D-Bus frame transport is implemented')
        if gpu=='virgl' and display!='dbus':raise ValueError('virgl requires D-Bus')
        firmware=Path(config['firmware']).resolve() if config.get('firmware') else None
        bios=config.get('bios','false').lower()=='true'
        if bios and any(config.get(k) for k in ('iso','disk','kernel','initrd')):raise ValueError('bios cannot be combined with Android media')
        iso=None;disk=Path(config["disk"]).resolve() if config.get("disk") else None
        if not bios and (disk is None or config.get("iso")):
            candidates=list((root/"images").glob("*.iso"))
            iso=Path(config["iso"]).resolve() if config.get("iso") else candidates[0] if len(candidates)==1 else None
            if iso is None:raise ValueError("Set iso to the Android image path (no unique ISO in runtime/images)")
        for media in (iso,disk):
            if media is not None and not media.is_file():raise ValueError(f"Android media missing: {media}")
        prepared=iso.with_suffix("") if iso else None
        kernel=Path(config["kernel"]).resolve() if config.get("kernel") else prepared/"kernel" if guest=="amd64" and prepared and (prepared/"kernel").is_file() else None
        initrd=Path(config["initrd"]).resolve() if config.get("initrd") else prepared/"initrd.img" if kernel and prepared else None
        if guest=="amd64" and iso and disk is None and (kernel is None or initrd is None):
            raise ValueError("Prepare matching kernel/initrd.img beside the ISO for automatic Android boot")
        for boot in (kernel,initrd,firmware):
            if boot is not None and not boot.is_file():raise ValueError(f"Boot file missing: {boot}")
        ffmpeg=None
        if gpu=="virgl":
            angle=Path(config.get("angle",str(root/"angle/bin"))).resolve()
            if not all((angle/n).is_file() for n in ("libEGL.dll","libGLESv2.dll")):raise ValueError(f"ANGLE runtime missing: {angle}")
            session.env["PATH"]=str(angle)+os.pathsep+session.env.get("PATH","")
        else:
            ffname='ffmpeg.exe' if host.system=='Windows' else 'ffmpeg'
            ffmpeg=executable(config.get("ffmpeg"),[root/('ffmpeg/bin/'+ffname),root/('ffmpeg/'+ffname)],ffname)
        session.qmp=port();session.vm_uuid=str(uuid.uuid4())
        vnc=None
        if display=='vnc':
            for attempt in range(30):
                vnc=port()
                if vnc>=5900 and vnc!=session.qmp:break
            else:raise RuntimeError('Unable to allocate VNC port')
        devices=probe([qemu,'-device','help'])
        input_mode=config.get('input','auto')
        if input_mode=='auto':
            input_mode='touchscreen' if not bios and devices['exitCode']==0 and 'virtio-multitouch-pci' in devices.get('output','') else 'mouse'
        print(f'ANDROIDPHONE_DISPLAY: {display}; GPU={gpu}; input={input_mode}; requestedAccel={config.get("accel","auto")}',flush=True)
        command=qemu_command(qemu,vnc,session.qmp,session.path,iso=iso,disk=disk,
            disk_format=config.get("diskFormat","qcow2"),accel=config.get("accel","auto"),
            memory=int(config.get("memory","4096")),cpus=int(config.get("cpus","2")),kernel=kernel,initrd=initrd,
            width=width,height=height,density=int(config.get("density","480")),
            input_mode=input_mode,display_mode=display,gpu=gpu,host=host,guest_arch=guest,
            firmware=firmware,kernel_append=config.get('kernelAppend'))
        command.extend(("-uuid",session.vm_uuid))
        session.phase="starting-qemu";session.save()
        session.qemu,pid=session.start("qemu",command)
        wait_qmp(session.qmp,session.qemu,timeout=30,check=session.check)
        bridge.extend(("--qemu-pid",str(pid),"--qmp-port",str(session.qmp),"--qemu-input",input_mode,
                       "--qemu-display","d3d11" if gpu=="virgl" else display,"--qemu-color-order",config.get("colorOrder","rgb" if gpu=="virgl" or kernel is None or guest=="arm64" else "bgr")))
        if vnc:bridge.extend(("--vnc-port",str(vnc)))
        if ffmpeg:bridge.extend(("--ffmpeg",str(ffmpeg)))
    session.phase="starting-bridge";session.save()
    session.start("bridge",bridge)
    deadline=time.monotonic()+60
    while not (session.path/"bridge/bridge.properties").is_file():
        session.check()
        if time.monotonic()>deadline:raise TimeoutError("Bridge startup timed out")
        session.stop.wait(.1)
    # No probe client consumes the first GPU scanout; Minecraft connects directly.
    session.phase="ready";session.save()
    print("ANDROIDPHONE_RUNTIME_READY",flush=True)
    while not session.stop.wait(.25):session.check()

def main():
    parser=argparse.ArgumentParser();parser.add_argument("--config",required=True);parser.add_argument("--session",required=True)
    args=parser.parse_args()
    Host.current() # reject unsupported hosts before allocating processes
    if os.name != "nt" and not hasattr(os, "waitid"):
        raise RuntimeError("Install Python 3.13+ on macOS: safe process cleanup requires os.waitid")
    path=Path(args.session).resolve()
    if not path.is_dir():raise ValueError("Session directory must be allocated by the JAR")
    stop=threading.Event()
    if os.name!='nt':
        import signal
        for sig in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP):signal.signal(sig,lambda *_:stop.set())
    def lease():
        try:
            while os.read(sys.stdin.fileno(), 1):pass
        finally:stop.set()
    threading.Thread(target=lease,daemon=True,name="minecraft-lifetime").start()
    session=Session(path,stop)
    try:launch(session,properties(args.config));return 0
    except InterruptedError:return 0
    except Exception as error:
        session.phase="failed";session.error=str(error)
        print(f"ANDROIDPHONE_RUNTIME_FAILED: {error}",file=sys.stderr,flush=True);return 1
    finally:session.close()

if __name__=="__main__":raise SystemExit(main())
