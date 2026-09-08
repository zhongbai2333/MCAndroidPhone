package com.zhongbai233.mcandroidphone.phone;

import com.zhongbai233.mcandroidphone.MCAndroidPhone;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Stable phone item and creative tab registration. */
public final class PhoneItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCAndroidPhone.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB, MCAndroidPhone.MOD_ID);

    public static final DeferredItem<AndroidPhoneItem> ANDROID_PHONE = ITEMS.registerItem(
            "android_phone", AndroidPhoneItem::new, Item.Properties::new);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PHONES_TAB = TABS.register(
            "phones", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcandroidphone.phones"))
                    .icon(() -> new ItemStack(ANDROID_PHONE.get()))
                    .displayItems((parameters, output) -> output.accept(ANDROID_PHONE.get()))
                    .build());

    private PhoneItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        TABS.register(modBus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(PhoneItems::identities);
    }
    private static void identities(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        var player=event.getEntity();
        if (player.level().isClientSide()) return;
        var seen=new java.util.HashSet<java.util.UUID>();
        var inventory=player.getInventory();
        for (int i=0;i<inventory.getContainerSize();i++) {
            var stack=inventory.getItem(i);
            if (!(stack.getItem() instanceof AndroidPhoneItem)) continue;
            AndroidPhoneItem.assignIdentity(stack);
            if (!seen.add(AndroidPhoneItem.readDeviceId(stack))) {
                AndroidPhoneItem.writeIdentity(stack,java.util.UUID.randomUUID());
                seen.add(AndroidPhoneItem.readDeviceId(stack));
            }
        }
    }
}
