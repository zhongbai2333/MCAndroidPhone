package com.zhongbai233.mcandroidphone.core;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded interactive native Android acceptance. Commands use the production PhoneConnection. */
public final class AndroidRuntimeTest {
    private static double number(Map<String,Object> data,String key,double fallback){Object value=data.get(key);return value instanceof Number n?n.doubleValue():fallback;}
    private static int channel(double value){return Math.max(0,Math.min(255,(int)Math.round(value)));}
    private static void capture(Frame frame,Path file)throws Exception {
        int w=frame.width(),h=frame.height();ByteBuffer pixels=frame.pixels();var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double l=(Byte.toUnsignedInt(pixels.get(y*w+x))-16)*255.0/219;
            int uv=w*h+(y/2)*w+(x/2)*2;double u=(Byte.toUnsignedInt(pixels.get(uv))-128)*255.0/224,v=(Byte.toUnsignedInt(pixels.get(uv+1))-128)*255.0/224;
            image.setRGB(x,y,(channel(l+1.5748*v)<<16)|(channel(l-.187324*u-.468124*v)<<8)|channel(l+1.8556*u));
        }
        ImageIO.write(image,"png",file.toFile());
    }
    public static void main(String[] args)throws Exception {
        Path config=Path.of(args[0]).toAbsolutePath(),evidence=Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        var values=new Properties();try(var in=Files.newBufferedReader(config)){values.load(in);}var options=new HashMap<String,String>();values.forEach((k,v)->options.put((String)k,(String)v));
        Path commands=Files.createDirectories(evidence.resolve("commands")),results=Files.createDirectories(evidence.resolve("results"));
        var runtime=new ManagedRuntime(evidence.resolve("game"),options,()->AndroidRuntimeTest.class.getResourceAsStream("/mcandroidphone/runtime/native-guard.jar"));
        Frame latest=null;PhoneConnection client=null;long frames=0;var handled=new HashSet<Path>();
        Map<String,Object> environmentInput=null;
        var environmentSampler=new com.zhongbai233.mcandroidphone.environment.EnvironmentSampler();
        try {
            runtime.start().get(115,TimeUnit.SECONDS);client=runtime.connect();client.start();
            Files.writeString(evidence.resolve("ready.json"),Json.write(Map.of("session",runtime.sessionDirectory().toString(),"pid",ProcessHandle.current().pid())));
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(15),lastReport=0;boolean stop=false;
            while(!stop&&System.nanoTime()<deadline) {
                if(runtime.state()==ManagedRuntime.State.FAILED)throw new IllegalStateException(runtime.status());
                Frame next=client.pollFrame();if(next!=null){if(latest!=null)latest.close();latest=next;frames++;}
                List<Path> pending;try(var paths=Files.list(commands)){pending=paths.filter(p->p.toString().endsWith(".json")&&!handled.contains(p)).sorted().toList();}
                for(Path request:pending) {
                    var data=Json.object(Files.readString(request));String op=(String)data.get("op");
                    switch(op) {
                        case "tap"->{double u=((Number)data.get("u")).doubleValue(),v=((Number)data.get("v")).doubleValue();client.touch("DOWN",u,v);Thread.sleep(100);client.touch("UP",u,v);}
                        case "touch"->client.touch((String)data.get("phase"),((Number)data.get("u")).doubleValue(),((Number)data.get("v")).doubleValue());
                        case "key"->client.key((String)data.get("value"));
                        case "text"->client.text((String)data.get("value"));
                        case "capture"->{if(latest==null)throw new IllegalStateException("No frame to capture");capture(latest,evidence.resolve(request.getFileName().toString().replace(".json",".png")));}
                        case "reconnect"->{client.close();client=runtime.connect();client.start();}
                        case "environment"->environmentInput=data;
                        case "stop"->stop=true;
                        default->throw new IllegalArgumentException("Unknown test command: "+op);
                    }
                    handled.add(request);Files.writeString(results.resolve(request.getFileName()),Json.write(Map.of("operation",op,"frames",frames,"epoch",client.connectionEpoch(),"status",client.status())));
                }
                if(environmentInput!=null) {
                    var d=environmentInput;
                    runtime.publishEnvironment(environmentSampler.sample(System.nanoTime(),(String)d.getOrDefault("dimension","minecraft:overworld"),
                        number(d,"x",0),number(d,"y",63),number(d,"z",0),number(d,"yaw",0),number(d,"pitch",0),
                        number(d,"roll",0),0,0,15,!Boolean.FALSE.equals(d.get("available"))));
                }
                if(System.nanoTime()-lastReport>5_000_000_000L){lastReport=System.nanoTime();System.out.println("ANDROID_TEST: frames="+frames+" "+client.status());}
                Thread.sleep(20);
            }
        }finally{if(latest!=null)latest.close();if(client!=null)client.close();runtime.close();runtime.awaitStopped(Duration.ofSeconds(25));
            Files.writeString(evidence.resolve("stopped.json"),Json.write(Map.of("state",runtime.state().name(),"status",runtime.status())));
            if(runtime.state()!=ManagedRuntime.State.STOPPED)throw new IllegalStateException(runtime.status());}
    }
}
