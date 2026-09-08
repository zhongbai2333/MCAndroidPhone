"""Portable entry point; no pre-started bridge. Uses the same JAR-owned runtime on every host."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'bridge'))
from mcandroid_bridge._launch.platforms import Host,capabilities


def compile_core():
    classes=ROOT/'build/portable-classes';classes.mkdir(parents=True,exist_ok=True)
    sources=sorted(p for kind in ('main','test') for p in (ROOT/f'src/{kind}/java/com/zhongbai233/mcandroidphone/core').glob('*.java'))
    for kind in ('main','test'):
        sources.extend(p for p in (ROOT/f'src/{kind}/java/com/zhongbai233/mcandroidphone/phone').glob('*.java')
                       if p.name in ('PhoneGeometry.java','PhonePose.java','PhoneGeometrySelfTest.java'))
    subprocess.run([java_tool('javac'),'-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
    archive=classes/'mcandroidphone/runtime/bridge.zip';archive.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for p in (ROOT/'bridge/mcandroid_bridge').rglob('*.py'):z.write(p,p.relative_to(ROOT/'bridge'))
        z.write(ROOT/'LICENSE','licenses/LICENSE')
    return classes


def java_tool(name):
    exe=name+('.exe' if os.name=='nt' else '')
    home=os.environ.get('JAVA_HOME')
    if home and (Path(home)/'bin'/exe).is_file():return str(Path(home)/'bin'/exe)
    found=shutil.which(exe)
    if not found:raise RuntimeError('Install Java 25 and set JAVA_HOME')
    return found


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('check','quick','build','pattern','qemu','bios','runtime-smoke','native-probe'))
    parser.add_argument('--gpu',choices=('virtio','virgl'),default='virtio')
    parser.add_argument('--guest-arch',choices=('amd64','arm64'))
    parser.add_argument('--world-smoke',action='store_true')
    parser.add_argument('--warmup',type=int,default=0)
    parser.add_argument('--set',action='append',default=[],metavar='KEY=VALUE',help='Runtime property; paths stay single argv values')
    args=parser.parse_args();host=Host.current()
    if args.mode=='check':print(json.dumps(capabilities(),indent=2));return
    env=os.environ.copy();env['PYTHONPATH']=str(ROOT/'bridge')
    if args.mode=='native-probe':
        if host.system=='Darwin':
            output=ROOT/'build/native-probe/iosurface-probe';output.parent.mkdir(parents=True,exist_ok=True)
            subprocess.run(['xcrun','clang','-Wno-deprecated-declarations',str(ROOT/'native/macos/iosurface_probe.m'),
                            '-framework','Foundation','-framework','Metal','-framework','IOSurface','-framework','OpenGL','-o',str(output)],check=True)
            result=subprocess.run([str(output)])
            if result.returncode==77: print("GPU capability unavailable on this host; production zero-copy is not validated.")
            elif result.returncode: result.check_returncode()
        elif host.system=='Linux':
            output=ROOT/'build/native-probe/dmabuf-probe';output.parent.mkdir(parents=True,exist_ok=True)
            subprocess.run(['cc',str(ROOT/'native/linux/dmabuf_probe.c'),'-lEGL','-lGL','-o',str(output)],check=True)
            result=subprocess.run([str(output)])
            if result.returncode==77: print("GPU capability unavailable on this host; production zero-copy is not validated.")
            elif result.returncode: result.check_returncode()
        else:raise RuntimeError('Windows GPU validation: scripts/gpu-smoke.py (uses prepared QEMU/Android/ANGLE)')
        return
    if args.mode in ('quick','runtime-smoke'):
        classes=compile_core()
        command=[java_tool('java'),'--enable-native-access=ALL-UNNAMED','-cp',str(classes)]
        if args.mode=='quick':
            for directory in ('bridge/tests','scripts/tests'):
                # Bound each suite and show the blocked thread if a new host exposes a deadlock.
                runner='import faulthandler,runpy; faulthandler.dump_traceback_later(120,exit=True); runpy.run_module("unittest",run_name="__main__"); faulthandler.cancel_dump_traceback_later()'
                subprocess.run([sys.executable,'-u','-c',runner,'discover','-s',directory,'-v'],cwd=ROOT,env=env,check=True)
            subprocess.run([*command,'com.zhongbai233.mcandroidphone.core.CoreSelfTest'],check=True)
            subprocess.run([*command,'com.zhongbai233.mcandroidphone.phone.PhoneGeometrySelfTest'],check=True)
            subprocess.run([*command,'com.zhongbai233.mcandroidphone.core.ManagedRuntimeSelfTest',sys.executable,str(ROOT/'.runtime')],check=True)
        else:
            subprocess.run([*command,'com.zhongbai233.mcandroidphone.core.RuntimeSmoke',sys.executable,str(ROOT/'.runtime'),*args.set],check=True)
        return
    properties={'python':sys.executable,'gpu':args.gpu}
    if args.mode!='build':properties['backend']='pattern' if args.mode=='pattern' else 'qemu'
    if args.mode=='bios':properties.update(bios='true',guestArch='amd64',input='mouse')
    if args.guest_arch:properties['guestArch']=args.guest_arch
    for entry in args.set:
        key,sep,value=entry.partition('=')
        if not sep or key not in ('root','backend','gpu','python','qemu','iso','disk','diskFormat','kernel','initrd',
            'ffmpeg','angle','width','height','density','memory','cpus','accel','guestArch','display','firmware','kernelAppend','input','colorOrder','bios'):
            raise ValueError(f'Unknown runtime override: {key}')
        properties[key]=value
    wrapper=str(ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew'))
    command=([wrapper] if os.name=='nt' else ['sh',wrapper])+['build' if args.mode=='build' else 'runClient']
    command += [f'-PphoneRuntime{k[0].upper()+k[1:]}={v}' for k,v in properties.items()]
    if args.world_smoke:
        command += ['-PphoneWorldSmoke=true',f'-PphoneSmokeWarmupSeconds={args.warmup}']
        if args.mode=='pattern':command.append('-PphonePatternSmoke=true')
        import uuid
        evidence=ROOT/'.runtime/evidence'/str(uuid.uuid4());evidence.mkdir(parents=True)
        command.append('-PphoneSmokeScreenshot='+str(evidence/'phone.png'))
        print('Evidence:',evidence,flush=True)
    subprocess.run(command,cwd=ROOT,check=True)


if __name__=='__main__':main()
