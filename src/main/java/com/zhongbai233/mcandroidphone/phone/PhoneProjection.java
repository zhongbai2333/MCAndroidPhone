package com.zhongbai233.mcandroidphone.phone;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector4f;
/** Display and touch use the same transformed face; stale projections cannot receive taps. */
final class PhoneProjection {
    private static final double[] X=new double[4],Y=new double[4];
    private static int width,height;
    private static long updated;
    static void clear() { updated=0; }
    static boolean hasProjectedQuad(int w,int h) {
        return updated!=0 && System.nanoTime()-updated<250_000_000L && w==width && h==height;
    }
    static double projectedQuadX(int i) { return X[i]; }
    static double projectedQuadY(int i) { return Y[i]; }
    static PhoneGeometry.Point projectLocal(double u,double v) {return PhoneGeometry.project(X,Y,u,v);}
    static boolean onRotationBorder(double x,double y) {
        var p=PhoneGeometry.unproject(X,Y,x,y);
        if(p==null)return false;
        if(p.u()>=0 && p.u()<=1 && p.v()>=0 && p.v()<=1)return false;
        // Distance to real projected edges, not an axis-aligned bounding rectangle.
        for(int i=0;i<4;i++) {
            int j=(i+1)%4;double dx=X[j]-X[i],dy=Y[j]-Y[i],d=dx*dx+dy*dy;
            double t=d==0?0:Math.max(0,Math.min(1,((x-X[i])*dx+(y-Y[i])*dy)/d));
            if(Math.hypot(x-X[i]-t*dx,y-Y[i]-t*dy)<=12)return true;
        }
        return false;
    }
    static void publish(Matrix4f model,float x0,float y0,float x1,float y1,float z) {
        var window=Minecraft.getInstance().getWindow();
        width=window.getGuiScaledWidth(); height=window.getGuiScaledHeight();
        var projection=new Matrix4f().perspective((float)Math.toRadians(70),
            window.getWidth()/(float)Math.max(1,window.getHeight()),.05f,
            net.minecraft.client.renderer.GameRenderer.PROJECTION_3D_HUD_Z_FAR);
        for (int i=0;i<4;i++) {
            var p=new Vector4f(i==0||i==3?x0:x1,i<2?y0:y1,z,1)
                .mul(model).mul(RenderSystem.getModelViewMatrix()).mul(projection);
            if (p.w<=0 || !Float.isFinite(p.w) || !Float.isFinite(p.x) || !Float.isFinite(p.y)) { clear(); return; }
            X[i]=(p.x/p.w*.5+.5)*width; Y[i]=(.5-p.y/p.w*.5)*height;
        }
        updated=System.nanoTime();
    }
}
