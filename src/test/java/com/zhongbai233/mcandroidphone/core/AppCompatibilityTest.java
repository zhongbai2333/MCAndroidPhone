package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Opt-in real guest acceptance. Game captures require review; never claims FPS or Go from a stock image. */
public final class AppCompatibilityTest extends TrimmedAndroidTest {
    static final String CALC="org.fossify.math", GAME="org.andstatus.game2048";
    static String ui(int guest,String name)throws Exception {
        require(adb(guest,"shell","uiautomator dump /data/local/tmp/mcphone-app-ui.xml").contains("dumped"),"UI dump failed");
        String xml=adb(guest,"shell","cat /data/local/tmp/mcphone-app-ui.xml");Files.writeString(evidence.resolve(name+".xml"),xml);return xml;
    }
    static Element node(String xml,String id)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var nodes=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getElementsByTagName("node");
        for(int i=0;i<nodes.getLength();i++){var e=(Element)nodes.item(i);if(id.equals(e.getAttribute("resource-id")))return e;}
        throw new IOException("Missing UI element "+id);
    }
    static void tap(int guest,Element node)throws Exception {
        require("true".equals(node.getAttribute("enabled")),"Disabled UI element");
        var m=java.util.regex.Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(node.getAttribute("bounds"));require(m.matches(),"Invalid bounds");
        adb(guest,"shell","input tap "+((Integer.parseInt(m.group(1))+Integer.parseInt(m.group(3)))/2)+" "+((Integer.parseInt(m.group(2))+Integer.parseInt(m.group(4)))/2));
    }
    public static void main(String[] args)throws Exception {
        if((args.length!=6&&args.length!=7)||!Set.of("baseline","go").contains(args[5]))throw new IllegalArgumentException("config evidence adb calculator.apk game2048.apk baseline|go [test-game-directory]");
        evidence=Path.of(args[1]).toAbsolutePath();Files.createDirectories(evidence);adb=Path.of(args[2]).toAbsolutePath();server=port();int guest=port();
        Path calc=Path.of(args[3]).toAbsolutePath(),gameApk=Path.of(args[4]).toAbsolutePath();
        require(RuntimeBundle.hash(calc).equals("7c5443c22d6d62c67dfe80df33ea9161ff0fa440f92590bcb45d7008b57613ed"),"Calculator version/hash differs from pinned sample");
        require(RuntimeBundle.hash(gameApk).equals("12191f38ae70c1113bb4bec66a1fa4b2cf0d6f15b39371a7446ce2f6f181c4e6"),"Game version/hash differs from pinned sample");
        var p=new Properties();try(var in=Files.newBufferedReader(Path.of(args[0]))){p.load(in);}
        var options=new HashMap<String,String>();p.forEach((k,v)->options.put((String)k,(String)v));options.put("deviceId",UUID.randomUUID().toString());options.put("adbPort",""+guest);options.put("camera","false");
        Path gameDirectory=args.length==7?Path.of(args[6]).toAbsolutePath():evidence.resolve("game");
        var runtime=new ManagedRuntime(gameDirectory,options,()->AppCompatibilityTest.class.getResourceAsStream("/mcandroidphone/runtime/native-guard.jar"));PhoneConnection connection=null;
        try {
            runtime.start().get(180,TimeUnit.SECONDS);connection=runtime.connect();connection.start();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(210);boolean boot=false;
            while(System.nanoTime()<until){adb(0,"connect","127.0.0.1:"+guest);if(adb(guest,"shell","getprop sys.boot_completed").equals("1")){boot=true;break;}Thread.sleep(1500);}
            require(boot,"Android boot timeout");String lowRam=adb(guest,"shell","getprop ro.config.low_ram");
            Files.writeString(evidence.resolve("guest-properties.txt"),adb(guest,"shell","getprop"));
            if(args[5].equals("go")) {
                require(lowRam.equals("true"),"Requested Go acceptance but guest is not low-RAM Go");
                require(adb(guest,"shell","getprop ro.product.device").endsWith("_go"),"Guest product is not a Go image");
            }
            for(Path apk:List.of(calc,gameApk))require(adb(guest,"install","-r",apk.toString()).contains("Success"),"APK install failed "+apk);
            adb(guest,"shell","input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; wm size 720x1280; wm density 320");
            require(adb(guest,"shell","am start -W -n "+CALC+"/.activities.SplashActivity.Green").contains("Status: ok"),"Calculator launch failed");Thread.sleep(1500);
            String xml=ui(guest,"calculator-before");
            for(String key:List.of("btn_clear","btn_1","btn_2","btn_plus","btn_3","btn_4","btn_equals"))tap(guest,node(xml,CALC+":id/"+key));
            require(node(ui(guest,"calculator-result"),CALC+":id/result").getAttribute("text").equals("46"),"12+34 did not equal 46");screenshot(guest,"calculator-result");
            require(adb(guest,"shell","am start -W -n "+GAME+"/.MyMainActivity").contains("Status: ok"),"Game launch failed");Thread.sleep(1800);screenshot(guest,"game-initial");
            // Pinned game's first-launch tutorial close button at the fixed test resolution.
            adb(guest,"shell","input tap 607 74");Thread.sleep(500);screenshot(guest,"game-before");
            String[] moves={"500 850 180 850","360 1000 360 650","180 850 570 850","360 650 360 1000"};
            for(int i=0;i<24;i++){adb(guest,"shell","input swipe "+moves[i%4]+" 180");Thread.sleep(150);}
            Thread.sleep(2000);screenshot(guest,"game-after");
            Files.writeString(evidence.resolve("game-memory.txt"),adb(guest,"shell","dumpsys meminfo "+GAME));
            adb(guest,"shell","input keyevent KEYCODE_HOME");Thread.sleep(500);
            require(adb(guest,"shell","am start -W -n "+GAME+"/.MyMainActivity").contains("Status: ok"),"Game resume failed");Thread.sleep(3000);screenshot(guest,"game-resumed");
            require(adb(guest,"shell","pidof "+GAME).matches("\\d+( \\d+)*"),"Game process exited");
            Files.writeString(evidence.resolve("acceptance.json"),Json.write(Map.of("mode",args[5],"lowRam",lowRam,"calculator","12+34=46","gameInputSwipes",24,"gameplay","requires-visual-review","session",runtime.sessionDirectory().toString())));
            System.out.println("APP_CALCULATOR_OK; GAME_CAPTURES_REQUIRE_REVIEW mode="+args[5]);
        } finally {
            try {
                if(connection!=null)connection.close();runtime.close();runtime.awaitStopped(Duration.ofSeconds(35));
                require(runtime.state()==ManagedRuntime.State.STOPPED,"Runtime cleanup failed: "+runtime.status());
                Path session=runtime.sessionDirectory().resolve("session.json");
                if(Files.isRegularFile(session))Files.copy(session,evidence.resolve("shutdown.json"),StandardCopyOption.REPLACE_EXISTING);
                System.out.println("APP_TEST_SHUTDOWN "+runtime.state());
            } finally {System.out.println(adb(0,"kill-server"));}
        }
    }
}
