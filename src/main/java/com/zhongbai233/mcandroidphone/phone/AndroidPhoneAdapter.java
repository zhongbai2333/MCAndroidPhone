package com.zhongbai233.mcandroidphone.phone;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import com.zhongbai233.mcandroidphone.core.BridgeClient;
import com.zhongbai233.mcandroidphone.core.ManagedRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Client-only adapter initialized by the common mod after item registration is attached. */
public final class AndroidPhoneAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(id("main"));
    private static final KeyMapping TOGGLE = new KeyMapping("key.mcandroidphone.toggle",
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);
    private static final PhoneVideoLayer VIDEO = new PhoneVideoLayer();
    static final PhonePose POSE = new PhonePose();
    private static BridgeClient client;
    private static UUID device;
    private static ManagedRuntime runtime;
    private static java.util.concurrent.CompletableFuture<Path> boot;
    private static boolean runtimeFailureReported;

    private static int pendingTicks;
    private static boolean openFocus;
    private static boolean touchDown;
    private static double lastU, lastV;
    private static int smokeTitleTicks, missingTicks;

    public AndroidPhoneAdapter(IEventBus modBus) {
        modBus.addListener(AndroidPhoneAdapter::registerKeys);
        modBus.addListener(PhoneRenderTypes::register);
        modBus.addListener(AndroidPhoneAdapter::registerReload);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::commands);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::tick);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::mouseDown);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::mouseDrag);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::mouseUp);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::mouseScroll);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::key);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::character);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::screenClosing);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::screenRender);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::logout);
        NeoForge.EVENT_BUS.addListener(AndroidPhoneAdapter::shutdown);
        LOGGER.info("MC Android Phone ready; right-click the Android Phone to connect");
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("mcandroidphone", path); }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY); event.register(TOGGLE);
    }

    private static void registerReload(AddClientReloadListenersEvent event) {
        event.addListener(id("phone_textures"), (ResourceManagerReloadListener) resources -> {
            // TextureManager retains non-reloadable dynamic textures across resource reloads.
            // Keep the static GPU scanout cache; a guest need not repaint after F3+T.
            releaseTouch(); PhoneProjection.clear();
        });
    }

    private static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("androidphone")
            .executes(ctx -> status())
            .then(literal("connect").executes(ctx -> connect()))
            .then(literal("disconnect").executes(ctx -> { stop(); tell("安卓手机已断开。"); return 1; }))
            .then(literal("status").executes(ctx -> status()))
            .then(literal("poweroff").executes(ctx -> { poweroff(); tell("安卓已关机。"); return 1; }))
            .then(literal("runtime").executes(ctx -> { tell(runtime == null ? "当前没有由 Mod 启动的运行环境。" :
                runtime.status()+"；日志："+runtime.sessionDirectory()); return 1; }))
            .then(literal("back").executes(ctx -> androidKey("BACK")))
            .then(literal("home").executes(ctx -> androidKey("HOME")))
            .then(literal("recent").executes(ctx -> androidKey("APP_SWITCH")))
            .then(literal("text").then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                if (client == null) { tell("请先 /androidphone connect。"); return 0; }
                client.text(StringArgumentType.getString(ctx, "text")); return 1;
            }))));
    }

    public static void openPhone(InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !isPhone(mc.player.getMainHandItem())) return;
        if (client != null && matches(mc.player.getMainHandItem())) {
            openPhoneFocus();
        } else {
            connect();
        }
    }

    private static boolean isPhone(ItemStack stack) { return stack.getItem() instanceof AndroidPhoneItem; }

    private static void openPhoneFocus() {
        Minecraft.getInstance().setScreen(new AndroidPhoneFocusScreen());
    }

    private static int connect() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !isPhone(mc.player.getMainHandItem())) {
            tell("请从“安卓手机”创造栏拿出手机，主手右键使用；也可 /give @s mcandroidphone:android_phone。"); return 0;
        }
        stop();
        pendingTicks = 200;
        openFocus = true;
        return 1;
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        AndroidPhoneWorldSmoke.tick(mc);
        if(runtime!=null && runtime.state()==ManagedRuntime.State.FAILED && !runtimeFailureReported) {
            runtimeFailureReported=true;stop();tell("安卓启动/运行失败："+runtime.status());
        }
        if(boot!=null && boot.isDone()) {
            var pending=boot;boot=null;
            try { client=new BridgeClient(pending.join());client.start(); }
            catch(java.util.concurrent.CompletionException | java.util.concurrent.CancellationException error) {
                if(!runtimeFailureReported) {runtimeFailureReported=true;stop();tell("安卓启动失败："+(runtime==null?error:runtime.status()));}
            }
        }
        if (Boolean.getBoolean("mcandroidphone.smoke") && !Boolean.getBoolean("mcandroidphone.worldSmoke")
                && mc.screen instanceof TitleScreen && ++smokeTitleTicks == 20) {
            try {
                // Force the target through Mixin even though the smoke run does not enter a world.
                Class.forName("net.minecraft.client.renderer.ItemInHandRenderer");
                if (!(PhoneItems.ANDROID_PHONE.get() instanceof AndroidPhoneItem)
                        || PhoneItems.ANDROID_PHONE.get().getDefaultInstance().getMaxStackSize() != 1)
                    throw new IllegalStateException("Android phone item registration failed");
                LOGGER.info("ANDROIDPHONE_SMOKE_OK: title screen reached; dedicated phone registered; renderer Mixins loaded");
            } catch (ClassNotFoundException e) { throw new IllegalStateException("Phone renderer missing", e); }
            mc.stop();
        }
        while (TOGGLE.consumeClick()) {
            if (mc.screen == null && mc.player != null && isPhone(mc.player.getMainHandItem())) {
                if (client != null || boot != null || pendingTicks > 0) { stop(); tell("安卓手机已断开。"); }
                else connect();
            }
        }
        if (pendingTicks > 0) {
            if (mc.player == null || !isPhone(mc.player.getMainHandItem())) { stop(); return; }
            // Defer until chat has finished closing; otherwise Minecraft overwrites the new focus screen.
            if (openFocus) {
                openFocus = false;
                openPhoneFocus();
            }
            device = AndroidPhoneItem.readDeviceId(mc.player.getMainHandItem());
            if (device != null) {
                pendingTicks = 0;
                String external=System.getProperty("mcandroidphone.config","");
                if(!external.isBlank()) {client=new BridgeClient(Path.of(external));client.start();}
                else boot=startRuntime(mc);
            } else if (--pendingTicks == 0) {
                stop();
                tell("手机未获得设备 ID；确认客户端和服务器都装好 MCAndroidPhone 后重试。");
            }
        }
        if (client == null && boot == null) return;
        if (mc.player == null) { stop(); return; }
        boolean owned=matches(mc.player.containerMenu.getCarried());
        for (int i=0;!owned && i<mc.player.getInventory().getContainerSize();i++)
            owned=matches(mc.player.getInventory().getItem(i));
        if (owned) missingTicks=0;
        else if (++missingTicks>=40) { stop(); return; }
        if (!matches(mc.player.getMainHandItem())) {
            releaseTouch(); PhoneProjection.clear();
            if (mc.screen instanceof AndroidPhoneFocusScreen) mc.screen.onClose();
            // Refresh the cache and acknowledge GPU leases at 20 Hz while stowed.
            try { VIDEO.update(client); }
            catch(RuntimeException error) { LOGGER.error("Stowed phone texture update failed",error);stop(); }
            return;
        }
        if (!(mc.screen instanceof AndroidPhoneFocusScreen) || !mc.isWindowActive()) {POSE.finish();POSE.hover(null);releaseTouch();}
        if(client==null || !client.connected())releaseTouch();

    }

    private static boolean matches(ItemStack stack) {
        return device != null && isPhone(stack) && device.equals(AndroidPhoneItem.readDeviceId(stack));
    }

    private static boolean focused(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        // Own input while identity and the bridge connection are pending.
        return screen instanceof AndroidPhoneFocusScreen
            && mc.player != null && isPhone(mc.player.getMainHandItem());
    }

    private static PhoneGeometry.Point point(Screen screen, double x, double y, boolean captured) {
        if (!PhoneProjection.hasProjectedQuad(screen.width, screen.height)) return null;
        double[] qx = new double[4], qy = new double[4];
        for (int i = 0; i < 4; i++) { qx[i] = PhoneProjection.projectedQuadX(i); qy[i] = PhoneProjection.projectedQuadY(i); }
        return VIDEO.fit().map(PhoneGeometry.unproject(qx, qy, x, y), captured);
    }

    static void hover(double x,double y) {
        var screen=Minecraft.getInstance().screen;
        if(screen==null || !focused(screen) || POSE.dragging()) {POSE.hover(null);return;}
        POSE.hover(point(screen,x,y,false));
    }

    private static void mouseDown(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!focused(event.getScreen())) return;
        event.setCanceled(true);
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { androidKey("BACK"); return; }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) { androidKey("HOME"); return; }
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        var screen=event.getScreen();
        if(PhoneProjection.hasProjectedQuad(screen.width,screen.height) &&
                PhoneProjection.onRotationBorder(event.getMouseX(),event.getMouseY())) {
            releaseTouch();
            var pivot=PhoneProjection.projectLocal(PhonePose.PIVOT_U,PhonePose.PIVOT_V);
            if(pivot!=null)POSE.begin(event.getMouseX(),event.getMouseY(),pivot.u(),pivot.v(),System.nanoTime());
            return;
        }
        if(client == null || !client.connected())return;
        var p = point(event.getScreen(), event.getMouseX(), event.getMouseY(), false);
        if (p == null) return;
        releaseTouch(); touchDown = true; lastU = p.u(); lastV = p.v();
        client.touch("DOWN", lastU, lastV);
    }

    private static void mouseDrag(ScreenEvent.MouseDragged.Pre event) {
        if (!focused(event.getScreen())) return;
        event.setCanceled(true);
        if(POSE.dragging() && event.getMouseButton()==GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            POSE.drag(event.getMouseX(),event.getMouseY(),System.nanoTime());return;
        }
        if (!touchDown || client == null || event.getMouseButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        var p = point(event.getScreen(), event.getMouseX(), event.getMouseY(), true);
        if (p == null) { releaseTouch(); return; }
        lastU = p.u(); lastV = p.v(); client.touch("MOVE", lastU, lastV);
    }

    private static void mouseUp(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!focused(event.getScreen())) return;
        event.setCanceled(true);
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {POSE.finish();releaseTouch();}
    }

    private static void mouseScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (focused(event.getScreen())) event.setCanceled(true);
    }

    private static void key(ScreenEvent.KeyPressed.Pre event) {
        if (!focused(event.getScreen())) return;
        int code = event.getKeyCode();
        if (code == GLFW.GLFW_KEY_ESCAPE) { releaseTouch(); return; }
        if (TOGGLE.matches(event.getKeyEvent())) { stop(); tell("安卓手机已断开。"); event.setCanceled(true); return; }
        if (code == GLFW.GLFW_KEY_HOME) androidKey("HOME");
        else if (code == GLFW.GLFW_KEY_END) androidKey("APP_SWITCH");
        else if (code == GLFW.GLFW_KEY_BACKSPACE) androidKey("BACK");
        // Printable key events are followed by CharacterTyped, which sends Unicode text separately.
        event.setCanceled(true);
    }

    private static void character(ScreenEvent.CharacterTyped.Pre event) {
        if (!focused(event.getScreen())) return;
        event.setCanceled(true);
        if (client != null && !Character.isISOControl(event.getCodePoint()))
            client.text(new String(Character.toChars(event.getCodePoint())));
    }

    private static int androidKey(String key) {
        if (client == null) { tell("请先 /androidphone connect。"); return 0; }
        releaseTouch(); client.key(key); return 1;
    }

    private static void releaseTouch() {
        if (!touchDown) return;
        touchDown = false;
        if (client != null) client.touch("UP", lastU, lastV);
    }

    private static void screenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof AndroidPhoneFocusScreen) {POSE.finish();POSE.hover(null);releaseTouch();}
    }

    private static void screenRender(ScreenEvent.Render.Post event) {
        if (!focused(event.getScreen())) return;
        var font = Minecraft.getInstance().font;
        int width = event.getScreen().width, height = event.getScreen().height;
        String connection = client == null ? (boot!=null && runtime!=null ? runtime.status() : "等待手机设备 ID…") : client.connected() ? (VIDEO.uploadedFrames()==0 ? "安卓正在开机 / 等待画面…" : "已连接") : client.status();
        String status = font.plainSubstrByWidth("安卓手机 · " + connection, Math.max(1, width - 16));
        event.getGuiGraphics().text(font, Component.literal(status), 8, 8, 0xffd8e8ff);
        var help = phoneHelpLines(width);
        for (int i = 0; i < help.size(); i++)
            event.getGuiGraphics().text(font, help.get(i), 8, height - 8 - help.size() * 10 + i * 10,
                0xffb9c8dc, true);
    }

    private static List<FormattedCharSequence> phoneHelpLines(int width) {
        return Minecraft.getInstance().font.split(Component.literal(
            "左键触控 · 拖动外缘旋转 · 右键返回 · Home桌面 · End最近 · Esc退出 · F8断开"), Math.max(1, width - 16));
    }

    private static int status() {
        tell(client == null ? (boot!=null && runtime!=null ? runtime.status() : pendingTicks > 0 ? "等待手机设备 ID。" : "安卓手机未连接。") : client.status()); return 1;
    }

    private static void tell(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
        else LOGGER.info(text);
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { poweroff(); }
    private static void shutdown(GameShuttingDownEvent event) { poweroff(); }

    private static java.util.concurrent.CompletableFuture<Path> startRuntime(Minecraft mc) {
        if(runtime==null || runtime.state()==ManagedRuntime.State.FAILED || runtime.state()==ManagedRuntime.State.STOPPED) {
            if(runtime!=null)runtime.close();
            var options=new java.util.HashMap<String,String>();
            for(String name:java.util.List.of("root","backend","gpu","python","qemu","iso","disk","diskFormat",
                "kernel","initrd","ffmpeg","angle","width","height","density","memory","cpus","accel","guestArch","display","firmware","kernelAppend","input","colorOrder","bios")) {
                String property="mcandroidphone.runtime"+Character.toUpperCase(name.charAt(0))+name.substring(1);
                String value=System.getProperty(property,"");if(!value.isBlank())options.put(name,value);
            }
            runtime=new ManagedRuntime(mc.gameDirectory.toPath(),options,
                ()->AndroidPhoneAdapter.class.getResourceAsStream("/mcandroidphone/runtime/bridge.zip"));
            runtimeFailureReported=false;
        }
        return runtime.start();
    }
    private static void poweroff() {
        stop();
        if(runtime!=null) {runtime.close();runtime=null;}
    }
    static boolean managedRuntimeActive(){return runtime!=null && runtime.state()==ManagedRuntime.State.READY;}

    private static void stop() {
        releaseTouch(); POSE.reset(); boot=null; missingTicks=0; PhoneProjection.clear(); pendingTicks = 0; openFocus = false; device = null;
        if (client != null) { client.close(); client = null; }
        VIDEO.close();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AndroidPhoneFocusScreen) mc.screen.onClose();
    }

    public static float prepareHand(PoseStack poses) {
        var mc=Minecraft.getInstance();
        POSE.advance(focused(mc.screen),System.nanoTime());
        // MP4's small grip-depth response follows the same smoothed hover state.
        poses.translate(0,0,(-POSE.tiltY()*.85-POSE.tiltX()*.65)*.055/4);
        return (float)(1-POSE.focus());
    }

    public static void renderPhone(float partialTick,float equip,boolean leftHand,
                                    PoseStack poses,SubmitNodeCollector collector) {
        var mc=Minecraft.getInstance();
        if (mc.player==null) return;
        var window=mc.getWindow();
        boolean focus=focused(mc.screen);
        poses.pushPose();
        try {
            POSE.advance(focus,System.nanoTime());
            var layout=PhoneGeometry.focusedHand(window.getWidth()/(double)Math.max(1,window.getHeight()),
                window.getGuiScaledWidth(),window.getGuiScaledHeight(),24,
                phoneHelpLines(window.getGuiScaledWidth()).size()*10+16,POSE.rotation());
            double f=POSE.focus();
            poses.translate((leftHand?-1:1)*(.585+(layout.x()-.585)*f),
                (-.025-equip*.85)*(1-f)+layout.y()*f,-1.08+(layout.z()+1.08)*f);
            float scale=(float)(1.48+(layout.scale()-1.48)*f);poses.scale(scale,scale,scale);
            poses.scale(.532f,.532f,.532f);
            float halfWidth=9f/32+PhoneGeometry.PHONE_BEZEL;
            float halfHeight=.5f+PhoneGeometry.PHONE_BEZEL;
            float px=(float)((PhonePose.PIVOT_U*2-1)*halfWidth),py=(float)((1-PhonePose.PIVOT_V*2)*halfHeight);
            // MP4 grip transform: shift left, then rotate around the lower grip.
            // Re-centering after rotation would cancel the vertical grip motion and float the device.
            poses.translate(-POSE.landscapeShift(),0,0);
            poses.translate(px,py,0);
            poses.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float)POSE.rotation()));
            poses.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float)POSE.tiltX()));
            poses.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float)POSE.tiltY()));
            poses.translate(-px,-py,0);
            if (focus) PhoneProjection.publish(poses.last().pose(),-halfWidth,halfHeight,halfWidth,-halfHeight,.025f);
            PhoneShell.submit(poses,collector,halfWidth,halfHeight);
            if (client!=null && !matches(mc.player.getMainHandItem())) return;
            VIDEO.submit(client,poses,collector,
                -halfWidth,halfHeight,halfWidth,-halfHeight,.02f,.02f,.02f,.02f);
        } catch (RuntimeException error) {
            LOGGER.error("Android phone texture upload failed",error); stop();
        } finally { poses.popPose(); }
    }
    static long connectionEpoch() { return client==null?-1:client.connectionEpoch(); }
    static Object connectionIdentity() { return client; }
    static boolean bridgeConnected() { return client != null && client.connected(); }
    static long uploadedFrames() { return VIDEO.uploadedFrames(); }
    static long renderedFrames() { return VIDEO.renderedFrames(); }
}
