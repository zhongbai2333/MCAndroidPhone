import com.zhongbai233.mcandroidphone.core.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.file.Path;
import com.zhongbai233.mcandroidphone.gpu.D3d11Importer;

public class GpuProbe {
 public static void main(String[] args) throws Exception {
  GLFW.glfwInit(); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);
  long window=GLFW.glfwCreateWindow(64,64,"GPU import diagnostic",0,0);
  GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
  System.out.println(GL11C.glGetString(GL11C.GL_RENDERER));
  try(BridgeClient client=new BridgeClient(Path.of(args[0])); D3d11Importer importer=new D3d11Importer()) {
   client.start();long until=System.nanoTime()+90_000_000_000L;int count=0;
   while(System.nanoTime()<until && count<3) {
    try(GpuFrame frame=client.pollGpuFrame()) {
     if(frame==null){Thread.sleep(2);continue;}
     System.out.println(frame.notice());
     int dst=GL11C.glGenTextures();
     GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,dst);
     GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D,1,GL11C.GL_RGBA8,frame.notice().textureWidth(),frame.notice().textureHeight());
     long began=System.nanoTime();
     if (!importer.copyTo(frame,dst)) { GL11C.glDeleteTextures(dst); continue; }
     System.out.println("GPU_COPY_OK us="+(System.nanoTime()-began)/1000);
     java.nio.ByteBuffer pixels=org.lwjgl.system.MemoryUtil.memAlloc(frame.notice().textureWidth()*frame.notice().textureHeight()*4);
     try {
      GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,pixels);
      if (GL11C.glGetError()!=0) throw new IllegalStateException("Diagnostic GPU readback failed");
      java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(pixels);
      System.out.println("DIAGNOSTIC_RGBA_CRC32="+crc.getValue());
     } finally { org.lwjgl.system.MemoryUtil.memFree(pixels); }
     GL11C.glDeleteTextures(dst);
     count++;
    }
   }
   if (count != 3) throw new IllegalStateException("GPU import did not complete: " + client.status());
   System.out.println("GPU_SMOKE_OK frames="+count+" "+client.status());
  } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
