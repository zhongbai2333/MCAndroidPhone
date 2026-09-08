package com.zhongbai233.mcandroidphone.phone;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.zhongbai233.mcandroidphone.core.GpuFrame;
import com.zhongbai233.mcandroidphone.gpu.D3d11Importer;
import net.minecraft.client.renderer.texture.AbstractTexture;

/** Own RGBA GPU storage for imported D3D11 scanouts. */
final class PhoneGpuTexture extends AbstractTexture {
    private int width, height;
    private final D3d11Importer importer = new D3d11Importer();
    @Override public void close() { importer.close(); content=null; super.close(); }
    private GpuFrame.Notice content;
    GpuFrame.Notice content() { return content; }
    boolean update(GpuFrame frame) {
        var n = frame.notice();
        if (texture == null || width != n.textureWidth() || height != n.textureHeight()) {
            close();
            width = n.textureWidth(); height = n.textureHeight();
            texture = RenderSystem.getDevice().createTexture("androidphone_gpu",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8, width, height, 1, 1);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            textureView = RenderSystem.getDevice().createTextureView(texture);
        }
        if (!(texture instanceof GlTexture gl)) throw new IllegalStateException("Phone D3D11 import requires Minecraft OpenGL");
        if (!importer.copyTo(frame, gl.glId())) return false;
        content = n;
        return true;
    }
}
