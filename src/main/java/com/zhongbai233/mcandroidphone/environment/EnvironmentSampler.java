package com.zhongbai233.mcandroidphone.environment;

/** Minecraft metres/ticks are converted at the edge; Android uses SI and device-local axes. */
public final class EnvironmentSampler {
    private long origin,previous,sequence;
    private String dimension;
    private double x,y,z,vx,vy,vz;
    private double[] orientation;

    public EnvironmentPacket sample(long now,String dim,double px,double py,double pz,double yaw,double pitch,
            double roll,double tiltX,double tiltY,int lightLevel,boolean available) {
        if(origin==0)origin=now;
        double dt=(now-previous)/1e9;
        boolean reset=previous==0 || !available || !dim.equals(dimension) || dt<=0 || dt>.5
            || Math.sqrt(square(px-x)+square(py-y)+square(pz-z))>32;
        double[] q=multiply(multiply(axis(2,yaw+180),axis(0,90-pitch)),
            multiply(multiply(axis(2,roll),axis(0,tiltX)),axis(1,tiltY)));
        double nx=reset?0:(px-x)/dt,ny=reset?0:(py-y)/dt,nz=reset?0:(pz-z)/dt;
        // Minecraft (east, up, south) -> ENU (east, north, up). Specific force includes gravity.
        double[] acceleration=rotate(conjugate(q),reset?0:(nx-vx)/dt,reset?0:-(nz-vz)/dt,9.80665+(reset?0:(ny-vy)/dt));
        double[] gyro={0,0,0};
        if(!reset && orientation!=null) {
            double[] delta=multiply(conjugate(orientation),q);if(delta[3]<0)for(int i=0;i<4;i++)delta[i]=-delta[i];
            double n=Math.sqrt(square(delta[0])+square(delta[1])+square(delta[2]));
            double scale=n<1e-9?2/dt:2*Math.atan2(n,delta[3])/(n*dt);
            for(int i=0;i<3;i++)gyro[i]=delta[i]*scale;
        }
        double latitude=Math.toDegrees(-pz/6378137.0),longitude=wrap(Math.toDegrees(px/6378137.0)+180)-180;
        boolean gps=available&&dim.equals("minecraft:overworld")&&Math.abs(latitude)<=85;
        // Virtual equatorial origin (0,0), 1 block = 1 m; sea level y=63. No real-world location.
        double altitude=py-63,pressure=1013.25*Math.exp(-altitude/8434.5);
        EnvironmentPacket packet=new EnvironmentPacket(++sequence,Math.max(0,now-origin),available,gps,reset,dim,
            px,py,pz,Math.max(-90,Math.min(90,latitude)),longitude,altitude,q[0],q[1],q[2],q[3],
            acceleration[0],acceleration[1],acceleration[2],gyro[0],gyro[1],gyro[2],pressure,
            Math.pow(Math.max(0,Math.min(15,lightLevel))/15.0,2)*10000,Math.hypot(nx,nz),wrap(Math.toDegrees(Math.atan2(nx,-nz))));
        previous=available?now:0;dimension=dim;x=px;y=py;z=pz;vx=nx;vy=ny;vz=nz;orientation=q;
        return packet;
    }
    private static double square(double x){return x*x;}
    private static double wrap(double x){return (x%360+360)%360;}
    private static double[] axis(int a,double degrees){double[] q={0,0,0,Math.cos(Math.toRadians(degrees)/2)};q[a]=Math.sin(Math.toRadians(degrees)/2);return q;}
    private static double[] conjugate(double[] q){return new double[]{-q[0],-q[1],-q[2],q[3]};}
    private static double[] multiply(double[] a,double[] b){return new double[]{
        a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],
        a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2]};}
    private static double[] rotate(double[] q,double x,double y,double z){double[] v=multiply(multiply(q,new double[]{x,y,z,0}),conjugate(q));return new double[]{v[0],v[1],v[2]};}
}
