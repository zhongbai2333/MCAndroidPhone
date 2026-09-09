package com.zhongbai233.mcandroidphone.core;

import java.io.IOException;
import java.lang.foreign.*;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** A read-only mapped surface or bounded D-Bus pixel fallback, owned by the surface lock. */
final class DbusSurface implements AutoCloseable {
    final int width,height,stride;final String format;final long offset;
    private long handle;private MemorySegment mapping;private byte[] pixels;
    static String format(long pixman,String order)throws IOException {
        String value=switch((int)pixman){case 0x20020888->"bgr0";case 0x20028888->"bgra";case 0x20030888->"rgb0";case 0x20038888->"rgba";default->throw new IOException("Unsupported pixman format");};
        if(!Set.of("rgb","bgr").contains(order))throw new IOException("Invalid colorOrder");
        return order.equals("rgb")?value:switch(value){case "bgr0"->"rgb0";case "rgb0"->"bgr0";case "bgra"->"rgba";default->"bgra";};
    }
    static long layout(long offset,int w,int h,int stride)throws IOException {
        RfbClient.dimensions(w,h);if(offset<0||stride<w*4||stride>16384||stride%4!=0||offset>128*1024*1024L-(long)stride*h)throw new IOException("Invalid shared framebuffer layout");return offset+(long)stride*h;
    }
    DbusSurface(long handle,long offset,int w,int h,int stride,long pixman,String order,byte[] data)throws IOException {
        this.handle=handle;this.offset=offset;width=w;height=h;this.stride=stride;
        try {
            long size=layout(offset,w,h,stride);format=format(pixman,order);
            if(data!=null){if(handle!=0||offset!=0||data.length!=size)throw new IOException("Pixel scanout length mismatch");pixels=data.clone();}
            else {if(handle<=0)throw new IOException("Invalid shared mapping handle");mapping=Win32Handles.map(handle,size);}
        }catch(IOException|RuntimeException e){close();throw e;}
    }
    void rectangle(int x,int y,int w,int h)throws IOException {if(x<0||y<0||w<0||h<0||(long)x+w>width||(long)y+h>height)throw new IOException("Dirty rectangle outside framebuffer");}
    void update(int x,int y,int w,int h,int rowStride,long pixman,byte[] data,String order)throws IOException {
        rectangle(x,y,w,h);if(pixels==null||rowStride<w*4||rowStride>16384||data.length!=(long)rowStride*h||!format(pixman,order).equals(format))throw new IOException("Pixel update layout mismatch");
        for(int row=0;row<h;row++)System.arraycopy(data,row*rowStride,pixels,(y+row)*stride+x*4,w*4);
    }
    byte[] snapshot(){byte[] result=new byte[width*height*4];for(int row=0;row<height;row++) {
        if(mapping!=null)MemorySegment.copy(mapping,JAVA_BYTE,offset+(long)row*stride,result,row*width*4,width*4);
        else System.arraycopy(pixels,row*stride,result,row*width*4,width*4);
    }return result;}
    @Override public void close(){if(mapping!=null){Win32Handles.unmap(mapping);mapping=null;}if(handle>0){Win32Handles.close(handle);handle=0;}pixels=null;}
}
