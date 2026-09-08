package com.zhongbai233.mcandroidphone.phone;

import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit development smoke: a fresh world, real item use, real texture upload and draw, then a PNG. */
final class AndroidPhoneWorldSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private enum Stage { NEW, JOINING, EQUIPPING, FRAMES, STOWED, REEQUIPPED, RELOADING, SCREENSHOT, DONE }
    private static Stage stage = Stage.NEW;
    private static int titleTicks;
    private static long startupNanos;
    private static Object originalConnection;
    private static long originalEpoch, stowUntil, resumeDraws;
    private static boolean stowStarted;
    private static long reequippedAt;
    private static CompletableFuture<Path> focusScreenshot;
    private static CompletableFuture<Void> reload;
    private static long reloadDraws;
    private static CompletableFuture<Void> move;

    private static long deadlineNanos;
    private static long uploadedBeforeUse;
    private static long drawnBeforeUse;
    private static long loadedNanos;
    private static long usedNanos;
    private static long inputSettledNanos;
    private static boolean patternInputPosted;
    private static int poseStep;
    private static long poseUntil;
    private static CompletableFuture<Path> tiltedScreenshot;
    private static long nextStatusNanos;
    private static String worldId;
    private static Path screenshotPath;
    private static CompletableFuture<Void> equip;
    private static CompletableFuture<Path> screenshot;
    private static CameraType originalCamera;
    private static boolean originalHideGui;

    private AndroidPhoneWorldSmoke() {}

    static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("mcandroidphone.worldSmoke") || stage == Stage.DONE) return;
        try {
            tickChecked(mc);
        } catch (Exception failure) {
            Stage failedStage = stage;
            stage = Stage.DONE;
            restoreView(mc);
            LOGGER.error("ANDROIDPHONE_WORLD_SMOKE_FAILED: stage={} world={} uploaded={} drawn={}",
                    failedStage, worldId, AndroidPhoneAdapter.uploadedFrames(), AndroidPhoneAdapter.renderedFrames(), failure);
            mc.stop();
            throw new IllegalStateException("Android phone world smoke failed at " + failedStage, failure);
        }
    }

    private static void tickChecked(Minecraft mc) throws Exception {
        if (stage == Stage.NEW) {
            if(startupNanos==0)startupNanos=System.nanoTime();
            if(System.nanoTime()-startupNanos>TimeUnit.SECONDS.toNanos(240))
                throw new IllegalStateException("Startup did not reach title: "+(mc.screen==null?"none":mc.screen.getClass().getName()));
            if(mc.getOverlay()==null && mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) {
                LOGGER.info("ANDROIDPHONE_WORLD_SMOKE_ONBOARDING: continuing fresh-install accessibility page");
                mc.screen.onClose();return;
            }
            if (mc.player != null || mc.level != null || mc.getSingleplayerServer() != null)
                throw new IllegalStateException("Refusing smoke inside an already open world");
            if (!(mc.screen instanceof TitleScreen) || ++titleTicks < 20) return;
            for (String id : new String[]{"net_music_can_play_bili","netmusic","sceneeditor"})
                if(net.neoforged.fml.ModList.get().isLoaded(id))throw new IllegalStateException("Standalone smoke loaded companion mod "+id);
            worldId = "mcandroidphone-smoke-" + UUID.randomUUID();
            Path saves = mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve("saves");
            Path world = saves.resolve(worldId).normalize();
            if (!world.startsWith(saves) || Files.exists(world))
                throw new IllegalStateException("Smoke world path must be new: " + world);
            String forcedScreenshot = System.getProperty("mcandroidphone.smokeScreenshot", "");
            screenshotPath = forcedScreenshot.isBlank()
                    ? mc.gameDirectory.toPath().toAbsolutePath().resolve(".runtime").resolve(worldId + ".png")
                    : Path.of(forcedScreenshot);
            if (!screenshotPath.isAbsolute()) throw new IllegalArgumentException("smokeScreenshot must be an absolute path");
            screenshotPath = screenshotPath.normalize();
            if (!Files.isDirectory(screenshotPath.getParent()))
                throw new IllegalStateException("Create the smoke screenshot directory before launching: " + screenshotPath.getParent());
            if (Files.exists(screenshotPath)) throw new IllegalStateException("Refusing to overwrite screenshot: " + screenshotPath);
            stage = Stage.JOINING;
            deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
            LOGGER.info("ANDROIDPHONE_WORLD_SMOKE_START: newWorld={} screenshot={}", world, screenshotPath);
            mc.createWorldOpenFlows().createFreshLevel(worldId,
                    new LevelSettings(worldId, GameType.CREATIVE,
                            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                            true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(1L, false, false), WorldPresets::createFlatWorldDimensions, mc.screen);
            return;
        }

        long now = System.nanoTime();
        if (now > deadlineNanos)
            throw new IllegalStateException("Timed out waiting for " + stage + "; bridgeConnected="
                    + AndroidPhoneAdapter.bridgeConnected() + "; screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName()));
        if (now >= nextStatusNanos) {
            nextStatusNanos = now + TimeUnit.SECONDS.toNanos(15);
            LOGGER.info("ANDROIDPHONE_WORLD_SMOKE_PROGRESS: stage={} bridgeConnected={} uploaded={} drawn={}",
                    stage, AndroidPhoneAdapter.bridgeConnected(), AndroidPhoneAdapter.uploadedFrames(), AndroidPhoneAdapter.renderedFrames());
        }

        if (stage == Stage.JOINING) {
            var server = mc.getSingleplayerServer();
            if (mc.player == null || mc.level == null || server == null || mc.gameMode == null || mc.screen != null) return;
            if (!worldId.equals(server.getWorldData().getLevelName()))
                throw new IllegalStateException("Loaded world is not this smoke's new world");
            loadedNanos = now;
            deadlineNanos = now + TimeUnit.SECONDS.toNanos(180);
            originalCamera = mc.options.getCameraType();
            originalHideGui = mc.options.hideGui;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = false;
            mc.player.getInventory().setSelectedSlot(0);
            UUID playerId = mc.player.getUUID();
            equip = new CompletableFuture<>();
            stage = Stage.EQUIPPING;
            server.execute(() -> {
                try {
                    if (!worldId.equals(server.getWorldData().getLevelName()))
                        throw new IllegalStateException("World changed before smoke equipment grant");
                    var player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) throw new IllegalStateException("Smoke player left before equipment grant");
                    player.getInventory().setSelectedSlot(0);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PhoneItems.ANDROID_PHONE.get()));
                    player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(net.minecraft.world.item.Items.STICK));
                    player.inventoryMenu.broadcastChanges();
                    equip.complete(null);
                } catch (Exception failure) { equip.completeExceptionally(failure); }
            });
            return;
        }

        if (stage == Stage.EQUIPPING) {
            if (!equip.isDone()) return;
            equip.join();
            if (mc.player == null || mc.gameMode == null || mc.screen != null
                    || !(mc.player.getMainHandItem().getItem() instanceof AndroidPhoneItem)
                    || !mc.player.getOffhandItem().is(net.minecraft.world.item.Items.STICK)) return;
            uploadedBeforeUse = AndroidPhoneAdapter.uploadedFrames();
            drawnBeforeUse = AndroidPhoneAdapter.renderedFrames();
            // Go through Minecraft's prediction and packet path; do not invoke the adapter directly.
            var result = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            if (!result.consumesAction()) throw new IllegalStateException("Android phone use did not consume the action: " + result);
            usedNanos = now;
            stage = Stage.FRAMES;
            LOGGER.info("ANDROIDPHONE_WORLD_SMOKE_USED_ITEM: item=mcandroidphone:android_phone hand=MAIN_HAND offhand=minecraft:stick");
            return;
        }

        if (stage == Stage.FRAMES) {
            if (now - usedNanos < TimeUnit.SECONDS.toNanos(Math.max(2, Math.min(120, Long.getLong("mcandroidphone.smokeWarmupSeconds",0L))))
                    || !AndroidPhoneAdapter.bridgeConnected()
                    // Android may stream only one frame for a static desktop; the pattern must keep updating.
                    || AndroidPhoneAdapter.uploadedFrames() - uploadedBeforeUse < (Boolean.getBoolean("mcandroidphone.patternSmoke") ? 4 : 1)
                    || AndroidPhoneAdapter.renderedFrames() - drawnBeforeUse <= 3
                    || !(mc.screen instanceof AndroidPhoneFocusScreen)
                    || !PhoneProjection.hasProjectedQuad(mc.screen.width, mc.screen.height)) return;
            if (Boolean.getBoolean("mcandroidphone.patternSmoke") && !patternInputPosted) {
                if(!checkPatternPose(mc,now))return;
                postPatternTouch(mc);
                patternInputPosted = true;
                inputSettledNanos = now + TimeUnit.MILLISECONDS.toNanos(500);
                return;
            }
            if (patternInputPosted && now < inputSettledNanos) return;
            if(System.getProperty("mcandroidphone.config","").isBlank() && !AndroidPhoneAdapter.managedRuntimeActive())
                throw new IllegalStateException("World smoke expected the JAR-owned runtime");
            if (focusScreenshot==null) {
                var target=screenshotPath.resolveSibling("android-phone-focused.png");
                if(Files.exists(target))throw new IllegalStateException("Focus screenshot already exists");
                focusScreenshot=new CompletableFuture<>();
                Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{
                    try(image){image.writeToFile(target);focusScreenshot.complete(target);}
                    catch(Exception failure){focusScreenshot.completeExceptionally(failure);}
                });
                return;
            }
            if(!focusScreenshot.isDone())return;
            focusScreenshot.join();
            if (!stowStarted) {
                originalConnection=AndroidPhoneAdapter.connectionIdentity();
                originalEpoch=AndroidPhoneAdapter.connectionEpoch();
                stowStarted=true;
                mc.screen.onClose();
                move=movePhone(mc,0,9);
                stage=Stage.STOWED;
                return;
            }
            screenshot = new CompletableFuture<>();
            stage = Stage.SCREENSHOT;
            // Completion is observed on a later tick so GPU readback and PNG write finish before stop().
            Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                try (image) {
                    image.writeToFile(screenshotPath);
                    screenshot.complete(screenshotPath);
                } catch (Exception failure) { screenshot.completeExceptionally(failure); }
            });
            return;
        }

        if (stage==Stage.STOWED) {
            if (!move.isDone()) return;
            move.join();
            if (mc.player.getMainHandItem().getItem() instanceof AndroidPhoneItem) return;
            requireSameConnection();
            if (stowUntil==0) stowUntil=now+TimeUnit.SECONDS.toNanos(3);
            if (now<stowUntil) return;
            resumeDraws=AndroidPhoneAdapter.renderedFrames();
            move=movePhone(mc,9,0);
            stage=Stage.REEQUIPPED;
            return;
        }
        if (stage==Stage.REEQUIPPED) {
            if (!move.isDone()) return;
            move.join();
            requireSameConnection();
            if (!(mc.player.getMainHandItem().getItem() instanceof AndroidPhoneItem)
                    || AndroidPhoneAdapter.renderedFrames()-resumeDraws<10) return;
            if (reequippedAt==0) reequippedAt=now;
            if (now-reequippedAt<TimeUnit.SECONDS.toNanos(1))return;
            if (mc.screen!=null) throw new IllegalStateException("Reequip must render without right-click/focus");
            LOGGER.info("ANDROIDPHONE_STOW_RESUME_OK: sameConnection=true sameEpoch=true inventorySlot=9 noRightClick=true drawn={}",
                AndroidPhoneAdapter.renderedFrames()-resumeDraws);
            reload=mc.reloadResourcePacks();stage=Stage.RELOADING;
            reloadDraws=AndroidPhoneAdapter.renderedFrames();
            return;
        }
        if (stage==Stage.RELOADING) {
            requireSameConnection();
            if(!reload.isDone())return;
            reload.join();
            if(mc.getOverlay()!=null || AndroidPhoneAdapter.renderedFrames()-reloadDraws<10)return;
            LOGGER.info("ANDROIDPHONE_RESOURCE_RELOAD_OK: sameConnection=true cacheRetained=true");
            screenshot=new CompletableFuture<>();stage=Stage.SCREENSHOT;
            Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{
                try(image){image.writeToFile(screenshotPath);screenshot.complete(screenshotPath);}
                catch(Exception failure){screenshot.completeExceptionally(failure);}
            });
            return;
        }
        if (stage == Stage.SCREENSHOT && screenshot.isDone()) {
            Path written = screenshot.join();
            if (!Files.isRegularFile(written) || Files.size(written) == 0)
                throw new IllegalStateException("Screenshot write did not produce a PNG");
            LOGGER.info("ANDROIDPHONE_WORLD_SMOKE_OK: world={} item=mcandroidphone:android_phone offhand=minecraft:stick bridgeConnected={} uploaded={} drawn={} elapsedMs={} patternInputPosted={} screenshot={}",
                    worldId, AndroidPhoneAdapter.bridgeConnected(), AndroidPhoneAdapter.uploadedFrames() - uploadedBeforeUse,
                    AndroidPhoneAdapter.renderedFrames() - drawnBeforeUse, TimeUnit.NANOSECONDS.toMillis(now - loadedNanos), patternInputPosted, written);
            stage = Stage.DONE;
            restoreView(mc);
            mc.stop();
        }
    }

    private static void requireSameConnection() {
        if (!AndroidPhoneAdapter.bridgeConnected() || AndroidPhoneAdapter.connectionIdentity()!=originalConnection
                || AndroidPhoneAdapter.connectionEpoch()!=originalEpoch || AndroidPhoneAdapter.uploadedFrames()==0)
            throw new IllegalStateException("Stowing destroyed or reconnected the phone session/cache");
    }
    private static CompletableFuture<Void> movePhone(Minecraft mc,int from,int to) {
        var done=new CompletableFuture<Void>();
        var id=mc.player.getUUID();var server=mc.getSingleplayerServer();
        server.execute(()->{
            try {
                var player=server.getPlayerList().getPlayer(id);
                var inventory=player.getInventory();
                if (!inventory.getItem(to).isEmpty())throw new IllegalStateException("Smoke destination occupied");
                inventory.setItem(to,inventory.removeItemNoUpdate(from));
                player.inventoryMenu.broadcastChanges();done.complete(null);
            } catch(Exception failure){done.completeExceptionally(failure);}
        });
        return done;
    }
    /** Synthetic input is opt-in for the harmless pattern backend; ordinary Android smoke never taps the guest. */
    private static void postPatternTouch(Minecraft mc) {
        var center=PhoneProjection.projectLocal(.5,.5);
        if(center==null)throw new IllegalStateException("No projected touch center");
        double x=center.u(),y=center.v();
        var mouse = new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        var pressed = new ScreenEvent.MouseButtonPressed.Pre(mc.screen, mouse, false);
        var released = new ScreenEvent.MouseButtonReleased.Pre(mc.screen, mouse);
        try {
            NeoForge.EVENT_BUS.post(pressed);
        } finally {
            // Always release, including when a subscriber rejects the synthetic press.
            NeoForge.EVENT_BUS.post(released);
        }
        if (!pressed.isCanceled() || !released.isCanceled())
            throw new IllegalStateException("Android phone did not intercept synthetic pattern touch events");
        // Interception is verified here; backend receipt should be corroborated by bridge telemetry.
        LOGGER.info("ANDROIDPHONE_WORLD_SMOKE_PATTERN_INPUT: pressCanceled=true releaseCanceled=true x={} y={}", x, y);
    }

    /** Real rendered pose plus NeoForge input events, only in the opt-in pattern world. */
    private static boolean checkPatternPose(Minecraft mc,long now) {
        if(poseStep==0) {
            var p=PhoneProjection.projectLocal(.8,.2);
            GLFW.glfwSetCursorPos(mc.getWindow().handle(),p.u()*mc.getWindow().getScreenWidth()/mc.screen.width,
                p.v()*mc.getWindow().getScreenHeight()/mc.screen.height);
            poseStep=1;poseUntil=now+TimeUnit.SECONDS.toNanos(1);return false;
        }
        if(now<poseUntil)return false;
        if(poseStep==1) {
            if(Math.abs(AndroidPhoneAdapter.POSE.tiltX())<.5 || Math.abs(AndroidPhoneAdapter.POSE.tiltY())<.5)
                throw new IllegalStateException("Rendered phone did not follow pointer hover");
            LOGGER.info("ANDROIDPHONE_HOVER_OK: tiltX={} tiltY={}",AndroidPhoneAdapter.POSE.tiltX(),AndroidPhoneAdapter.POSE.tiltY());
            tiltedScreenshot=new CompletableFuture<>();
            Path target=screenshotPath.resolveSibling("android-phone-tilted.png");
            Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{
                try(image){image.writeToFile(target);tiltedScreenshot.complete(target);}
                catch(Exception e){tiltedScreenshot.completeExceptionally(e);}
            });
            poseStep=2;return false;
        }
        if(poseStep==2) {
            if(!tiltedScreenshot.isDone())return false;
            tiltedScreenshot.join();
            var edge=PhoneProjection.projectLocal(.5,0);var center=PhoneProjection.projectLocal(.5,.5);
            double dx=edge.u()-center.u(),dy=edge.v()-center.v(),length=Math.hypot(dx,dy);
            double x=edge.u()+dx/length*5,y=edge.v()+dy/length*5;
            var button=new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT,0);
            NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonPressed.Pre(mc.screen,new MouseButtonEvent(x,y,button),false));
            if(!AndroidPhoneAdapter.POSE.dragging())throw new IllegalStateException("Phone border did not capture rotation");
            var pivot=PhoneProjection.projectLocal(PhonePose.PIVOT_U,PhonePose.PIVOT_V);
            double nx=pivot.u()-(y-pivot.v()),ny=pivot.v()+(x-pivot.u());
            var mouse=new MouseButtonEvent(nx,ny,button);
            try {NeoForge.EVENT_BUS.post(new ScreenEvent.MouseDragged.Pre(mc.screen,mouse,nx-x,ny-y));}
            finally {NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(mc.screen,mouse));}
            poseStep=3;poseUntil=now+TimeUnit.SECONDS.toNanos(1);return false;
        }
        if(Math.abs(AndroidPhoneAdapter.POSE.rotation()+90)>.5 || AndroidPhoneAdapter.POSE.dragging())
            throw new IllegalStateException("Phone border rotation did not settle to landscape");
        LOGGER.info("ANDROIDPHONE_ROTATION_OK: rotation={} touchUsesProjectedQuad=true",AndroidPhoneAdapter.POSE.rotation());
        return true;
    }

    static boolean extractTestHover() {
        if(!Boolean.getBoolean("mcandroidphone.worldSmoke") || !Boolean.getBoolean("mcandroidphone.patternSmoke")
                || stage!=Stage.FRAMES || poseStep!=1)return false;
        var p=PhoneProjection.projectLocal(.8,.2);
        if(p!=null)AndroidPhoneAdapter.hover(p.u(),p.v());
        return true;
    }

    private static void restoreView(Minecraft mc) {
        if (originalCamera != null) {
            mc.options.setCameraType(originalCamera);
            mc.options.hideGui = originalHideGui;
            originalCamera = null;
        }
    }
}
