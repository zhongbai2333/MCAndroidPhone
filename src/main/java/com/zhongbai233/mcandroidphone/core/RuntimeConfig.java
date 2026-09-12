package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Native host selection and validated, shell-free QEMU arguments. */
final class RuntimeConfig {
    final Properties values;
    final Path root;
    boolean persistentDisks;
    final boolean windows=System.getProperty("os.name").startsWith("Windows");
    final boolean mac=System.getProperty("os.name").startsWith("Mac");
    final String hostArch=arch(System.getProperty("os.arch"));
    RuntimeConfig(Properties values){this.values=values;root=Path.of(values.getProperty("root")).toAbsolutePath().normalize();}
    String get(String key,String fallback){String v=values.getProperty(key);return v==null||v.isBlank()?fallback:v;}
    boolean flag(String key){String v=get(key,"false").toLowerCase(Locale.ROOT);if(!Set.of("true","false").contains(v))throw new IllegalArgumentException("Invalid boolean: "+key);return v.equals("true");}
    int integer(String key,int fallback,int min,int max){int v=Integer.parseInt(get(key,""+fallback));if(v<min||v>max)throw new IllegalArgumentException("Invalid "+key);return v;}
    static String arch(String name){return switch(name.toLowerCase(Locale.ROOT)){case "aarch64","arm64"->"arm64";case "amd64","x86_64","x64"->"amd64";default->throw new IllegalArgumentException("Unsupported architecture: "+name);};}
    String guest(){return arch(get("guestArch",hostArch));}
    String accelerator() {
        String nativeAccel=windows?"whpx":mac?"hvf":"kvm",requested=get("accel","auto");
        if(requested.equals("auto"))return !guest().equals(hostArch)||(!windows&&!mac&&!Files.isWritable(Path.of("/dev/kvm")))?"tcg":nativeAccel;
        if(!requested.equals("tcg")&&(!requested.equals(nativeAccel)||!guest().equals(hostArch)))throw new IllegalArgumentException("Accelerator does not match host/guest; cross-architecture guests require TCG");
        return requested;
    }
    Path path(String key)throws IOException {
        String v=get(key,"");if(v.isEmpty())return null;
        if(v.indexOf(0)>=0||v.indexOf('\n')>=0||v.indexOf('\r')>=0)throw new IOException("Invalid path: "+key);
        Path p=Path.of(v);if(!p.isAbsolute())p=root.resolve(p);p=p.toAbsolutePath().normalize();
        if(!Files.isRegularFile(p))throw new IOException("Missing "+key+": "+p);return p;
    }
    Path executable(String key,String base)throws IOException {
        Path explicit=path(key);if(explicit!=null){if(!windows&&!Files.isExecutable(explicit))throw new IOException("Not executable: "+explicit);return explicit;}
        String name=base+(windows?".exe":"");
        var candidates=new ArrayList<Path>(List.of(root.resolve(key+"/bin/"+name),root.resolve(key+"/"+name)));
        for(String folder:System.getenv().getOrDefault("PATH","").split(File.pathSeparator))if(!folder.isBlank())candidates.add(Path.of(folder,name));
        if(!windows)for(String folder:List.of("/opt/homebrew/bin","/usr/local/bin","/usr/bin"))candidates.add(Path.of(folder,name));
        for(Path p:candidates)if(Files.isRegularFile(p)&&(windows||Files.isExecutable(p))&&!p.toString().contains("WindowsApps"))return p.toAbsolutePath();
        throw new IOException("Missing "+name+"; set "+key+" in mcandroidphone-runtime.properties");
    }
    String display(){String value=get("display","auto");if(value.equals("auto"))value=windows?"dbus":"vnc";
        if(!Set.of("vnc","dbus").contains(value)||(!windows&&value.equals("dbus")))throw new IllegalArgumentException("Use VNC on Unix until native D-Bus FD transport is implemented");return value;}
    String gpu(){String value=get("gpu","virtio");if(!Set.of("virtio","virgl").contains(value))throw new IllegalArgumentException("Invalid GPU mode");
        if(value.equals("virgl")&&(!windows||!display().equals("dbus")))throw new IllegalArgumentException("VirGL requires the Windows D-Bus GPU transport");return value;}
    static String option(Path p){return p.toString().replace(",",",,");}
    List<String> qemu(Path session,int vnc,int qmp,String uuid,String input)throws IOException {
        return qemu(session,vnc,qmp,uuid,input,0);
    }
    List<String> qemu(Path session,int vnc,int qmp,String uuid,String input,int environmentPort)throws IOException {
        return qemu(session,vnc,qmp,uuid,input,environmentPort,0);
    }
    List<String> qemu(Path session,int vnc,int qmp,String uuid,String input,int environmentPort,int cameraPort)throws IOException {
        String guest=guest(),gpu=gpu(),display=display(),accel=accelerator();
        int w=integer("width",1080,320,4096),h=integer("height",1920,320,4096);RfbClient.dimensions(w,h);
        int density=integer("density",480,120,640),memory=integer("memory",4096,256,65536),cpus=integer("cpus",2,1,256);
        if(!Set.of("mouse","touchscreen").contains(input))throw new IllegalArgumentException("Invalid input mode");
        if(qmp<1||qmp>65535||(display.equals("vnc")&&(vnc<5900||vnc>65535||vnc==qmp)))throw new IllegalArgumentException("Invalid local display/control ports");
        boolean bios=flag("bios");
        if(bios)for(String key:List.of("iso","disk","dataDisk","kernel","initrd"))if(!get(key,"").isEmpty())throw new IllegalArgumentException("bios cannot be combined with Android media");
        Path iso=path("iso"),disk=path("disk"),kernel=path("kernel"),initrd=path("initrd"),firmware=path("firmware");
        Path dataDisk=path("dataDisk"),firmwareVars=path("firmwareVars");
        if(dataDisk!=null&&disk==null)throw new IllegalArgumentException("dataDisk requires a system disk");
        if(firmwareVars!=null&&firmware==null)throw new IllegalArgumentException("firmwareVars requires firmware");
        int adbPort=integer("adbPort",0,0,65535);
        if(adbPort!=0&&(adbPort==qmp||adbPort==vnc||adbPort<1024))throw new IllegalArgumentException("Invalid local ADB port");
        if(environmentPort!=0&&(environmentPort<1024||environmentPort>65535||environmentPort==qmp||environmentPort==vnc||environmentPort==adbPort))
            throw new IllegalArgumentException("Invalid environment port");
        if(cameraPort!=0&&(cameraPort<1024||cameraPort>65535||cameraPort==qmp||cameraPort==vnc||cameraPort==adbPort||cameraPort==environmentPort))throw new IllegalArgumentException("Invalid camera port");
        if(!bios&&iso==null&&disk==null&&kernel==null) {
            var choices=new ArrayList<Path>();Path folder=root.resolve("images");
            if(Files.isDirectory(folder))try(var files=Files.list(folder)){files.filter(p->Files.isRegularFile(p)&&p.toString().toLowerCase(Locale.ROOT).endsWith(".iso")).forEach(choices::add);}
            if(choices.size()!=1)throw new IOException("Set an Android disk, ISO or kernel (no unique runtime/images ISO)");iso=choices.getFirst();
        }
        if(guest.equals("amd64")&&iso!=null&&disk==null) {
            String filename=iso.getFileName().toString();Path prepared=iso.resolveSibling(filename.substring(0,filename.lastIndexOf('.')));
            if(kernel==null)kernel=prepared.resolve("kernel");if(initrd==null)initrd=prepared.resolve("initrd.img");
            if(!Files.isRegularFile(kernel)||!Files.isRegularFile(initrd))throw new IOException("Prepare kernel/initrd matching the Android-x86 ISO");
        }
        if(initrd!=null&&kernel==null)throw new IllegalArgumentException("initrd requires kernel");
        if(guest.equals("arm64")&&kernel==null&&firmware==null)throw new IllegalArgumentException("ARM64 boot requires its UEFI firmware or kernel");
        String append=get("kernelAppend","");if(guest.equals("arm64")&&kernel!=null&&append.isEmpty())throw new IllegalArgumentException("ARM64 kernel boot requires image-specific kernelAppend");
        String format=get("diskFormat","qcow2");if(!Set.of("raw","qcow2").contains(format))throw new IllegalArgumentException("Invalid disk format");
        String varsFormat=get("firmwareVarsFormat","raw");if(!Set.of("raw","qcow2").contains(varsFormat))throw new IllegalArgumentException("Invalid firmware variables format");
        String dataFormat=get("dataDiskFormat","qcow2");if(!Set.of("raw","qcow2").contains(dataFormat))throw new IllegalArgumentException("Invalid data disk format");
        String video=(guest.equals("arm64")?"virtio-gpu":"virtio-vga")+(gpu.equals("virgl")?"-gl":"")+(guest.equals("arm64")?"-pci":"");
        String machine=(guest.equals("arm64")?"virt,gic-version=3":dataDisk!=null?"q35":"pc")+",accel="+accel;
        if(get("accel","auto").equals("auto")&&guest.equals("amd64")&&!accel.equals("tcg"))machine+=":tcg";
        String cameraTransport=get("cameraTransport","network");
        if(!Set.of("network","virtio").contains(cameraTransport))throw new IllegalArgumentException("Invalid camera transport");
        String cpuModel=get("cpuModel",guest.equals("amd64")?"Nehalem":accel.equals("tcg")?"max":"host");
        if(!cpuModel.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("Invalid CPU model");
        var args=new ArrayList<String>(List.of(executable("qemu",guest.equals("arm64")?"qemu-system-aarch64":"qemu-system-x86_64").toString(),
            "-name","MCAndroidPhone","-machine",machine,"-cpu",cpuModel,
            "-m",""+memory,"-smp",""+cpus,"-display",display.equals("dbus")?"dbus,p2p=on,gl="+(gpu.equals("virgl")?"on":"off"):"none",
            "-vga","none","-device",video+",id=phone-display,xres="+w+",yres="+h+",max_outputs=1,edid=on",
            "-device","qemu-xhci,id=usb","-device",input.equals("touchscreen")?"virtio-multitouch-pci,id=phone-touch,display=phone-display":"usb-tablet,bus=usb.0",
            "-qmp","tcp:127.0.0.1:"+qmp+",server=on,wait=off","-monitor","none","-serial","file:"+session.resolve("qemu-serial.log"),
            "-netdev","user,id=net0"+(adbPort==0?"":",hostfwd=tcp:127.0.0.1:"+adbPort+"-:5555")+
                (cameraPort==0||!cameraTransport.equals("network")?"":",guestfwd=tcp:10.0.2.100:18765-chardev:phone-camera"),
            "-device",guest.equals("arm64")||dataDisk!=null?"virtio-net-pci,netdev=net0":"e1000,netdev=net0","-no-reboot","-uuid",uuid));
        // USB serves UEFI; virtio carries Linux consumer keys (AC_HOME/AC_BACK) after boot.
        String data=get("qemuData","");if(!data.isEmpty()){Path folder=Path.of(data);if(!folder.isAbsolute())folder=root.resolve(folder);if(!Files.isDirectory(folder))throw new IOException("Missing QEMU data directory");args.addAll(List.of("-L",folder.toString()));}
        if(guest.equals("arm64"))args.addAll(List.of("-device","usb-kbd,bus=usb.0","-device","virtio-keyboard-pci,id=phone-keyboard","-device","virtio-rng-pci","-device","virtio-serial-pci"));
        if(environmentPort!=0)args.addAll(List.of("-device","virtio-serial-pci,id=phone-env-controller",
            "-chardev","socket,id=phone-env,host=127.0.0.1,port="+environmentPort+",reconnect-ms=1000",
            "-device","virtserialport,bus=phone-env-controller.0,chardev=phone-env,name="+EnvironmentChannel.PORT_NAME));
        if(display.equals("vnc"))args.addAll(List.of("-vnc","127.0.0.1:"+(vnc-5900)));
        if(cameraPort!=0){
            args.addAll(List.of("-chardev","socket,id=phone-camera,host=127.0.0.1,port="+cameraPort+",reconnect-ms=1000"));
            if(cameraTransport.equals("virtio"))args.addAll(List.of("-device","virtio-serial-pci,id=phone-camera-controller",
                "-device","virtserialport,bus=phone-camera-controller.0,chardev=phone-camera,name="+CameraChannel.PORT_NAME));
        }
        if(firmware!=null)args.addAll(List.of("-drive","if=pflash,unit=0,format=raw,readonly=on,file="+option(firmware)));
        if(firmwareVars!=null) {
            // Never write EFI boot variables back to the downloaded image template.
            Path copy=persistentDisks?firmwareVars:session.resolve("efi-vars.fd");if(!persistentDisks)Files.copy(firmwareVars,copy);
            args.addAll(List.of("-drive","if=pflash,unit=1,format="+varsFormat+",file="+option(copy)));
        }
        if(iso!=null) {
            if(guest.equals("arm64"))args.addAll(List.of("-device","virtio-scsi-pci,id=scsi","-drive","if=none,id=cdrom,media=cdrom,readonly=on,format=raw,file="+option(iso),"-device","scsi-cd,drive=cdrom"));
            else args.addAll(List.of("-drive","file="+option(iso)+",media=cdrom,readonly=on,format=raw","-boot","d"));
        }
        if(dataDisk!=null) {
            args.addAll(List.of("-drive","file="+option(disk)+",if=none,id=phone-system,format="+format,
                "-device","virtio-blk-pci,drive=phone-system,bootindex=0",
                "-drive","file="+option(dataDisk)+",if=none,id=phone-data,format="+dataFormat,
                "-device","virtio-blk-pci,drive=phone-data,bootindex=1"));
        }else if(disk!=null)args.addAll(List.of("-drive","file="+option(disk)+",if="+(guest.equals("arm64")?"virtio":"ide")+",format="+format));
        if(disk!=null&&!persistentDisks)args.add("-snapshot");
        if(kernel!=null) {
            args.addAll(List.of("-kernel",kernel.toString()));if(initrd!=null)args.addAll(List.of("-initrd",initrd.toString()));
            if(append.isEmpty())append="root=/dev/ram0 quiet SETUPWIZARD=0 SRC= DATA= video=Virtual-1:"+w+"x"+h+"-32@60e HWACCEL=0 DPI="+density+" console=tty0 console=ttyS0,115200";
            if(gpu.equals("virgl"))append=append.replace("HWACCEL=0","HWACCEL=1 GRALLOC=gbm HWC=drm");args.addAll(List.of("-append",append));
        }
        return args;
    }
}
