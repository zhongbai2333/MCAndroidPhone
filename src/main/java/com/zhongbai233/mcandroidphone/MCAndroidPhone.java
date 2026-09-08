package com.zhongbai233.mcandroidphone;

import com.zhongbai233.mcandroidphone.phone.AndroidPhoneAdapter;
import com.zhongbai233.mcandroidphone.phone.PhoneItems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** Common entry point: the phone item exists on the client and server. */
@Mod(MCAndroidPhone.MOD_ID)
public final class MCAndroidPhone {
    public static final String MOD_ID = "mcandroidphone";

    public MCAndroidPhone(IEventBus modBus) {
        PhoneItems.register(modBus);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientOnly.init(modBus);
        }
    }

    /** Keep Minecraft client classes out of dedicated-server class loading. */
    private static final class ClientOnly {
        private static void init(IEventBus modBus) {
            new AndroidPhoneAdapter(modBus);
        }
    }
}
