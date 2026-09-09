package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** RFC 6143: loopback, None security, Raw/CopyRect/DesktopSize, bounded native BGRX frames. */
final class RfbClient implements AutoCloseable {
    interface Resize {void accept(int width,int height)throws Exception;}
    record Update(boolean changed,boolean resized){}
    private final Socket socket=new Socket();
    private final Object writeLock=new Object(),inputLock=new Object();
    private final Object layoutLock;
    private InputStream in;private OutputStream out;
    private volatile boolean closed;
    private int mask,x,y;
    private final Set<Integer> keys=new LinkedHashSet<>();
    int width,height;
    byte[] pixels=new byte[0];
    static void dimensions(int w,int h){if(w<2||h<2||w>4096||h>4096||(w&1)!=0||(h&1)!=0)throw new IllegalArgumentException("Native display must have even dimensions in 2..4096: "+w+"x"+h);}
    RfbClient(int port,Resize resize)throws Exception {this(port,resize,new Object());}
    RfbClient(int port,Resize resize,Object layoutLock)throws Exception {
        this.layoutLock=layoutLock;
        if(port<1||port>65535)throw new IllegalArgumentException("Invalid VNC port");
        try {
            socket.connect(new InetSocketAddress("127.0.0.1",port),3000);socket.setSoTimeout(250);socket.setTcpNoDelay(true);
            in=new BufferedInputStream(socket.getInputStream(),65536);out=socket.getOutputStream();
            String banner=new String(read(12,false),StandardCharsets.US_ASCII);
            if(!banner.matches("RFB 003\\.[0-9]{3}\n"))throw new IOException("Invalid RFB banner");
            int offered=Integer.parseInt(banner.substring(8,11)),version=offered==7||offered==8?offered:3;
            send(String.format(Locale.ROOT,"RFB 003.%03d\n",version).getBytes(StandardCharsets.US_ASCII));
            if(version==3){int security=buffer(4).getInt();if(security==0)throw new IOException(reason());if(security!=1)throw new IOException("Authenticated VNC is unsupported");}
            else {int count=read(1,false)[0]&255;if(count==0)throw new IOException(reason());boolean none=false;
                for(byte type:read(count,false))if(type==1)none=true;if(!none)throw new IOException("Authenticated VNC is unsupported");send(new byte[]{1});
                if(version==8&&buffer(4).getInt()!=0)throw new IOException(reason());}
            send(new byte[]{1});var init=buffer(24);int w=Short.toUnsignedInt(init.getShort()),h=Short.toUnsignedInt(init.getShort());init.position(20);int name=init.getInt();
            if(name<0||name>65536)throw new IOException("VNC name exceeds limit");read(name,false);resize(w,h,resize);
            var format=ByteBuffer.allocate(20);format.putInt(0).put(new byte[]{32,24,0,1}).putShort((short)255).putShort((short)255).putShort((short)255).put(new byte[]{16,8,0,0,0,0});send(format.array());
            send(ByteBuffer.allocate(16).put((byte)2).put((byte)0).putShort((short)3).putInt(0).putInt(1).putInt(-223).array());
        }catch(Exception e){close();throw e;}
    }
    private String reason()throws IOException{int size=buffer(4).getInt();if(size<0||size>65536)throw new IOException("VNC error exceeds limit");return new String(read(size,false),StandardCharsets.UTF_8);}
    private ByteBuffer buffer(int count)throws IOException{return ByteBuffer.wrap(read(count,false));}
    private byte[] read(int count,boolean idle)throws IOException {
        if(count<0||count>4096*4096*4)throw new IOException("VNC payload exceeds limit");
        byte[] data=new byte[count];int offset=0;long end=System.nanoTime()+10_000_000_000L;
        while(offset<count){if(closed)throw new EOFException("VNC closed");
            try{int n=in.read(data,offset,count-offset);if(n<0)throw new EOFException("VNC disconnected");offset+=n;}
            catch(SocketTimeoutException e){if(idle&&offset==0){end=System.nanoTime()+10_000_000_000L;continue;}if(System.nanoTime()>end)throw e;}
            if(offset<count&&System.nanoTime()>end)throw new SocketTimeoutException("VNC partial message timeout");
        }return data;
    }
    private void send(byte[] data)throws IOException{synchronized(writeLock){if(closed||out==null)throw new EOFException("VNC closed");out.write(data);out.flush();}}
    private void resize(int w,int h,Resize listener)throws Exception {
        dimensions(w,h);
        // Atomically change the device epoch, native dimensions and input coordinates.
        synchronized(layoutLock){release();listener.accept(w,h);
        synchronized(inputLock){byte[] next=new byte[w*h*4];for(int row=0;row<Math.min(h,height);row++)System.arraycopy(pixels,row*width*4,next,row*w*4,Math.min(w,width)*4);
            pixels=next;width=w;height=h;x=Math.min(x,w-1);y=Math.min(y,h-1);}}
    }
    void request(boolean incremental)throws IOException{send(ByteBuffer.allocate(10).put((byte)3).put((byte)(incremental?1:0)).putShort((short)0).putShort((short)0).putShort((short)width).putShort((short)height).array());}
    private void rect(int x,int y,int w,int h)throws IOException{if(w<1||h<1||(long)x+w>width||(long)y+h>height)throw new IOException("VNC rectangle outside framebuffer");}
    Update update(Resize resize,boolean idle)throws Exception {
        int ancillary=0;while(true){int type=read(1,idle)[0]&255;if(type==0)break;if(++ancillary>256)throw new IOException("Too many VNC ancillary messages");
            if(type==2)continue;if(type!=3)throw new IOException("Unexpected VNC message: "+type);var b=buffer(7);b.position(3);int size=b.getInt();
            if(size<0||size>1024*1024)throw new IOException("VNC clipboard limit");read(size,false);}
        var header=buffer(3);header.get();int count=Short.toUnsignedInt(header.getShort());if(count>8192)throw new IOException("VNC rectangle count limit");
        boolean changed=false,resized=false;long bytes=(long)count*12;
        for(int i=0;i<count;i++) {
            var b=buffer(12);int x=Short.toUnsignedInt(b.getShort()),y=Short.toUnsignedInt(b.getShort()),w=Short.toUnsignedInt(b.getShort()),h=Short.toUnsignedInt(b.getShort()),encoding=b.getInt();
            if(encoding==-223){if(i!=count-1)throw new IOException("DesktopSize must be last");resize(w,h,resize);changed=false;resized=true;continue;}
            rect(x,y,w,h);
            if(encoding==0){int size=w*h*4;bytes+=size;if(bytes>2L*4096*4096*4)throw new IOException("VNC update byte limit");byte[] data=read(size,false);
                for(int row=0;row<h;row++)System.arraycopy(data,row*w*4,pixels,((y+row)*width+x)*4,w*4);
            }else if(encoding==1){var source=buffer(4);int sx=Short.toUnsignedInt(source.getShort()),sy=Short.toUnsignedInt(source.getShort());rect(sx,sy,w,h);
                for(int r=0;r<h;r++){int row=y>sy?h-r-1:r;System.arraycopy(pixels,((sy+row)*width+sx)*4,pixels,((y+row)*width+x)*4,w*4);}
            }else throw new IOException("Unrequested VNC encoding: "+encoding);changed=true;
        }return new Update(changed,resized);
    }
    void touch(String phase,double u,double v)throws IOException{synchronized(inputLock){x=(int)Math.round(u*(width-1));y=(int)Math.round(v*(height-1));
        if(phase.equals("DOWN"))mask=1;int next=phase.equals("UP")?0:mask;pointer(next);mask=next;}}
    private void pointer(int value)throws IOException{send(ByteBuffer.allocate(6).put((byte)5).put((byte)value).putShort((short)x).putShort((short)y).array());}
    void release()throws IOException{synchronized(inputLock){if(mask!=0){pointer(0);mask=0;}}}
    void chord(int...values)throws IOException{synchronized(inputLock){IOException failure=null;try{for(int key:values){keys.add(key);key(key,true);}}catch(IOException e){failure=e;}
        finally{for(int i=values.length-1;i>=0;i--)try{key(values[i],false);keys.remove(values[i]);}catch(IOException e){if(failure==null)failure=e;}}
        if(failure!=null)throw failure;}}
    private void key(int value,boolean down)throws IOException{send(ByteBuffer.allocate(8).put((byte)4).put((byte)(down?1:0)).putShort((short)0).putInt(value).array());}
    @Override public void close(){try{release();for(int key:List.copyOf(keys))try{key(key,false);}catch(IOException ignored){}}catch(IOException ignored){}
        closed=true;try{socket.close();}catch(IOException ignored){} }
}
