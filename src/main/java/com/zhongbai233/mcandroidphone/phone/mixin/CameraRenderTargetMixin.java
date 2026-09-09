package com.zhongbai233.mcandroidphone.phone.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.zhongbai233.mcandroidphone.phone.PhoneWorldCamera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class CameraRenderTargetMixin {
    @Inject(method="getMainRenderTarget",at=@At("HEAD"),cancellable=true)
    private void androidphone$cameraTarget(CallbackInfoReturnable<RenderTarget> callback){var target=PhoneWorldCamera.targetOverride();if(target!=null)callback.setReturnValue(target);}
}
