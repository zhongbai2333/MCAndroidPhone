package com.zhongbai233.mcandroidphone.phone;

import com.zhongbai233.mcandroidphone.core.AndroidImageLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Responsive settings UI; installation and integrity checks never run on the render thread. */
public final class AndroidImageManagerScreen extends Screen {
    private static volatile Job job;
    private static int smokeTicks,smokeStage;
    private static CompletableFuture<Void> screenshot;
    static void smokeTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("mcandroidphone.imageManagerSmoke"))return;
        Minecraft mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        try {
            if(++smokeTicks<20)return;smokeTicks=0;
            if(screenshot!=null){if(!screenshot.isDone())return;screenshot.join();screenshot=null;}
            if(smokeStage==0){if(!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen))return;mc.setScreen(new OptionsScreen(mc.screen,mc.options,false));}
            else if(smokeStage==1){
                if(mc.screen.children().stream().noneMatch(c->c instanceof Button b&&b.getMessage().getString().equals("安卓镜像…")))throw new AssertionError("Settings entry missing");
                capture(mc,"options.png");
            }
            else if(smokeStage==2)mc.setScreen(new AndroidImageManagerScreen(mc.screen));
            else if(smokeStage==3)capture(mc,"image-manager.png");
            else if(smokeStage==4)mc.setScreen(new ImportScreen((AndroidImageManagerScreen)mc.screen));
            else if(smokeStage==5)capture(mc,"image-import.png");
            else {com.mojang.logging.LogUtils.getLogger().info("IMAGE_MANAGER_UI_SMOKE_OK options entry, catalog, import screen rendered");mc.stop();}
            smokeStage++;
        }catch(Throwable e){com.mojang.logging.LogUtils.getLogger().error("IMAGE_MANAGER_UI_SMOKE_FAILED",e);mc.stop();}
    }
    private static void capture(Minecraft mc,String name)throws Exception {
        Path folder=Path.of(System.getProperty("mcandroidphone.imageManagerSmokeOutput"));Files.createDirectories(folder);Path target=folder.resolve(name);
        if(Files.exists(target))throw new IllegalStateException("Smoke screenshot exists");
        for(var child:mc.screen.children())if(child instanceof AbstractWidget w&&(w.getX()<0||w.getY()<0||w.getX()+w.getWidth()>mc.screen.width||w.getY()+w.getHeight()>mc.screen.height))throw new IllegalStateException("Widget outside screen: "+w.getMessage().getString());
        CompletableFuture<Void> pending=new CompletableFuture<>();screenshot=pending;
        net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(target);pending.complete(null);}catch(Exception e){pending.completeExceptionally(e);}});
    }
    private static final class Job {volatile String status="准备中…";volatile boolean cancelled,done;}
    private final Screen parent;
    private AndroidImageLibrary library;
    private List<AndroidImageLibrary.Image> images=List.of();
    private String selected="",error="";
    private int page;
    private Job observed;
    private final List<Button> mutations=new ArrayList<>();
    public AndroidImageManagerScreen(Screen parent){super(Component.literal("安卓镜像管理"));this.parent=parent;}
    public static void optionsButton(ScreenEvent.Init.Post event) {
        if(!(event.getScreen() instanceof OptionsScreen screen))return;
        for(var listener:screen.children())if(listener instanceof Button done&&done.getMessage().getString().equals(Component.translatable("gui.done").getString())) {
            int x=screen.width/2;done.setX(x+2);done.setWidth(98);
            event.addListener(Button.builder(Component.literal("安卓镜像…"),b->Minecraft.getInstance().setScreen(new AndroidImageManagerScreen(screen))).bounds(x-100,done.getY(),98,20).build());break;
        }
    }
    private Button button(String label,int x,int y,int w,Runnable action,boolean mutation) {
        Button b=addRenderableWidget(Button.builder(Component.literal(label),ignored->action.run()).bounds(x,y,w,20).build());if(mutation)mutations.add(b);return b;
    }
    private String defaultFor(String arch){try{return library.selected(arch);}catch(Exception e){return "";}}
    private int rows(){return Math.max(1,(height-184)/26);}
    @Override protected void init() {
        mutations.clear();int w=Math.min(width-24,440),left=(width-w)/2;
        try {library=new AndroidImageLibrary(minecraft.gameDirectory.toPath());images=library.list();if(selected.isEmpty())selected=library.selected(AndroidImageLibrary.hostArch());}catch(Exception e){error=e.getMessage();}
        page=Math.min(page,Math.max(0,(images.size()-1)/rows()));
        for(int i=page*rows();i<Math.min(images.size(),(page+1)*rows());i++) {
            var item=images.get(i);String mark=item.id().equals(selected)?"▶ ":"";
            String label=mark+item.name()+(item.id().equals(defaultFor(item.arch()))?"（默认）":"")+"  ·  "+(item.installed()?"已安装":String.format(Locale.ROOT,"%.1f MB",item.bytes()/1e6));
            button(label,left,56+(i%rows())*26,w,()->{selected=item.id();rebuildWidgets();},false);
        }
        int y=height-124;
        button("上一页",left,y,65,()->{page=Math.max(0,page-1);rebuildWidgets();},false);
        button("下一页",left+70,y,65,()->{page=Math.min(Math.max(0,(images.size()-1)/rows()),page+1);rebuildWidgets();},false);
        button("导入本地…",left+w-100,y,100,()->minecraft.setScreen(new ImportScreen(this)),true);
        int third=(w-8)/3;
        button("下载 / 校验",left,y+25,third,()->start((cancel,status)->library.install(selected,cancel,status)),true);
        button("设为新手机默认",left+third+4,y+25,third,()->start((cancel,status)->{library.select(selected);status.accept("已设为新手机默认；已有手机保持原系统");}),true);
        button("移除镜像",left+2*(third+4),y+25,third,()->minecraft.setScreen(new ConfirmScreen(ok->{minecraft.setScreen(this);if(ok)start((cancel,status)->{library.remove(selected);selected="";status.accept("镜像已移除，手机数据保留");});},Component.literal("移除所选镜像？"),Component.literal("仅删除镜像模板。已有手机的应用和数据会保留。"))),true);
        button("返回",width/2-102,height-26,100,this::onClose,false);
        button("取消任务",width/2+2,height-26,100,()->{Job current=job;if(current!=null&&!current.done)current.cancelled=true;},false);
        updateButtons();
    }
    @FunctionalInterface private interface Work {void run(Runnable cancelled,java.util.function.Consumer<String> status)throws Exception;}
    private void start(Work work) {
        if(job!=null&&!job.done)return;
        if(AndroidPhoneAdapter.imageManagementBlocked()){error="请先让安卓手机关机，再管理镜像";return;}
        Job current=new Job();job=current;observed=null;error="";
        Thread.ofPlatform().daemon().name("android-image-library").start(()->{
            try{work.run(()->{if(current.cancelled)throw new CancellationException();},text->current.status=text);if(!current.cancelled&&current.status.startsWith("正在"))current.status="镜像已就绪";}
            catch(CancellationException e){current.status="已取消；官方镜像下载可续传";}
            catch(Exception e){current.status="操作失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
            finally{current.done=true;}
        });updateButtons();
    }
    private void updateButtons(){boolean busy=(job!=null&&!job.done)||AndroidPhoneAdapter.imageManagementBlocked();for(Button b:mutations)b.active=!busy;}
    @Override public void tick(){if(job!=null&&job.done&&job!=observed){observed=job;rebuildWidgets();}updateButtons();}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float partial) {
        super.extractRenderState(g,x,y,partial);g.centeredText(font,title,width/2,14,0xffffffff);
        g.centeredText(font,Component.literal("选择新手机的系统 · 已有手机保留原系统和数据"),width/2,34,0xffbbbbbb);
        String status=!error.isEmpty()?error:AndroidPhoneAdapter.imageManagementBlocked()?"安卓正在运行，请先关机":job==null?"支持官方镜像、兼容的三盘目录与镜像 ZIP":job.status;
        g.textWithWordWrap(font,Component.literal(status),12,height-70,width-24,0xffdddddd);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    private static final class ImportScreen extends Screen {
        private final AndroidImageManagerScreen parent;
        private EditBox path,name;
        private String arch=AndroidImageLibrary.hostArch(),savedPath="",savedName="";
        ImportScreen(AndroidImageManagerScreen parent){super(Component.literal("导入安卓镜像"));this.parent=parent;}
        @Override protected void init() {
            if(path!=null){savedPath=path.getValue();savedName=name.getValue();}
            int w=Math.min(width-24,420),left=(width-w)/2;
            path=addRenderableWidget(new EditBox(font,left,65,w,20,Component.literal("镜像 ZIP 或目录路径")));path.setMaxLength(4096);path.setValue(savedPath);
            name=addRenderableWidget(new EditBox(font,left,105,w,20,Component.literal("显示名称")));name.setMaxLength(80);name.setValue(savedName);
            addRenderableWidget(Button.builder(Component.literal("架构："+arch),b->{arch=arch.equals("amd64")?"arm64":"amd64";b.setMessage(Component.literal("架构："+arch));}).bounds(left,135,150,20).build());
            addRenderableWidget(Button.builder(Component.literal("导入"),b->{Path input;try{input=Path.of(path.getValue().strip().replaceAll("^\"|\"$",""));}catch(InvalidPathException e){return;}String label=name.getValue();minecraft.setScreen(parent);parent.start((cancel,status)->{String id=parent.library.importLocal(input,arch,label,cancel,status);parent.selected=id;status.accept("导入完成，可设为新手机默认镜像");});}).bounds(width/2+2,height-26,100,20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(width/2-102,height-26,100,20).build());
        }
        @Override public void removed(){if(path!=null){savedPath=path.getValue();savedName=name.getValue();}}
        @Override public void onFilesDrop(List<Path> files){if(files.size()==1)path.setValue(files.getFirst().toAbsolutePath().toString());}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float partial) {
            super.extractRenderState(g,x,y,partial);g.centeredText(font,title,width/2,14,0xffffffff);int left=(width-Math.min(width-24,420))/2;
            g.text(font,"镜像包 / 三盘目录（可拖入窗口）",left,50,0xffdddddd);g.text(font,"显示名称（可留空）",left,92,0xffdddddd);
            g.textWithWordWrap(font,Component.literal("需要适配 QEMU virtio / UEFI 的镜像。普通手机刷机包或单独的 GSI 不能直接启动。"),left,165,Math.min(width-24,420),0xffbbbbbb);
        }
        @Override public void onClose(){minecraft.setScreen(parent);}
    }
}
