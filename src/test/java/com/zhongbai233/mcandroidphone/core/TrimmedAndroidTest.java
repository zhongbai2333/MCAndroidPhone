package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class TrimmedAndroidTest {
    static Path evidence, adb, apk;
    static void require(boolean b,String message)throws IOException {if(!b)throw new IOException(message);}
    static void screenshot(int guest,String name)throws Exception {
        var p=new ProcessBuilder(adb.toString(),"-P",""+server,"-s","127.0.0.1:"+guest,"exec-out","screencap","-p").redirectOutput(evidence.resolve(name+".png").toFile()).start();
        try {require(p.waitFor(15,TimeUnit.SECONDS)&&p.exitValue()==0,"Screenshot failed");}finally{if(p.isAlive())p.destroyForcibly();}
    }
    static void cameraTest(ManagedRuntime runtime,int guest,int round)throws Exception {
        require(adb(guest,"install","-r",apk.toString()).contains("Success"),"APK install failed");
        adb(guest,"shell","input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n com.zhongbai233.mcandroidphone.camera/.CameraActivity");
        var picture=new java.awt.image.BufferedImage(320,240,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=picture.createGraphics();graphics.setColor(java.awt.Color.GREEN);graphics.fillRect(0,0,320,240);graphics.setColor(java.awt.Color.BLACK);graphics.drawString("TRIM V1 CAMERA TRANSPORT TEST",12,100);graphics.dispose();
        var encoded=new ByteArrayOutputStream();javax.imageio.ImageIO.write(picture,"jpeg",encoded);byte[] jpeg=encoded.toByteArray();
        var publish=Executors.newSingleThreadScheduledExecutor();var seq=new java.util.concurrent.atomic.AtomicLong();
        publish.scheduleAtFixedRate(()->runtime.publishCamera(seq.incrementAndGet(),320,240,jpeg),0,80,TimeUnit.MILLISECONDS);
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!runtime.cameraDemanded()&&System.nanoTime()<until)Thread.sleep(100);
            require(runtime.cameraDemanded(),"Camera transport not connected");Thread.sleep(1800);
            adb(guest,"shell","uiautomator dump /data/local/tmp/trim-camera.xml");String xml=adb(guest,"shell","cat /data/local/tmp/trim-camera.xml");
            Files.writeString(evidence.resolve("camera-ui-"+round+".xml"),xml);
            var m=java.util.regex.Pattern.compile("<node[^>]*text=\"拍照\"[^>]*>").matcher(xml);require(m.find(),"Camera shutter missing");String node=m.group();require(node.contains("enabled=\"true\""),"Camera shutter disabled");
            var bounds=java.util.regex.Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").matcher(node);require(bounds.find(),"Missing shutter bounds");
            int x=(Integer.parseInt(bounds.group(1))+Integer.parseInt(bounds.group(3)))/2,y=(Integer.parseInt(bounds.group(2))+Integer.parseInt(bounds.group(4)))/2;
            String before=adb(guest,"shell","content query --uri content://media/external/images/media --projection _display_name:relative_path:is_pending");
            adb(guest,"shell","input tap "+x+" "+y);Thread.sleep(1800);screenshot(guest,"camera-"+round);
            String photos=adb(guest,"shell","content query --uri content://media/external/images/media --projection _display_name:relative_path:is_pending");
            require(photos.contains("MCAndroidPhone")&&photos.contains("is_pending=0")&&photos.lines().filter(l->l.contains("Minecraft-")&&l.contains("is_pending=0")).count()>before.lines().filter(l->l.contains("Minecraft-")&&l.contains("is_pending=0")).count(),"New photo not published");Files.writeString(evidence.resolve("photos-"+round+".txt"),photos);
            System.out.println("CAMERA_TRANSPORT_PHOTO_OK round="+round);
        } finally {publish.shutdownNow();publish.awaitTermination(3,TimeUnit.SECONDS);}
    }
    static int server;
    static int port() throws IOException { try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {return socket.getLocalPort();} }
    static String adb(int guest,String... args)throws Exception {
        var cmd=new ArrayList<String>(List.of(adb.toString(),"-P",""+server));
        if(guest!=0)cmd.addAll(List.of("-s","127.0.0.1:"+guest));cmd.addAll(List.of(args));
        Path output=Files.createTempFile(evidence,"adb-",".txt"); Process p=null;
        try {p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if(!p.waitFor(15,TimeUnit.SECONDS))throw new IOException("ADB timeout");
            String result=Files.readString(output).trim();
            if(p.exitValue()!=0)return "ADB_EXIT_"+p.exitValue()+": "+result;
            return result;
        } finally {if(p!=null&&p.isAlive()){p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);}Files.deleteIfExists(output);}
    }
    public static void main(String[] args)throws Exception {
        evidence=Path.of(args[1]).toAbsolutePath();Files.createDirectories(evidence);
        adb=Path.of(args[2]).toAbsolutePath();server=port();apk=Path.of(args[3]).toAbsolutePath();
        var template=new Properties();try(var in=Files.newBufferedReader(Path.of(args[0]))){template.load(in);}
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        var reports=new ArrayList<Map<String,Object>>();Path game=evidence.resolve("game");
        try {
            for(int round=0;round<3;round+=2) {
                int guest=port();var options=new HashMap<String,String>();template.forEach((k,v)->options.put((String)k,(String)v));
                options.put("camera","true");options.put("deviceId",round==1?b:a);options.put("storage","persistent");options.put("adbPort",""+guest);
                long began=System.nanoTime();var runtime=new ManagedRuntime(game,options,()->TrimmedAndroidTest.class.getResourceAsStream("/mcandroidphone/runtime/native-guard.jar"));
                PhoneConnection connection=null; long frames=0;
                try {
                    runtime.start().get(180,TimeUnit.SECONDS);long runtimeReadyMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);connection=runtime.connect();connection.start();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(210);boolean booted=false;
                    while(System.nanoTime()<deadline) {
                        adb(0,"connect","127.0.0.1:"+guest);
                        if(adb(guest,"shell","getprop sys.boot_completed").equals("1")){booted=true;break;}
                        Frame frame=connection.pollFrame();if(frame!=null){frames++;frame.close();}
                        Thread.sleep(1500);
                    }
                    if(!booted)throw new IOException("Android boot timeout round="+round);
                    long androidBootMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
                    if(Boolean.parseBoolean(template.getProperty("expectedNoAvf","false"))) {
                        String apex=adb(guest,"shell","pm list packages --apex-only"),features=adb(guest,"shell","pm list features");
                        require(apex.contains("com.android.art")&&!apex.contains("com.android.virt")&&!apex.contains("com.android.compos"),"Wrong specialized APEX set");
                        require(!features.contains("android.software.virtualization_framework"),"Removed AVF still advertised");
                        Files.writeString(evidence.resolve("apex-"+round+".txt"),apex+"\n"+features);
                    }
                    Files.writeString(evidence.resolve("policy-"+round+".txt"),adb(guest,"shell","dumpsys window policy"));
                    Thread.sleep(500);
                    String packages=adb(guest,"shell","pm list packages -s");Files.writeString(evidence.resolve("packages-"+round+".txt"),packages);
                    require(!packages.contains("package:com.android.egg\n")&&!packages.contains("package:com.android.dreams.phototable\n"),"Trimmed package survived");
                    for(String p:List.of("com.android.settings","com.android.systemui","com.android.webview","com.android.inputmethod.latin"))require(packages.contains("package:"+p+"\n"),"Core package missing "+p);
                    String web=adb(guest,"shell","dumpsys webviewupdate");Files.writeString(evidence.resolve("webview-"+round+".txt"),web);require(web.contains("com.android.webview"),"WebView missing");
                    String network="";long networkDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
                    do {network=adb(guest,"shell","ping -c 1 -W 2 10.0.2.2");if(network.contains("1 received")||network.contains("1 packets received"))break;Thread.sleep(1500);}while(System.nanoTime()<networkDeadline);
                    Files.writeString(evidence.resolve("network-state-"+round+".txt"),adb(guest,"shell","ip addr; ip route show table all; dumpsys ethernet"));
                    require(network.contains("1 received")||network.contains("1 packets received"),"Guest NAT ping failed: "+network);
                    Files.writeString(evidence.resolve("network-"+round+".txt"),network);
                    cameraTest(runtime,guest,round);
                    String compiled=adb(guest,"shell","cmd package compile -m speed -f com.zhongbai233.mcandroidphone.camera");
                    require(compiled.contains("Success"),"App compilation failed: "+compiled);
                    Files.writeString(evidence.resolve("compile-"+round+".txt"),compiled);
                    String marker;
                    if(round==0)marker=adb(guest,"shell","echo phone-a-persistent > /data/local/tmp/mcphone-overlay-acceptance; cat /data/local/tmp/mcphone-overlay-acceptance");
                    else marker=adb(guest,"shell","if [ -f /data/local/tmp/mcphone-overlay-acceptance ]; then cat /data/local/tmp/mcphone-overlay-acceptance; else echo independent-phone; fi");
                    String expected=round==1?"independent-phone":"phone-a-persistent";
                    if(!marker.equals(expected))throw new IOException("Wrong phone data: "+marker);
                    String boot=adb(guest,"shell","getprop ro.build.version.release; getprop ro.product.cpu.abi");
                    long time=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
                    reports.add(Map.of("round",round,"deviceId",round==1?b:a,"bootMs",time,"runtimeReadyMs",runtimeReadyMs,"androidBootMs",androidBootMs,"marker",marker,"android",boot,"session",runtime.sessionDirectory().toString(),"framesDuringBoot",frames));
                    Files.writeString(evidence.resolve("acceptance.json"),Json.write(reports));
                    System.out.println("ANDROID_OVERLAY_ROUND_OK round="+round+" bootMs="+time+" marker="+marker+" "+boot.replace('\n',' '));
                } finally {
                    if(connection!=null)connection.close();runtime.close();runtime.awaitStopped(Duration.ofSeconds(25));
                    if(runtime.state()!=ManagedRuntime.State.STOPPED)throw new IOException("Runtime cleanup failed: "+runtime.status());
                    var stopped=Json.object(Files.readString(runtime.sessionDirectory().resolve("session.json")));
                    System.out.println("SHUTDOWN_OUTCOME "+stopped.get("shutdownOutcome"));
                    if(!"guest-confirmed".equals(stopped.get("shutdownOutcome")))throw new IOException("Guest did not confirm shutdown");
                }
            }
            var bases=game.resolve("mcandroidphone/devices/.bases");
            try(var paths=Files.list(bases)){var images=paths.filter(p->p.toString().endsWith(".qcow2")).toList();
                if(images.size()!=1)throw new IOException("Expected one shared system base");
                Path base=images.getFirst();if(!base.getFileName().toString().startsWith(RuntimeBundle.hash(base)))throw new IOException("Base changed during real Android writes");}
            System.out.println("TRIMMED_ANDROID_OK two boots, restart persistence, camera transport, APK install, core packages, NAT, base unchanged");
        } finally {System.out.println(adb(0,"kill-server"));}
    }
}
