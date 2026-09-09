package com.zhongbai233.mcandroidphone.phone;

/** Presentation pose; the optional environment sampler publishes its rotation to Android. */
public final class PhonePose {
    public static final double PIVOT_U=.58, PIVOT_V=.88;
    private double hoverX,hoverY,targetX,targetY,focus,rotation,targetRotation;
    private double startPointer,startRotation,lastPointer,velocity,pivotX,pivotY;
    private long previous,dragTime;
    private boolean dragging;
    public void hover(PhoneGeometry.Point local) {
        targetX=local==null?0:clamp(local.u()*2-1,-1,1);
        targetY=local==null?0:clamp(local.v()*2-1,-1,1);
    }
    public void advance(boolean focused,long now) {
        double dt=previous==0?0:clamp((now-previous)/1e9,0,.1); previous=now;
        double a=1-Math.exp(-24*dt);
        focus+=( (focused?1:0)-focus)*a;
        hoverX+=((focused?targetX:0)-hoverX)*a;
        hoverY+=((focused?targetY:0)-hoverY)*a;
        if(!dragging)rotation+=(targetRotation-rotation)*(1-Math.exp(-15*dt));
    }
    public double focus(){return focus;}
    public double tiltX(){return hoverY*4*focus;}
    public double tiltY(){return hoverX*4*focus;}
    public double rotation(){return rotation;}
    public double landscapeShift(){double t=clamp(Math.abs(rotation)/90,0,1);return .5*t*t*(3-2*t);}
    public boolean dragging(){return dragging;}
    public void begin(double x,double y,double pivotX,double pivotY,long now) {
        this.pivotX=pivotX;this.pivotY=pivotY;
        startPointer=lastPointer=angle(x,y);startRotation=rotation;velocity=0;dragTime=now;dragging=true;
    }
    public void drag(double x,double y,long now) {
        if(!dragging)return;
        double pointer=angle(x,y),dt=Math.max(.001,(now-dragTime)/1e9);
        double delta=-normalize(pointer-startPointer);
        rotation=clamp(startRotation+delta,-105,15);
        velocity=velocity*.65+(-normalize(pointer-lastPointer)/dt)*.35;
        lastPointer=pointer;dragTime=now;
    }
    public void finish(){if(!dragging)return;dragging=false;targetRotation=rotation+clamp(velocity*.08,-18,18)<-45?-90:0;}
    public void reset(){hoverX=hoverY=targetX=targetY=focus=rotation=targetRotation=0;previous=0;dragging=false;}
    private double angle(double x,double y){return Math.toDegrees(Math.atan2(y-pivotY,x-pivotX));}
    private static double normalize(double a){return ((a+180)%360+360)%360-180;}
    private static double clamp(double a,double lo,double hi){return Math.max(lo,Math.min(hi,a));}
}
