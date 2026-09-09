// SPDX-License-Identifier: Apache-2.0
package com.zhongbai233.mcandroidphone.camera;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Foreground-only Minecraft camera. No physical camera, microphone, or broad storage permission. */
public final class CameraActivity extends Activity {
    private static final int MAGIC=0x4d435043,MAX_JPEG=2*1024*1024;
    private static final long FRESH_MS=500;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final AtomicReference<Picture> waiting=new AtomicReference<>();
    private final AtomicInteger generation=new AtomicInteger();
    private final AtomicInteger lifecycle=new AtomicInteger();
    private final ExecutorService saving=Executors.newSingleThreadExecutor();
    private volatile boolean active;
    private volatile int lens=1;
    private volatile Socket socket;
    private Thread receiver;
    private ImageView preview;
    private TextView status;
    private Button capture,flip,album;
    private Picture shown;
    private boolean savingPhoto;
    private Uri lastPhoto;
    private static final class Picture {
        final byte[] jpeg;final Bitmap bitmap;final long at;final int generation,lens;
        Picture(byte[] jpeg,Bitmap bitmap,int generation,int lens){this.jpeg=jpeg;this.bitmap=bitmap;this.generation=generation;this.lens=lens;at=SystemClock.elapsedRealtime();}
        void close(){bitmap.recycle();}
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int sp){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(Color.WHITE);return t;}
    private Button button(String value){Button b=new Button(this);b.setText(value);b.setAllCaps(false);return b;}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(17,22,28));root.setPadding(dp(20),dp(20),dp(20),dp(16));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(20)+i.getSystemWindowInsetLeft(),dp(20)+i.getSystemWindowInsetTop(),dp(20)+i.getSystemWindowInsetRight(),dp(16)+i.getSystemWindowInsetBottom());return i;});
        root.addView(text("游戏相机",26));root.addView(text("拍下你的 Minecraft 世界",14));
        preview=new ImageView(this);preview.setBackgroundColor(Color.BLACK);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("游戏镜头预览");
        LinearLayout.LayoutParams pictureLayout=new LinearLayout.LayoutParams(-1,0,1);pictureLayout.topMargin=dp(20);root.addView(preview,pictureLayout);
        status=text("正在连接游戏镜头…",14);status.setPadding(0,dp(14),0,dp(10));root.addView(status);
        LinearLayout controls=new LinearLayout(this);flip=button("切换前摄");capture=button("拍照");album=button("查看照片");
        for(Button b:new Button[]{flip,capture,album})controls.addView(b,new LinearLayout.LayoutParams(0,dp(56),1));root.addView(controls);setContentView(root);
        capture.setEnabled(false);String last=getPreferences(0).getString("lastPhoto",null);lastPhoto=last==null?null:Uri.parse(last);album.setEnabled(lastPhoto!=null);
        flip.setOnClickListener(v->{lens=lens==1?2:1;generation.incrementAndGet();clearPicture();flip.setText(lens==1?"切换前摄":"切换后摄");status.setText("正在切换镜头…");});
        capture.setOnClickListener(v->takePhoto());
        album.setOnClickListener(v->{if(lastPhoto!=null)try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastPhoto,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(ActivityNotFoundException e){Toast.makeText(this,"照片已保存到 DCIM/MCAndroidPhone",Toast.LENGTH_LONG).show();}});
    }
    private void clearPicture(){Picture p=waiting.getAndSet(null);if(p!=null)p.close();preview.setImageDrawable(null);if(shown!=null){shown.close();shown=null;}capture.setEnabled(false);}
    private final Runnable refresh=new Runnable(){public void run(){if(!active)return;Picture p=waiting.getAndSet(null);if(p!=null){if(p.generation!=generation.get()){p.close();}else{Picture old=shown;shown=p;preview.setImageBitmap(p.bitmap);if(old!=null)old.close();}}
        boolean fresh=shown!=null&&shown.generation==generation.get()&&SystemClock.elapsedRealtime()-shown.at<FRESH_MS;
        if(!fresh){clearPicture();status.setText("等待游戏画面 · 请拿起手机并返回第一人称");}else status.setText(lens==1?"后摄 · 游戏世界":"前摄 · 自拍视角");capture.setEnabled(fresh&&!savingPhoto);ui.postDelayed(this,100);}};
    @Override protected void onResume(){super.onResume();active=true;generation.incrementAndGet();int run=lifecycle.incrementAndGet();receiver=new Thread(()->receive(run),"mcphone-camera-receiver");receiver.start();ui.post(refresh);}
    @Override protected void onPause(){active=false;lifecycle.incrementAndGet();generation.incrementAndGet();Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}if(receiver!=null)receiver.interrupt();ui.removeCallbacks(refresh);clearPicture();super.onPause();}
    @Override protected void onDestroy(){saving.shutdown();super.onDestroy();}
    private void receive(int run) {
        // The QEMU guest route is identical for every VM; the host destination remains session-private.
        while(active&&lifecycle.get()==run&&!Thread.currentThread().isInterrupted())try(Socket s=new Socket()) {
            socket=s;s.connect(new InetSocketAddress("10.0.2.100",18765),2000);s.setSoTimeout(2500);
            DataInputStream in=new DataInputStream(s.getInputStream());DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeInt(MAGIC);out.writeInt(1);out.flush();long previous=0;
            while(active&&lifecycle.get()==run&&!Thread.currentThread().isInterrupted()) {
                int ticket=generation.get(),facing=lens;out.writeInt(facing);out.flush();int length=in.readInt();if(length==0){Thread.sleep(100);continue;}
                if(length<32||length>28+MAX_JPEG)throw new IOException("Frame length");if(in.readInt()!=MAGIC||in.readInt()!=1)throw new IOException("Protocol");long sequence=in.readLong();int w=in.readInt(),h=in.readInt(),size=in.readInt();
                if(w<2||h<2||w>640||h>640||size!=length-28||sequence<1||sequence<previous)throw new IOException("Frame metadata");byte[]jpeg=new byte[size];in.readFully(jpeg);
                if(sequence!=previous&&active&&generation.get()==ticket) {
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(jpeg,0,size,bounds);if(bounds.outWidth!=w||bounds.outHeight!=h)throw new IOException("JPEG dimensions");
                    Bitmap bitmap=BitmapFactory.decodeByteArray(jpeg,0,size);if(bitmap==null)throw new IOException("JPEG decode");Picture p=new Picture(jpeg,bitmap,ticket,facing);
                    if(!active||ticket!=generation.get())p.close();else{Picture old=waiting.getAndSet(p);if(old!=null)old.close();}
                }
                previous=sequence;Thread.sleep(100);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();break;}catch(IOException e){if(active&&lifecycle.get()==run)try{Thread.sleep(800);}catch(InterruptedException stop){Thread.currentThread().interrupt();break;}}finally{if(lifecycle.get()==run)socket=null;}
    }
    private void takePhoto() {
        Picture frame=shown;if(frame==null||frame.generation!=generation.get()||SystemClock.elapsedRealtime()-frame.at>=FRESH_MS||savingPhoto)return;
        final byte[] jpeg=frame.jpeg;savingPhoto=true;capture.setEnabled(false);status.setText("正在保存照片…");
        saving.execute(()->{Uri target=null;try {
            ContentResolver resolver=getApplicationContext().getContentResolver();ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"Minecraft-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/MCAndroidPhone");values.put(MediaStore.Images.Media.IS_PENDING,1);
            target=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(target==null)throw new IOException("MediaStore insert failed");try(OutputStream out=resolver.openOutputStream(target,"w")){if(out==null)throw new IOException("Photo output missing");out.write(jpeg);}
            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);if(resolver.update(target,values,null,null)!=1)throw new IOException("Photo publication failed");Uri saved=target;
            ui.post(()->{savingPhoto=false;lastPhoto=saved;getPreferences(0).edit().putString("lastPhoto",saved.toString()).apply();album.setEnabled(true);Toast.makeText(this,"已保存到相册",Toast.LENGTH_SHORT).show();});
        }catch(Exception e){if(target!=null)try{getContentResolver().delete(target,null,null);}catch(Exception ignored){}ui.post(()->{savingPhoto=false;Toast.makeText(this,"保存失败，请检查存储空间后重试",Toast.LENGTH_LONG).show();});}});
    }
}
