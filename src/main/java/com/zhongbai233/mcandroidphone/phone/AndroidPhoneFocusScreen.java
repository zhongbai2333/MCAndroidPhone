package com.zhongbai233.mcandroidphone.phone;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
/** Transparent input surface over the first-person phone. */
public final class AndroidPhoneFocusScreen extends Screen {
    public AndroidPhoneFocusScreen() { super(Component.translatable("item.mcandroidphone.android_phone")); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor graphics,int x,int y,float partial) { if(!AndroidPhoneWorldSmoke.extractTestHover())AndroidPhoneAdapter.hover(x,y); }
    @Override public void onClose() { PhoneProjection.clear(); super.onClose(); }
}
