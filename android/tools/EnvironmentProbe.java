package com.zhongbai233.mcandroidphone.guest;

import com.zhongbai233.mcandroidphone.environment.EnvironmentPacket;
import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.util.*;

/** Development-only app_process probe. Never included in the release image or mod JAR. */
public final class EnvironmentProbe {
    private static Object sensors,locations,listener,locationListener;
    private static Class<?> sensorClass,managerClass,locationClass;
    private static boolean enabled,provider;
    private static volatile long observed,observedLocation;
    private static final String PROVIDER="mcandroidphone";
    private static void command(String... argv)throws Exception {
        Process p=new ProcessBuilder(argv).redirectErrorStream(true).start();
        try(InputStream in=p.getInputStream()){byte[] b=new byte[4096];while(in.read(b)!=-1){}}
        if(p.waitFor()!=0)throw new IOException("Android test command failed: "+argv[0]);
    }
    private static Object invoke(Class<?> type,Object target,String method,Class<?>[] signature,Object... args)throws Exception {
        return type.getMethod(method,signature).invoke(target,args);
    }
    private static long clock()throws Exception{return (Long)Class.forName("android.os.SystemClock").getMethod("elapsedRealtimeNanos").invoke(null);}
    private static void initialize()throws Exception {
        Class<?> activity=Class.forName("android.app.ActivityThread"),contextType=Class.forName("android.content.Context");
        Object thread=activity.getMethod("systemMain").invoke(null);
        Object system=activity.getMethod("getSystemContext").invoke(thread);
        Object context=contextType.getMethod("createPackageContext",String.class,int.class).invoke(system,"com.android.shell",0);
        sensors=contextType.getMethod("getSystemService",String.class).invoke(context,"sensor");
        locations=contextType.getMethod("getSystemService",String.class).invoke(context,"location");
        managerClass=Class.forName("android.hardware.SensorManager");sensorClass=Class.forName("android.hardware.Sensor");
        locationClass=Class.forName("android.location.LocationManager");
        command("dumpsys","sensorservice","data_injection","com.android.shell");enabled=true;
        if(!Boolean.TRUE.equals(invoke(managerClass,sensors,"initDataInjection",new Class<?>[]{boolean.class},true)))
            throw new IOException("Android refused sensor test injection");
        Class<?> callbacks=Class.forName("android.hardware.SensorEventListener");
        listener=Proxy.newProxyInstance(callbacks.getClassLoader(),new Class<?>[]{callbacks},(proxy,method,args)->{
            if(method.getName().equals("onSensorChanged")) {
                Object event=args[0];float[] values=(float[])event.getClass().getField("values").get(event);
                Object sensor=event.getClass().getField("sensor").get(event);int type=(Integer)sensorClass.getMethod("getType").invoke(sensor);
                if(++observed%30==1)System.out.println("ANDROID_SENSOR_OBSERVED type="+type+" values="+Arrays.toString(values));
            }
            if(method.getName().equals("toString"))return "MCAndroidPhoneEnvironmentProbe";
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            return null;
        });
        for(int type:new int[]{1,4}) {
            Object sensor=invoke(managerClass,sensors,"getDefaultSensor",new Class<?>[]{int.class},type);
            if(sensor==null || !Boolean.TRUE.equals(invoke(managerClass,sensors,"registerListener",new Class<?>[]{callbacks,sensorClass,int.class},listener,sensor,50000)))
                throw new IOException("Sensor listener registration failed: "+type);
        }
        // The operator grants mock_location only to the shell in the disposable test VM.
        invoke(locationClass,locations,"addTestProvider",new Class<?>[]{String.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class,int.class,int.class},
            PROVIDER,false,true,false,false,true,true,true,1,1);provider=true;
        Class<?> locationCallbacks=Class.forName("android.location.LocationListener");
        locationListener=Proxy.newProxyInstance(locationCallbacks.getClassLoader(),new Class<?>[]{locationCallbacks},(proxy,method,args)->{
            if(method.getName().equals("onLocationChanged") && ++observedLocation%20==1)System.out.println("ANDROID_LOCATION_OBSERVED "+args[0]);
            if(method.getName().equals("toString"))return "MCAndroidPhoneLocationProbe";
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            return null;
        });
        invoke(locationClass,locations,"requestLocationUpdates",new Class<?>[]{String.class,long.class,float.class,locationCallbacks},PROVIDER,1000L,0f,locationListener);
    }
    private static void inject(EnvironmentPacket p)throws Exception {
        invoke(locationClass,locations,"setTestProviderEnabled",new Class<?>[]{String.class,boolean.class},PROVIDER,p.locationValid);
        if(!p.available)return;
        for(int type:new int[]{1,4}) {
            Object sensor=invoke(managerClass,sensors,"getDefaultSensor",new Class<?>[]{int.class},type);
            float[] values=type==1?new float[]{(float)p.ax,(float)p.ay,(float)p.az}:new float[]{(float)p.gx,(float)p.gy,(float)p.gz};
            if(!Boolean.TRUE.equals(invoke(managerClass,sensors,"injectSensorData",new Class<?>[]{sensorClass,float[].class,int.class,long.class},sensor,values,3,clock())))
                throw new IOException("Sensor injection failed: "+type);
        }
        if(p.locationValid) {
            Class<?> type=Class.forName("android.location.Location");Object location=type.getConstructor(String.class).newInstance(PROVIDER);
            invoke(type,location,"setLatitude",new Class<?>[]{double.class},p.latitude);
            invoke(type,location,"setLongitude",new Class<?>[]{double.class},p.longitude);
            invoke(type,location,"setAltitude",new Class<?>[]{double.class},p.altitude);
            invoke(type,location,"setAccuracy",new Class<?>[]{float.class},1f);
            invoke(type,location,"setSpeed",new Class<?>[]{float.class},(float)p.speed);
            invoke(type,location,"setBearing",new Class<?>[]{float.class},(float)p.bearing);
            invoke(type,location,"setTime",new Class<?>[]{long.class},System.currentTimeMillis());
            invoke(type,location,"setElapsedRealtimeNanos",new Class<?>[]{long.class},clock());
            invoke(locationClass,locations,"setTestProviderLocation",new Class<?>[]{String.class,type},PROVIDER,location);
        }
    }
    private static void cleanup() {
        try{if(provider)invoke(locationClass,locations,"removeTestProvider",new Class<?>[]{String.class},PROVIDER);}catch(Exception e){System.err.println(e);}
        try{if(enabled){invoke(managerClass,sensors,"initDataInjection",new Class<?>[]{boolean.class},false);command("dumpsys","sensorservice","enable");}}catch(Exception e){System.err.println(e);}
    }
    private static void run(String[] args) {
        int exit=0;
        try {
            // TCP is a test fallback for stock user images whose virtio node is root-only.
            boolean tcp=args.length>=3&&args[0].equals("--tcp");
            boolean inject=Arrays.asList(args).contains("--inject-test");
            Socket socket=tcp?new Socket(args[1],Integer.parseInt(args[2])):null;
            if(socket!=null)socket.setSoTimeout(5000);
            try(InputStream input=socket!=null?socket.getInputStream():new FileInputStream(args[0]);
                OutputStream output=socket!=null?socket.getOutputStream():new FileOutputStream(args[0])) {
                DataOutputStream out=new DataOutputStream(output);out.writeInt(EnvironmentPacket.MAGIC);out.writeInt(EnvironmentPacket.VERSION);out.flush();
                long deadline=System.nanoTime()+120_000_000_000L,lastSequence=-1,count=0;
                while(System.nanoTime()<deadline) {
                    EnvironmentPacket p;
                    try{p=EnvironmentPacket.read(input);}catch(EOFException e){if(count==0)throw e;break;}
                    if(p.sequence<lastSequence)throw new IOException("Out-of-order sample");lastSequence=p.sequence;
                    if(inject)inject(p);
                    if(++count%20==1)System.out.println("ENVIRONMENT_RECEIVED seq="+p.sequence+" available="+p.available+" dimension="+p.dimension+
                        " xyz="+p.x+","+p.y+","+p.z+" latlon="+p.latitude+","+p.longitude+" accel="+p.ax+","+p.ay+","+p.az+" pressure="+p.pressure);
                    out.writeLong(p.sequence);out.flush();
                }
                System.out.println("ENVIRONMENT_PROBE_OK frames="+count+" sensorCallbacks="+observed+" locationCallbacks="+observedLocation);
            }finally{if(socket!=null)socket.close();}
        }catch(Throwable e){e.printStackTrace();exit=1;}finally{cleanup();}
        System.exit(exit);
    }
    public static void main(String[] args)throws Exception {
        if(args.length==0)throw new IllegalArgumentException("EnvironmentProbe <virtio-node> [--inject-test] | --tcp host port [--inject-test]");
        Class<?> looper=Class.forName("android.os.Looper");looper.getMethod("prepareMainLooper").invoke(null);
        try{if(Arrays.asList(args).contains("--inject-test"))initialize();}catch(Throwable e){cleanup();throw e;}
        new Thread(()->run(args),"mcphone-environment-probe").start();looper.getMethod("loop").invoke(null);
    }
}
