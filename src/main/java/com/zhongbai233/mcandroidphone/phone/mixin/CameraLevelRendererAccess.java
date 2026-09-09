package com.zhongbai233.mcandroidphone.phone.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface CameraLevelRendererAccess {
    @Accessor("entityOutlineTarget") RenderTarget androidphone$getOutline();
    @Accessor("entityOutlineTarget") void androidphone$setOutline(RenderTarget target);
}
