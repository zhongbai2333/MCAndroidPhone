package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded local QMP transactions; guest shutdown confirmation is retained across command responses. */
final class QmpClient implements AutoCloseable {
    private final Socket socket=new Socket();
    private final InputStream input;
    private final OutputStream output;
    private final int timeout;
    private long id;
    private boolean guestShutdown;
    QmpClient(int port,int timeout)throws IOException {
        if(port<1||port>65535||timeout<1)throw new IllegalArgumentException("Invalid QMP endpoint");
        this.timeout=timeout;
        try {
            socket.connect(new InetSocketAddress("127.0.0.1",port),timeout);socket.setTcpNoDelay(true);
            input=new BufferedInputStream(socket.getInputStream());output=socket.getOutputStream();
            if(!(read(System.nanoTime()+timeout*1_000_000L).get("QMP") instanceof Map))throw new IOException("QMP greeting missing");
            execute("qmp_capabilities",null);
        }catch(IOException|RuntimeException e){socket.close();throw e;}
    }
    synchronized Object execute(String name,Map<String,?> args)throws IOException {
        if(!name.matches("[a-z][a-z0-9_-]*"))throw new IllegalArgumentException("Invalid QMP command");
        var request=new LinkedHashMap<String,Object>();request.put("execute",name);request.put("id",++id);
        if(args!=null)request.put("arguments",args);
        long deadline=System.nanoTime()+timeout*1_000_000L;
        try {
            output.write((Json.write(request)+"\n").getBytes(StandardCharsets.UTF_8));output.flush();
            while(true) {
                var response=read(deadline);
                observe(response);
                if(response.containsKey("event")||!Objects.equals(response.get("id"),id))continue;
                if(response.containsKey("error"))throw new IOException("QMP "+name+": "+response.get("error"));
                if(!response.containsKey("return"))throw new IOException("Missing QMP result");return response.get("return");
            }
        }catch(IOException e){close();throw e;}
    }
    private void observe(Map<String,Object> message) {
        if("SHUTDOWN".equals(message.get("event"))&&message.get("data") instanceof Map<?,?> data
                &&Boolean.TRUE.equals(data.get("guest"))&&"guest-shutdown".equals(data.get("reason")))guestShutdown=true;
    }
    synchronized boolean powerdown(String uuid,int graceMillis)throws IOException {
        return powerdown(uuid,graceMillis,false);
    }
    synchronized boolean powerdown(String uuid,int graceMillis,boolean powerKey)throws IOException {
        if(graceMillis<1||graceMillis>15000)throw new IllegalArgumentException("Invalid shutdown grace period");
        Object identity=execute("query-uuid",null);
        if(!(identity instanceof Map<?,?> map)||!uuid.equals(map.get("UUID")))throw new IOException("QEMU shutdown identity mismatch");
        try {
            if(powerKey)execute("send-key",Map.of("keys",List.of(Map.of("type","qcode","data","power")),"hold-time",1500));
            else execute("system_powerdown",null);
        }catch(IOException e){if(guestShutdown)return true;throw e;}
        long deadline=System.nanoTime()+graceMillis*1_000_000L;
        try {while(!guestShutdown)observe(read(deadline));}
        catch(SocketTimeoutException|EOFException e){return guestShutdown;}
        return true;
    }
    private Map<String,Object> read(long deadline)throws IOException {
        var bytes=new ByteArrayOutputStream();
        while(true) {
            long remaining=deadline-System.nanoTime();if(remaining<=0)throw new SocketTimeoutException("QMP response timeout");
            socket.setSoTimeout((int)Math.max(1,Math.min(Integer.MAX_VALUE,remaining/1_000_000L)));
            int c=input.read();if(c<0)throw new EOFException("QMP disconnected");if(c=='\n')return Json.object(bytes.toString(StandardCharsets.UTF_8));
            if(bytes.size()>=Json.LIMIT)throw new IOException("QMP line exceeds 1 MiB");bytes.write(c);
        }
    }
    @Override public void close(){try{socket.close();}catch(IOException ignored){}}
}
