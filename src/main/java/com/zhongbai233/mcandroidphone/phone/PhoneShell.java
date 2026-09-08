package com.zhongbai233.mcandroidphone.phone;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
/** A thin, solid body with equal bezels; its screen face is supplied by PhoneVideoLayer. */
final class PhoneShell {
    private static final Identifier BODY=Identifier.withDefaultNamespace("textures/block/gray_concrete.png");
    static void submit(PoseStack poses,SubmitNodeCollector collector,float x,float y) {
        collector.submitCustomGeometry(poses,PhoneRenderTypes.videoRgbaEntity(BODY),(pose,out)->{
            float front=.02f,back=-.025f;
            face(out,pose,new float[]{-x,y,front,-x,-y,front,x,-y,front,x,y,front},0xff080a0d);
            face(out,pose,new float[]{x,y,back,x,-y,back,-x,-y,back,-x,y,back},0xff555d68);
            face(out,pose,new float[]{-x,y,back,-x,-y,back,-x,-y,front,-x,y,front},0xff667080);
            face(out,pose,new float[]{x,y,front,x,-y,front,x,-y,back,x,y,back},0xff8893a5);
            face(out,pose,new float[]{-x,y,back,-x,y,front,x,y,front,x,y,back},0xffabb3c0);
            face(out,pose,new float[]{-x,-y,front,-x,-y,back,x,-y,back,x,-y,front},0xff414754);
        });
    }
    private static void face(VertexConsumer out,PoseStack.Pose pose,float[] p,int color) {
        for(int i=0;i<4;i++)out.addVertex(pose,p[i*3],p[i*3+1],p[i*3+2])
            .setColor(color).setUv(i<2?0:1,i==0||i==3?0:1).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(0x00f000f0).setNormal(pose,0,0,1);
    }
}
