package com.zhongbai233.mcandroidphone.phone;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Common item; identity follows the stack, independently of its inventory slot. */
public final class AndroidPhoneItem extends Item {
    public AndroidPhoneItem(Properties properties) { super(properties.stacksTo(1)); }
    public static UUID readDeviceId(ItemStack stack) {
        if (!(stack.getItem() instanceof AndroidPhoneItem)) return null;
        var data=stack.get(DataComponents.CUSTOM_DATA);
        if (data==null) return null;
        try { return UUID.fromString(data.copyTag().getString("androidphone_device_id").orElse("")); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    static void assignIdentity(ItemStack stack) {
        if (readDeviceId(stack)==null) writeIdentity(stack,UUID.randomUUID());
    }
    static void writeIdentity(ItemStack stack,UUID id) {
        stack.update(DataComponents.CUSTOM_DATA,CustomData.EMPTY,
            data->data.update(tag->tag.putString("androidphone_device_id",id.toString())));
    }
    @Override public InteractionResult use(Level level,Player player,InteractionHand hand) {
        if (hand!=InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level.isClientSide()) assignIdentity(player.getItemInHand(hand));
        else PhoneClientHooks.openPhone(hand);
        return InteractionResult.CONSUME;
    }
}
