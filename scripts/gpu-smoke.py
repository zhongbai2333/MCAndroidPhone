"""Fast real VirGL -> D3D11 -> Java OpenGL validation, without starting Minecraft.
Uses existing QEMU/ANGLE/Android assets and the cached project dependencies.
Pixel readback is confined to this diagnostic; production never reads pixels.
"""
import sys,os,importlib.util,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
STAGE=ROOT/'scripts/tests/fixtures'
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'bridge')]
spec=importlib.util.spec_from_file_location('quick',ROOT/'scripts/quick-test.py');q=importlib.util.module_from_spec(spec);spec.loader.exec_module(q)
def main():
    with q.project_lock(ROOT):
        env=os.environ.copy();java=q.find_java();env['JAVA_HOME']=str(java);env['PATH']=str(ROOT/'.runtime/angle/bin')+os.pathsep+env['PATH'];env['PYTHONPATH']=str(ROOT/'bridge')
        s=q.Session(ROOT,env,'gpu-native-smoke')
        print('RUNTIME',s.runtime,flush=True)
        state='failed'
        try:
         s.run('gpu-compile',[*q.gradle_invocation(ROOT,java),'--offline','--no-configuration-cache','classes'],timeout=180)
         port=q.free_port()
         cmd=q.qemu_command(ROOT/'.runtime/qemu/bin/qemu-system-x86_64.exe',None,port,s.runtime,
          iso=ROOT/'.runtime/images/android-x86_64-9.0-r2.iso',kernel=ROOT/'.runtime/images/android-x86_64-9.0-r2/kernel',
          initrd=ROOT/'.runtime/images/android-x86_64-9.0-r2/initrd.img',input_mode='touchscreen',display_mode='dbus',gpu='virgl')
         ident=str(uuid.uuid4());cmd+=['-uuid',ident]
         p,_=s.start('qemu',cmd);s.qemu_process=p;s.qemu_port=port;s.qemu_uuid=ident;q.wait_qmp(port,p)
         bridge,_=s.start('bridge',[sys.executable,'-m','mcandroid_bridge','--backend','qemu','--qemu-display','d3d11',
          '--qemu-pid',str(s.child_pid(p)),'--qmp-port',str(port),'--qemu-input','touchscreen','--port','0','--runtime',s.runtime/'bridge'])
         cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1/org.lwjgl'
         jars=[]
         for module in ('lwjgl','lwjgl-glfw','lwjgl-opengl'):
          for jar in (cache/module/'3.4.1').rglob('*.jar'):
           if jar.name in (f'{module}-3.4.1.jar',f'{module}-3.4.1-natives-windows.jar'):jars.append(jar)
         if len(jars)!=6:raise RuntimeError('Minecraft LWJGL 3.4.1 runtime incomplete; build the project first')
         cp=os.pathsep.join(map(str,[*jars,ROOT/'build/classes/java/main']))
         log=s.run('gpu-consumer',[java/'bin/java.exe','--enable-native-access=ALL-UNNAMED','-cp',cp,STAGE/'GpuProbe.java',s.runtime/'bridge/bridge.properties'],timeout=110,watch=[('bridge',bridge),('qemu',p)])
         report=log.read_text(errors='replace')
         print(report,flush=True)
         if 'GPU_SMOKE_OK' not in report:raise RuntimeError('GPU consumer did not confirm pixel import')
         state='passed'
        finally:s.stop();s.save(state)

if __name__ == '__main__':
    main()
