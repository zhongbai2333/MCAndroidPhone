package com.zhongbai233.mcandroidphone.phone;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A second world pass from the rendered handset's lens. All resources are render-thread owned. */
public final class PhoneWorldCamera implements AutoCloseable {
    private static RenderTarget override;
    private final TextureTarget target=new TextureTarget("Android phone camera",640,480,true);
    private final CrossFrameResourcePool pool=new CrossFrameResourcePool(3);
    private final ProjectionMatrixBuffer projectionBuffer=new ProjectionMatrixBuffer("Android phone camera");
    private final GlobalSettingsUniform global=new GlobalSettingsUniform();
    private final LensCamera camera=new LensCamera();
    private FogRenderer fog;
    public static RenderTarget targetOverride(){return override;}

    private static final class LensCamera extends Camera {
        final Matrix4f projection=new Matrix4f();
        final Matrix4f view=new Matrix4f();
        Frustum frustum;
        void configure(Minecraft mc,Matrix4f handset,boolean front) {
            setLevel(mc.level);setEntity(mc.player);update(mc.getDeltaTracker());
            var main=mc.gameRenderer.getMainCamera();
            var world=new Matrix4f().rotate(main.rotation()).mul(handset);
            // Lens coordinates on the handset model; front is on the display side (+Z).
            var lens=world.transformPosition(new Vector3f(front?.16f:-.23f,.43f,front?.027f:-.027f));
            var rotation=world.getUnnormalizedRotation(new Quaternionf());if(front)rotation.rotateY((float)Math.PI);
            var euler=rotation.getEulerAnglesYXZ(new Vector3f());
            setRotation(180-(float)Math.toDegrees(euler.y),-(float)Math.toDegrees(euler.x),-(float)Math.toDegrees(euler.z));
            setPosition(main.position().add(lens.x,lens.y,lens.z));attributeProbe().tick(mc.level,position());
            view.rotation(rotation().conjugate(new Quaternionf()));
            projection.identity().perspective((float)Math.toRadians(60),640f/480f,.05f,Math.max(256,mc.options.getEffectiveRenderDistance()*64f));
            frustum=new Frustum(view,projection);frustum.prepare(position().x,position().y,position().z);
        }
        @Override public Matrix4f getViewRotationMatrix(Matrix4f dest){return dest.set(view);}
        @Override public Matrix4f getViewRotationProjectionMatrix(Matrix4f dest){return dest.set(projection).mul(view);}
        @Override public Frustum getCullFrustum(){return frustum==null?super.getCullFrustum():frustum;}
        @Override public boolean isDetached(){return true;}
        @Override public void extractRenderState(CameraRenderState state,float partial){super.extractRenderState(state,partial);state.projectionMatrix.set(projection);state.viewRotationMatrix.set(view);state.cullFrustum.set(frustum);}
    }
    RenderTarget render(Minecraft mc,Matrix4f handset,int facing) {
        var renderer=mc.levelRenderer;var game=mc.gameRenderer.getGameRenderState();var state=game.levelRenderState;
        var originalCamera=state.cameraRenderState;var oldProjection=RenderSystem.getProjectionMatrixBuffer();var oldType=RenderSystem.getProjectionType();
        var oldFog=RenderSystem.getShaderFog();var oldColor=RenderSystem.outputColorTextureOverride;var oldDepth=RenderSystem.outputDepthTextureOverride;
        var access=(com.zhongbai233.mcandroidphone.phone.mixin.CameraLevelRendererAccess)renderer;var outline=access.androidphone$getOutline();
        var delta=mc.getDeltaTracker();float partial=delta.getGameTimeDeltaPartialTick(false);
        try {
            if(fog==null)fog=new FogRenderer();camera.configure(mc,handset,facing==2);
            state.cameraRenderState=new CameraRenderState();camera.extractRenderState(state.cameraRenderState,partial);
            state.cameraRenderState.fogData=fog.setupFog(camera,mc.options.getEffectiveRenderDistance(),delta,mc.gameRenderer.getBossOverlayWorldDarkening(partial),mc.level);
            state.cameraRenderState.fogType=camera.getFluidInCamera();
            override=target;access.androidphone$setOutline(null);
            renderer.update(camera);renderer.extractLevel(delta,camera,partial);state.haveGlowingEntities=false;state.blockOutlineRenderState=null;
            var options=game.optionsRenderState;
            global.update(target.width,target.height,options.glintStrength,mc.level.getGameTime(),delta,options.menuBackgroundBlurriness,camera.position(),options.textureFiltering==TextureFilteringMethod.RGSS);
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(camera.projection),ProjectionType.PERSPECTIVE);
            fog.updateBuffer(state.cameraRenderState.fogData);
            renderer.renderLevel(pool,delta,false,state.cameraRenderState,camera.view,fog.getBuffer(FogRenderer.FogMode.WORLD),state.cameraRenderState.fogData.color,true,state.chunkSectionsToRender);
            return target;
        }finally {
            override=null;access.androidphone$setOutline(outline);state.reset();state.cameraRenderState=originalCamera;
            renderer.update(mc.gameRenderer.getMainCamera());
            mc.getEntityRenderDispatcher().prepare(mc.gameRenderer.getMainCamera(),mc.crosshairPickEntity);
            mc.getBlockEntityRenderDispatcher().prepare(originalCamera.pos);
            var options=game.optionsRenderState;var window=game.windowRenderState;
            mc.gameRenderer.getGlobalSettingsUniform().update(window.width,window.height,options.glintStrength,mc.level.getGameTime(),delta,options.menuBackgroundBlurriness,originalCamera.pos,options.textureFiltering==TextureFilteringMethod.RGSS);
            RenderSystem.setProjectionMatrix(oldProjection,oldType);RenderSystem.setShaderFog(oldFog);RenderSystem.outputColorTextureOverride=oldColor;RenderSystem.outputDepthTextureOverride=oldDepth;
            pool.endFrame();if(fog!=null)fog.endFrame();
        }
    }
    @Override public void close(){target.destroyBuffers();pool.close();projectionBuffer.close();global.close();if(fog!=null)fog.close();}
}
