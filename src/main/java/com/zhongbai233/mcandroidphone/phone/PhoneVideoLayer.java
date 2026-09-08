package com.zhongbai233.mcandroidphone.phone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.mcandroidphone.core.BridgeClient;
import com.zhongbai233.mcandroidphone.core.Frame;
import com.zhongbai233.mcandroidphone.core.GpuFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** Owns the CPU/GPU frame cache separately from the visibility of the held item. */
final class PhoneVideoLayer implements AutoCloseable {
    private PhoneNv12Textures texture;
    private PhoneGpuTexture gpuTexture;
    private static final Identifier GPU_ID = Identifier.fromNamespaceAndPath("mcandroidphone", "dynamic/phone_gpu");
    private long sequence = -1;
    private int width = 1080, height = 1920;
    private long uploadedFrames, renderedFrames;
    private BridgeClient sourceClient;
    private long sourceEpoch = -1;
    private boolean sourceConnected;
    private PhoneGeometry.Fit fit = PhoneGeometry.phoneFit(PhoneGeometry.PHONE_SURFACE_ASPECT, width, height);

    PhoneGeometry.Fit fit() { return fit; }
    long uploadedFrames() { return uploadedFrames; }
    long renderedFrames() { return renderedFrames; }

    void update(BridgeClient client) {
        boolean connected = client != null && client.connected();
        try (Frame frame = !connected ? null : client.pollFrame();
             GpuFrame gpuFrame = !connected || !client.gpuTransport() ? null : client.pollGpuFrame()) {
            // Read the frame's immutable epoch: reconnect can race with the render thread.
            long epoch = gpuFrame != null ? gpuFrame.connectionEpoch() : frame != null ? frame.connectionEpoch() : client == null ? -1 : client.connectionEpoch();
            if (client != sourceClient || epoch != sourceEpoch || (!connected && sourceConnected)) {
                close();
                sourceClient = client;
                sourceEpoch = epoch;
            }
            sourceConnected = connected;
            if (texture == null) {
                Identifier y = Identifier.fromNamespaceAndPath("mcandroidphone", "dynamic/phone_y");
                Identifier uv = Identifier.fromNamespaceAndPath("mcandroidphone", "dynamic/phone_uv");
                texture = new PhoneNv12Textures(y, uv, y, "androidphone");
                texture.upload(new byte[] {16, 16, 16, 16, (byte) 128, (byte) 128}, 2, 2);
            }
            if (gpuFrame != null) {
                if (gpuTexture == null) {
                    gpuTexture = new PhoneGpuTexture();
                    Minecraft.getInstance().getTextureManager().register(GPU_ID, gpuTexture);
                }
                try {
                    if (gpuTexture.update(gpuFrame)) {
                        width = gpuFrame.notice().width(); height = gpuFrame.notice().height();
                        sequence = gpuFrame.notice().sequence();
                        uploadedFrames++;
                        if (uploadedFrames == 1) com.mojang.logging.LogUtils.getLogger().info(
                            "ANDROIDPHONE_GPU_FRAME: {}x{} D3D11 shared texture; CPU pixel copies=0; GPU cache copies=1", width, height);
                    }
                } catch (RuntimeException error) {
                    client.close();
                    com.mojang.logging.LogUtils.getLogger().error("Phone GPU import failed; use --qemu-gpu virtio for CPU compatibility", error);
                    close();
                    return;
                }
            }
            if (frame != null && (frame.sequence() != sequence || width != frame.width() || height != frame.height())) {
                if (texture.upload(frame.pixels(), frame.pixels().remaining(), frame.width(), frame.height())) {
                    width = frame.width();
                    height = frame.height();
                    sequence = frame.sequence();
                    uploadedFrames++;
                }
            }
        }
    }

    void submit(BridgeClient client,PoseStack poses,SubmitNodeCollector collector,
                float x0,float y0,float x1,float y1,float z0,float z1,float z2,float z3) {
        update(client);
        if (texture==null) return;
        fit = PhoneGeometry.phoneFit(Math.abs((x1 - x0) / (y1 - y0)), width, height);
        PhoneGeometry.Fit frameFit = fit;
        GpuFrame.Notice gpuContent = gpuTexture == null ? null : gpuTexture.content();
        collector.submitCustomGeometry(poses, gpuContent != null ? PhoneRenderTypes.videoRgbaEntity(GPU_ID) :
            PhoneRenderTypes.nv12Entity(texture.yId(), texture.uId(), texture.vId()),
            (pose, buffer) -> {
                renderedFrames++;
                // Keep a solid outer bezel; guest letterboxing stays inside the fixed portrait panel.
                quad(buffer, pose, x0, y0, x1, y1, z0, z1, z2, z3, 0, 0, 1, 1, .004f, 0xff000000, gpuContent);
                quad(buffer, pose, x0, y0, x1, y1, z0, z1, z2, z3,
                    (float) frameFit.left(), (float) frameFit.top(),
                    (float) (frameFit.left() + frameFit.width()), (float) (frameFit.top() + frameFit.height()),
                    .005f, 0xffffffff, gpuContent);
            });
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose, float x0, float y0, float x1, float y1,
                             float z0, float z1, float z2, float z3, float u0, float v0, float u1, float v1,
                             float lift, int color, GpuFrame.Notice image) {
        float left=0, right=1, top=0, bottom=1;
        if (image != null) {
            left = (float)image.x()/image.textureWidth();
            right = (float)(image.x()+image.width())/image.textureWidth();
            top = (float)image.y()/image.textureHeight();
            bottom = (float)(image.y()+image.height())/image.textureHeight();
            // ANGLE's D3D allocation has the opposite row convention from its GL scanout.
            if (image.topDown()) { top=1-top; bottom=1-bottom; }
        }
        // Both windings support either hand and preserve the surface's UV orientation.
        vertex(out, pose, x0, y0, x1, y1, z0, z1, z2, z3, u0, v0, left, top, lift, color);
        vertex(out, pose, x0, y0, x1, y1, z0, z1, z2, z3, u0, v1, left, bottom, lift, color);
        vertex(out, pose, x0, y0, x1, y1, z0, z1, z2, z3, u1, v1, right, bottom, lift, color);
        vertex(out, pose, x0, y0, x1, y1, z0, z1, z2, z3, u1, v0, right, top, lift, color);
        vertex(out, pose, x0, y0, x1, y1, z0, z1, z2, z3, u1, v0, right, top, lift, color);
        vertex(out, pose, x0, y0, x1, y1, z0, z1, z2, z3, u1, v1, right, bottom, lift, color);
        vertex(out, pose, x0, y0, x1, y1, z0, z1, z2, z3, u0, v1, left, bottom, lift, color);
        vertex(out, pose, x0, y0, x1, y1, z0, z1, z2, z3, u0, v0, left, top, lift, color);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x0, float y0, float x1, float y1,
                               float z0, float z1, float z2, float z3, float u, float v, float tu, float tv,
                               float lift, int color) {
        float z = (z0 + (z1 - z0) * v) * (1 - u) + (z3 + (z2 - z3) * v) * u + lift;
        out.addVertex(pose, x0 + (x1 - x0) * u, y0 + (y1 - y0) * v, z)
            .setColor(color).setUv(tu, tv).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(0x00f000f0).setNormal(pose, 0, 0, 1);
    }

    @Override public void close() {
        if (texture != null) { texture.close(); texture = null; }
        if (gpuTexture != null) { Minecraft.getInstance().getTextureManager().release(GPU_ID); gpuTexture = null; }
        sequence = -1;
        width = 1080; height = 1920;
        fit = PhoneGeometry.phoneFit(PhoneGeometry.PHONE_SURFACE_ASPECT, width, height);
        uploadedFrames = 0; renderedFrames = 0;
        sourceClient = null; sourceEpoch = -1; sourceConnected = false;
    }
}
