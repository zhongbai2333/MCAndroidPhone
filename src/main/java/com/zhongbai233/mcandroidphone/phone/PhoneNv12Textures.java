package com.zhongbai233.mcandroidphone.phone;
import java.nio.ByteBuffer;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.textures.FilterMode;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL42C.glTexStorage2D;

/** Two native planes; mapped NV12 buffers upload directly without Java heap conversion. */
final class PhoneNv12Textures implements AutoCloseable {
    private final Identifier yId,uvId;
    private Plane y,uv;
    private int width,height;
    PhoneNv12Textures(Identifier yId,Identifier uvId,Identifier unused,String label) {
        this.yId=yId;this.uvId=uvId;
    }
    Identifier yId(){return yId;}
    Identifier uId(){return uvId;}
    Identifier vId(){return yId;}
    boolean upload(byte[] bytes,int w,int h) {
        var buffer=ByteBuffer.allocateDirect(bytes.length);buffer.put(bytes).flip();
        return upload(buffer,bytes.length,w,h);
    }
    boolean upload(ByteBuffer bytes,int length,int w,int h) {
        int size=Math.multiplyExact(w,h);
        if (w<=0||h<=0||(w&1)!=0||(h&1)!=0||length<size+size/2||bytes.remaining()<size+size/2)
            throw new IllegalArgumentException("Invalid NV12 planes");
        if (y==null||w!=width||h!=height) {
            close(); width=w;height=h;
            try {
                y=new Plane(w,h,false);uv=new Plane(w/2,h/2,true);
                Minecraft.getInstance().getTextureManager().register(yId,y);
                Minecraft.getInstance().getTextureManager().register(uvId,uv);
            } catch (RuntimeException error) { close();throw error; }
        }
        y.upload(bytes.slice(bytes.position(),size));
        uv.upload(bytes.slice(bytes.position()+size,size/2));
        return true;
    }
    @Override public void close() {
        var textures=Minecraft.getInstance().getTextureManager();
        if(y!=null){textures.release(yId);y.close();y=null;}
        if(uv!=null){textures.release(uvId);uv.close();uv=null;}
    }
    private static final class Plane extends AbstractTexture {
        private final int width,height,format;
        Plane(int w,int h,boolean chroma) {
            width=w;height=h;format=chroma?GL_RG:GL_RED;
            int previous=glGetInteger(GL_TEXTURE_BINDING_2D);
            int pbo=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            int id=glGenTextures();boolean wrapped=false;
            try {
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
                GlStateManager._bindTexture(id);
                glTexStorage2D(GL_TEXTURE_2D,1,chroma?GL_RG8:GL_R8,w,h);
                int error=glGetError();
                if(error!=GL_NO_ERROR)throw new IllegalStateException("Phone texture allocation GL error "+error);
                texture=new PlaneStorage(w,h,id);wrapped=true;
                textureView=RenderSystem.getDevice().createTextureView(texture);
                sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            } catch(RuntimeException error) { if(wrapped)close();throw error; }
            finally {
                if(!wrapped)glDeleteTextures(id);
                GlStateManager._bindTexture(previous);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,pbo);
            }
        }
        void upload(ByteBuffer bytes) {
            int binding=glGetInteger(GL_TEXTURE_BINDING_2D),pbo=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            int[] names={GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_SKIP_PIXELS,GL_UNPACK_SKIP_ROWS};
            int[] saved=new int[names.length];
            for(int i=0;i<names.length;i++)saved[i]=glGetInteger(names[i]);
            try {
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
                GlStateManager._bindTexture(((GlTexture)texture).glId());
                for(int i=0;i<names.length;i++)glPixelStorei(names[i],i==0?1:0);
                glTexSubImage2D(GL_TEXTURE_2D,0,0,0,width,height,format,GL_UNSIGNED_BYTE,bytes);
                int error=glGetError();
                if(error!=GL_NO_ERROR)throw new IllegalStateException("Phone NV12 upload GL error "+error);
            } finally {
                for(int i=0;i<names.length;i++)glPixelStorei(names[i],saved[i]);
                GlStateManager._bindTexture(binding);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,pbo);
            }
        }
    }
    /** RG8 has no Minecraft enum; the owned GL allocation defines actual plane storage. */
    private static final class PlaneStorage extends GlTexture {
        PlaneStorage(int width,int height,int id) {
            super(GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,"phone_nv12_plane",
                TextureFormat.RGBA8,width,height,1,1,id);
        }
    }
}
