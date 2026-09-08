package com.zhongbai233.mcandroidphone.phone;

import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** Client entry safe to reference from the common phone item. */
public final class PhoneClientHooks {
    private PhoneClientHooks() {}

    public static void openPhone(InteractionHand hand) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientOnly.openPhone(hand);
        }
    }

    private static final class ClientOnly {
        private static void openPhone(InteractionHand hand) {
            AndroidPhoneAdapter.openPhone(hand);
        }
    }
}
