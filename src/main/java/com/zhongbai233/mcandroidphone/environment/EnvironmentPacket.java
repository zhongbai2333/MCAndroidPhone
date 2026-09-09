package com.zhongbai233.mcandroidphone.environment;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Versioned host/guest wire contract. Java 8 compatible so the same source runs on ART. */
public final class EnvironmentPacket {
    public static final int MAGIC = 0x4d435045, VERSION = 1, MAX_BYTES = 512;
    public final long sequence, elapsedNanos;
    public final boolean available, locationValid, discontinuity;
    public final String dimension;
    // World position (blocks); virtual WGS84; device-to-ENU quaternion (xyzw);
    // device-frame accelerometer (m/s2), gyroscope (rad/s); hPa, lux, m/s, degrees.
    public final double x,y,z,latitude,longitude,altitude,qx,qy,qz,qw,ax,ay,az,gx,gy,gz,pressure,light,speed,bearing;

    public EnvironmentPacket(long sequence,long elapsedNanos,boolean available,boolean locationValid,
            boolean discontinuity,String dimension,double... v) {
        if(sequence<0 || elapsedNanos<0 || dimension==null || !dimension.matches("[a-z0-9_.:/-]{1,128}") || v.length!=20)
            throw new IllegalArgumentException("Invalid environment envelope");
        for(double value:v)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite environment value");
        double norm=v[6]*v[6]+v[7]*v[7]+v[8]*v[8]+v[9]*v[9];
        if(Math.abs(norm-1)>0.001 || Math.abs(v[3])>90 || Math.abs(v[4])>180 || v[16]<=0 || v[17]<0 || v[18]<0 || v[19]<0 || v[19]>=360)
            throw new IllegalArgumentException("Invalid environment units");
        this.sequence=sequence;this.elapsedNanos=elapsedNanos;this.available=available;
        this.locationValid=available&&locationValid;this.discontinuity=discontinuity;this.dimension=dimension;
        x=v[0];y=v[1];z=v[2];latitude=v[3];longitude=v[4];altitude=v[5];
        qx=v[6];qy=v[7];qz=v[8];qw=v[9];ax=v[10];ay=v[11];az=v[12];
        gx=v[13];gy=v[14];gz=v[15];pressure=v[16];light=v[17];speed=v[18];bearing=v[19];
    }
    public double[] values(){return new double[]{x,y,z,latitude,longitude,altitude,qx,qy,qz,qw,ax,ay,az,gx,gy,gz,pressure,light,speed,bearing};}
    public EnvironmentPacket unavailable(){return new EnvironmentPacket(sequence,elapsedNanos,false,false,true,dimension,values());}
    public byte[] encode()throws IOException {
        ByteArrayOutputStream payload=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(payload);
        out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(sequence);out.writeLong(elapsedNanos);
        out.writeBoolean(available);out.writeBoolean(locationValid);out.writeBoolean(discontinuity);
        byte[] name=dimension.getBytes(StandardCharsets.US_ASCII);out.writeInt(name.length);out.write(name);
        for(double v:values())out.writeDouble(v);
        ByteArrayOutputStream framed=new ByteArrayOutputStream();DataOutputStream frame=new DataOutputStream(framed);
        frame.writeInt(payload.size());payload.writeTo(frame);return framed.toByteArray();
    }
    public static EnvironmentPacket read(InputStream input)throws IOException {
        DataInputStream source=new DataInputStream(input);int size=source.readInt();
        if(size<192 || size>MAX_BYTES)throw new IOException("Invalid environment frame size");
        byte[] bytes=new byte[size];source.readFully(bytes);DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported environment protocol");
        long seq=in.readLong(),time=in.readLong();int activeFlag=in.readUnsignedByte(),gpsFlag=in.readUnsignedByte(),resetFlag=in.readUnsignedByte();
        if(activeFlag>1 || gpsFlag>1 || resetFlag>1)throw new IOException("Invalid environment flags");
        boolean active=activeFlag==1,gps=gpsFlag==1,reset=resetFlag==1;
        int length=in.readInt();if(length<1 || length>128 || size!=191+length)throw new IOException("Invalid dimension length");
        byte[] name=new byte[length];in.readFully(name);double[] v=new double[20];for(int i=0;i<v.length;i++)v[i]=in.readDouble();
        try{return new EnvironmentPacket(seq,time,active,gps,reset,new String(name,StandardCharsets.US_ASCII),v);}
        catch(IllegalArgumentException e){throw new IOException("Invalid environment frame",e);}
    }
}
