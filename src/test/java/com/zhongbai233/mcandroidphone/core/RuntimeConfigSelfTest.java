package com.zhongbai233.mcandroidphone.core;

import java.nio.file.*;
import java.util.*;

final class RuntimeConfigSelfTest {
    private static void require(boolean ok,String message){ManagedRuntimeSelfTest.require(ok,message);}
    private static RuntimeConfig config(Path root,String host,String arch,Map<String,String> overrides) {
        var properties=new Properties();properties.setProperty("root",root.toString());properties.setProperty("qemu",root.resolve("qemu fake.exe").toString());properties.setProperty("bios","true");overrides.forEach(properties::setProperty);
        String oldHost=System.getProperty("os.name"),oldArch=System.getProperty("os.arch");
        try{System.setProperty("os.name",host);System.setProperty("os.arch",arch);return new RuntimeConfig(properties);}finally{System.setProperty("os.name",oldHost);System.setProperty("os.arch",oldArch);}
    }
    static void run()throws Exception {
        Path root=Files.createTempDirectory("java-qemu-command-");Path qemu=Files.writeString(root.resolve("qemu fake.exe"),"test");qemu.toFile().setExecutable(true);
        Files.writeString(root.resolve("firmware,readonly.fd"),"test");Files.writeString(root.resolve("android.qcow2"),"test");Files.writeString(root.resolve("kernel"),"test");Files.writeString(root.resolve("initrd.img"),"test");
        var mac=config(root,"Mac OS X","aarch64",Map.of("firmware","firmware,readonly.fd"));var command=mac.qemu(root,5901,50001,"uuid","mouse");
        require(command.contains("virt,gic-version=3,accel=hvf")&&command.contains("host"),"ARM HVF selection");require(command.stream().anyMatch(v->v.contains("file="+root+java.io.File.separator+"firmware,,readonly.fd")),"QEMU comma escaping");
        var cross=config(root,"Mac OS X","aarch64",Map.of("guestArch","amd64"));require(cross.accelerator().equals("tcg"),"Cross architecture must use TCG");
        try{config(root,"Mac OS X","aarch64",Map.of("guestArch","amd64","accel","hvf")).accelerator();throw new AssertionError("Cross HVF accepted");}catch(IllegalArgumentException expected){}
        var win=config(root,"Windows 11","amd64",Map.of("gpu","virgl"));command=win.qemu(root,5901,50001,"uuid","touchscreen");require(win.display().equals("dbus")&&command.contains("dbus,p2p=on,gl=on")&&command.contains("pc,accel=whpx:tcg"),"Windows display/acceleration");
        var linux=config(root,"Linux","aarch64",Map.of("firmware","firmware,readonly.fd","accel","kvm"));require(linux.display().equals("vnc")&&linux.accelerator().equals("kvm"),"Linux CPU display");
        var disk=config(root,"Mac OS X","aarch64",Map.of("bios","false","disk","android.qcow2","firmware","firmware,readonly.fd"));command=disk.qemu(root,5901,50001,"uuid","mouse");require(command.contains("-snapshot")&&command.stream().anyMatch(s->s.contains("if=virtio,format=qcow2")),"Read-only backing disk snapshot");
        var kernel=config(root,"Mac OS X","aarch64",Map.of("bios","false","kernel","kernel","initrd","initrd.img","kernelAppend","console=ttyAMA0 root=/dev/vda"));command=kernel.qemu(root,5901,50001,"uuid","mouse");require(command.contains("console=ttyAMA0 root=/dev/vda")&&!command.contains("-snapshot"),"ARM direct kernel without ISO");
        try{config(root,"Mac OS X","aarch64",Map.of("bios","false","kernel","kernel")).qemu(root,5901,50001,"uuid","mouse");throw new AssertionError("ARM kernel boot without append");}catch(IllegalArgumentException expected){}
        Files.writeString(root.resolve("userdata.qcow2"),"data");Files.writeString(root.resolve("vars.fd"),"pristine variables");
        Path session=Files.createDirectory(root.resolve("session"));
        var multi=config(root,"Mac OS X","aarch64",Map.of("bios","false","disk","android.qcow2","dataDisk","userdata.qcow2","firmware","firmware,readonly.fd","firmwareVars","vars.fd","firmwareVarsFormat","qcow2","adbPort","15555"));
        command=multi.qemu(session,5901,50001,"uuid","touchscreen");
        require(command.contains("virtio-blk-pci,drive=phone-system,bootindex=0")&&command.contains("virtio-blk-pci,drive=phone-data,bootindex=1")&&command.contains("-snapshot"),"Two snapshot disks in stable boot order");
        require(command.contains("user,id=net0,hostfwd=tcp:127.0.0.1:15555-:5555"),"ADB must bind only to loopback");
        require(command.contains("usb-kbd,bus=usb.0")&&command.contains("virtio-keyboard-pci,id=phone-keyboard"),"ARM needs firmware and Android navigation keyboards");
        for(var platform:List.of(mac,win,linux)) {
            var camera=platform.qemu(root,5901,50001,"uuid","mouse",0,50003);
            require(camera.stream().anyMatch(v->v.contains("guestfwd=tcp:10.0.2.100:18765-chardev:phone-camera")),"Per-VM fixed guest camera address");
            require(camera.contains("socket,id=phone-camera,host=127.0.0.1,port=50003,reconnect-ms=1000"),"Camera host endpoint must remain private and reconnectable");
            require(camera.stream().noneMatch(v->v.contains("virtserialport")&&v.contains("phone-camera")),"Only one camera frontend can own the stream");
            var env=platform.qemu(root,5901,50001,"uuid","mouse",50002);
            require(env.contains("socket,id=phone-env,host=127.0.0.1,port=50002,reconnect-ms=1000"),"Loopback QEMU environment endpoint");
            require(env.contains("virtserialport,bus=phone-env-controller.0,chardev=phone-env,name=com.mcandroidphone.environment"),"Architecture-neutral guest port");
        }
        var virtioCamera=config(root,"Mac OS X","aarch64",Map.of("firmware","firmware,readonly.fd","cameraTransport","virtio")).qemu(root,5901,50001,"uuid","mouse",0,50003);
        require(virtioCamera.stream().anyMatch(v->v.contains("name=com.mcandroidphone.camera"))&&virtioCamera.stream().noneMatch(v->v.contains("guestfwd=")),"Explicit VirtIO camera transport retained");
        require(command.stream().anyMatch(s->s.startsWith("if=pflash,unit=1,format=qcow2,")),"Explicit QCOW2 EFI variables");
        Files.writeString(session.resolve("efi-vars.fd"),"modified");require(Files.readString(root.resolve("vars.fd")).equals("pristine variables"),"EFI template was modified");
        var amd=config(root,"Windows 11","amd64",Map.of("bios","false","disk","android.qcow2","dataDisk","userdata.qcow2"));
        command=amd.qemu(root,5901,50001,"uuid","mouse",50002);
        require(command.contains("q35,accel=whpx:tcg")&&command.contains("virtio-net-pci,netdev=net0")&&command.contains("virtio-blk-pci,drive=phone-data,bootindex=1"),"AMD64 VirtIO two-disk image support");
        System.out.println("JAVA_QEMU_PLATFORM_COMMAND_TESTS_OK");
    }
}
