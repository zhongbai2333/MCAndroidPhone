package com.zhongbai233.mcandroidphone.phone.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.mcandroidphone.phone.AndroidPhoneItem;
import com.zhongbai233.mcandroidphone.phone.AndroidPhoneAdapter;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Only the phone hand is replaced; other items keep their usual renderer. */
@Mixin(ItemInHandRenderer.class)
public abstract class AndroidPhoneHandRendererMixin {
    @Inject(method="renderArmWithItem",at=@At("HEAD"),cancellable=true)
    private void androidphone$render(AbstractClientPlayer player,float partial,float pitch,
            InteractionHand hand,float swing,ItemStack stack,float equip,PoseStack poses,
            SubmitNodeCollector collector,int light,CallbackInfo callback) {
        if (!(stack.getItem() instanceof AndroidPhoneItem)||hand!=InteractionHand.MAIN_HAND) return;
        if (!player.isInvisible()) {
            poses.pushPose();
            try {
                // Grip below the screen so the hand cannot cover Android navigation controls.
                poses.translate(player.getMainArm()==HumanoidArm.LEFT?-.055:.055,-.065,0);
                float held=AndroidPhoneAdapter.prepareHand(poses);
                androidphone$arm(poses,collector,light,equip*held,swing*held,player.getMainArm());
            }
            finally { poses.popPose(); }
        }
        AndroidPhoneAdapter.renderPhone(partial,equip,player.getMainArm()==HumanoidArm.LEFT,poses,collector);
        callback.cancel();
    }
    @Invoker("renderPlayerArm") protected abstract void androidphone$arm(PoseStack poses,
        SubmitNodeCollector collector,int light,float equip,float swing,HumanoidArm arm);
}
