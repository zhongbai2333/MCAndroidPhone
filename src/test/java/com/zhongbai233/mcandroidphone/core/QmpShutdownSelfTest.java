package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class QmpShutdownSelfTest {
    public static void main(String[] args)throws Exception {
        for(String mode:List.of("before-reply","power-key","delayed","powerdown-only","host-shutdown","guest-reset","wrong-uuid"))test(mode);
        System.out.println("QMP_SHUTDOWN_OK guest confirmation, event ordering, timeout, host/reset rejection, UUID isolation");
    }
    static void write(OutputStream out,Object value)throws IOException {out.write((Json.write(value)+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void test(String mode)throws Exception {
        String uuid=UUID.randomUUID().toString();
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var fixture=CompletableFuture.runAsync(()->{try(var socket=server.accept()) {
                socket.setSoTimeout(5000);var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));var out=socket.getOutputStream();
                write(out,Map.of("QMP",Map.of()));
                var cap=Json.object(in.readLine());write(out,Map.of("id",cap.get("id"),"return",Map.of()));
                var identity=Json.object(in.readLine());check(identity.get("execute").equals("query-uuid"),"Verify VM before shutdown");
                write(out,Map.of("id",identity.get("id"),"return",Map.of("UUID",mode.equals("wrong-uuid")?"different":uuid)));
                String command=in.readLine();
                if(mode.equals("wrong-uuid")){check(command==null,"Wrong VM received a command");return;}
                var request=Json.object(command);check(request.get("execute").equals(mode.equals("power-key")?"send-key":"system_powerdown"),"Must request guest shutdown, not quit");
                if(mode.equals("power-key"))check(request.get("arguments").equals(Map.of("hold-time",1500L,"keys",List.of(Map.of("type","qcode","data","power")))),"Power key must be held for 1500 ms");
                var event=Map.of("event","SHUTDOWN","data",Map.of("guest",!mode.equals("host-shutdown"),"reason",mode.equals("guest-reset")?"guest-reset":"guest-shutdown"));
                write(out,Map.of("event","POWERDOWN")); // Request acknowledgement is not completion.
                if(mode.equals("before-reply")){write(out,event);return;}
                write(out,Map.of("id",request.get("id"),"return",Map.of()));
                if(mode.equals("delayed"))Thread.sleep(500); // Longer than the command timeout.
                if(!mode.equals("powerdown-only"))write(out,event);
                check(in.readLine()==null,"Unexpected follow-up command");
            }catch(Exception e){throw new CompletionException(e);}});
            long began=System.nanoTime();
            try(var qmp=new QmpClient(server.getLocalPort(),300)) {
                if(mode.equals("wrong-uuid")) {try{qmp.powerdown(uuid,900);throw new AssertionError("Wrong UUID accepted");}catch(IOException expected){}}
                else check(qmp.powerdown(uuid,900,mode.equals("power-key"))==Set.of("before-reply","delayed","power-key").contains(mode),"Wrong shutdown result: "+mode);
            }
            check(System.nanoTime()-began<TimeUnit.SECONDS.toNanos(4),"Shutdown wait unbounded");fixture.get(5,TimeUnit.SECONDS);
        }
    }
}
