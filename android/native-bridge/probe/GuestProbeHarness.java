package com.zhongbai233.mcandroidphone.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;

/** Opt-in local guest acceptance harness. Commands control only this test VM. */
public final class GuestProbeHarness {
 public static void main(String[] args) throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("GuestProbeHarness runtime.properties NEW-evidence-dir probe.apk");
  Path evidence=Path.of(args[1]).toAbsolutePath();Files.createDirectory(evidence);
  Path apk=Path.of(args[2]).toAbsolutePath();if(Files.size(apk)>16*1024*1024)throw new IllegalArgumentException("Probe APK too large");
  var p=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]))){p.load(r);}
  var options=new HashMap<String,String>();p.forEach((k,v)->{if(!k.toString().startsWith("benchmark"))options.put((String)k,(String)v);});
  options.put("storage","persistent");options.putIfAbsent("deviceId",UUID.randomUUID().toString());
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/probe.apk",exchange->{try(exchange){if(!exchange.getRequestURI().getPath().equals("/probe.apk")){exchange.sendResponseHeaders(404,-1);return;}
   exchange.getResponseHeaders().set("Content-Type","application/vnd.android.package-archive");exchange.sendResponseHeaders(200,Files.size(apk));Files.copy(apk,exchange.getResponseBody());}});
  server.start();Files.writeString(evidence.resolve("download-url.txt"),"http://10.0.2.2:"+server.getAddress().getPort()+"/probe.apk");
  Path game=options.containsKey("probeGameDirectory")?Path.of(options.remove("probeGameDirectory")):evidence.resolve("game");
  var runtime=new ManagedRuntime(game,options,()->GuestProbeHarness.class.getResourceAsStream("/mcandroidphone/runtime/native-guard.jar"));
  PhoneConnection connection=null;long frames=0,start=System.nanoTime();
  try {
   runtime.start().get(180,TimeUnit.SECONDS);connection=runtime.connect();connection.start();
   var command=(List<?>)Json.parse(Files.readString(runtime.sessionDirectory().resolve("qemu-command.json")));
   String address=(String)command.get(command.indexOf("-qmp")+1);int port=Integer.parseInt(address.split(":")[2].split(",")[0]);
   Files.writeString(evidence.resolve("session.txt"),runtime.sessionDirectory().toString());
   long nextShot=0;boolean running=true;
   while(running&&System.nanoTime()-start<TimeUnit.MINUTES.toNanos(25)) {
    if(runtime.state()==ManagedRuntime.State.FAILED)throw new IllegalStateException(runtime.status());
    try(var gpu=connection.pollGpuFrame()){if(gpu!=null)frames++;}try(var cpu=connection.pollFrame()){if(cpu!=null)frames++;}
    Path instruction=evidence.resolve("command.txt");
    if(Files.exists(instruction)) {
     String line=Files.readString(instruction).strip();Files.delete(instruction);String[] parts=line.split(" ",2);
     switch(parts[0]) {
      case "tap"->{String[] xy=parts[1].split(" ");connection.touch("DOWN",Double.parseDouble(xy[0]),Double.parseDouble(xy[1]));Thread.sleep(90);connection.touch("UP",0,0);}
      case "swipe"->{String[] xy=parts[1].split(" ");double x=Double.parseDouble(xy[0]),y=Double.parseDouble(xy[1]),ex=Double.parseDouble(xy[2]),ey=Double.parseDouble(xy[3]);connection.touch("DOWN",x,y);for(int i=1;i<=15;i++){Thread.sleep(30);connection.touch("MOVE",x+(ex-x)*i/15,y+(ey-y)*i/15);}connection.touch("UP",ex,ey);}
      case "text"->connection.text(parts[1]);
      case "key"->connection.key(parts[1]);
      case "enter"->{try(var q=new QmpClient(port,3000)){q.execute("send-key",Map.of("keys",List.of(Map.of("type","qcode","data","ret")),"hold-time",80));}}
      case "quit"->running=false;
      default->throw new IllegalArgumentException("Unknown probe command: "+parts[0]);
     }
     Files.writeString(evidence.resolve("commands.log"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    if(System.nanoTime()>nextShot) {
     nextShot=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
     try(var q=new QmpClient(port,3000)){q.execute("screendump",Map.of("filename",evidence.resolve("latest.png").toString(),"format","png"));}
     catch(Exception error){Files.writeString(evidence.resolve("screenshot-error.txt"),error.toString());}
     Files.writeString(evidence.resolve("progress.json"),Json.write(Map.of("frames",frames,"elapsedSeconds",TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start),"state",runtime.state().name())));
    }
    Thread.sleep(10);
   }
  } finally {
   if(connection!=null)connection.close();runtime.close();runtime.awaitStopped(Duration.ofSeconds(25));server.stop(0);
   Files.writeString(evidence.resolve("result.json"),Json.write(Map.of("state",runtime.state().name(),"frames",frames,"deviceId",options.get("deviceId"))));
  }
 }
}