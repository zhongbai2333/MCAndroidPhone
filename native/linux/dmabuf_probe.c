/* Hardware capability probe, not the production transport. CPU readback is diagnostic only. */
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <GL/glext.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

int main(void) {
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);EGLint major,minor;
    if(!eglInitialize(d,&major,&minor)){puts("UNAVAILABLE: EGL display");return 77;}
    const char *extensions=eglQueryString(d,EGL_EXTENSIONS);
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_NONE};
    EGLConfig cfg;EGLint count;eglBindAPI(EGL_OPENGL_API);
    if(!eglChooseConfig(d,attrs,&cfg,1,&count)||!count)return 77;
    EGLint size[]={EGL_WIDTH,4,EGL_HEIGHT,4,EGL_NONE};
    EGLSurface p=eglCreatePbufferSurface(d,cfg,size);EGLContext c=eglCreateContext(d,cfg,EGL_NO_CONTEXT,NULL);
    if(!eglMakeCurrent(d,p,p,c))return 77;
    const char *renderer=(const char*)glGetString(GL_RENDERER);
    printf("EGL %d.%d renderer=%s\n",major,minor,renderer?renderer:"unknown");
    if(!renderer || strstr(renderer,"llvmpipe") || strstr(renderer,"softpipe") || !extensions ||
       !strstr(extensions,"EGL_MESA_image_dma_buf_export") || !strstr(extensions,"EGL_EXT_image_dma_buf_import")) {
        puts("DMABUF_UNAVAILABLE: hardware producer export and consumer import are both required; WSL OpenGL acceleration alone is insufficient.");return 77;
    }
    PFNEGLCREATEIMAGEKHRPROC create=(void*)eglGetProcAddress("eglCreateImageKHR");
    PFNEGLDESTROYIMAGEKHRPROC destroy=(void*)eglGetProcAddress("eglDestroyImageKHR");
    PFNEGLEXPORTDMABUFIMAGEQUERYMESAPROC query=(void*)eglGetProcAddress("eglExportDMABUFImageQueryMESA");
    PFNEGLEXPORTDMABUFIMAGEMESAPROC export=(void*)eglGetProcAddress("eglExportDMABUFImageMESA");
    void (*target)(GLenum,void*)=(void*)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    if(!create||!destroy||!query||!export||!target)return 77;
    GLuint tex,fbo;glGenTextures(1,&tex);glBindTexture(GL_TEXTURE_2D,tex);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,4,4,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,tex,0);
    glClearColor(.25,.5,.75,1);glClear(GL_COLOR_BUFFER_BIT);glFinish();
    EGLImage image=create(d,c,EGL_GL_TEXTURE_2D_KHR,(EGLClientBuffer)(uintptr_t)tex,NULL);
    int fourcc,planes,fd=-1,stride,offset;EGLuint64KHR modifier;
    if(image==EGL_NO_IMAGE_KHR||!query(d,image,&fourcc,&planes,&modifier)||planes!=1||!export(d,image,&fd,&stride,&offset))return 77;
    EGLint importedAttrs[]={EGL_WIDTH,4,EGL_HEIGHT,4,EGL_LINUX_DRM_FOURCC_EXT,fourcc,
        EGL_DMA_BUF_PLANE0_FD_EXT,fd,EGL_DMA_BUF_PLANE0_OFFSET_EXT,offset,EGL_DMA_BUF_PLANE0_PITCH_EXT,stride,
        EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT,(EGLint)modifier,EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT,(EGLint)(modifier>>32),EGL_NONE};
    if(!strstr(extensions,"EGL_EXT_image_dma_buf_import_modifiers")) {
        if(modifier!=0){puts("DMABUF_UNAVAILABLE: non-linear modifier import unsupported");close(fd);return 77;}
        importedAttrs[12]=EGL_NONE;
    }
    EGLImage imported=create(d,EGL_NO_CONTEXT,EGL_LINUX_DMA_BUF_EXT,NULL,importedAttrs);close(fd);
    if(imported==EGL_NO_IMAGE_KHR){puts("DMABUF_UNAVAILABLE: exported format/modifier cannot be imported");return 77;}
    GLuint other;glGenTextures(1,&other);glBindTexture(GL_TEXTURE_2D,other);target(GL_TEXTURE_2D,imported);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,other,0);
    unsigned char pixel[4];glReadPixels(1,1,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
    int ok=glGetError()==GL_NO_ERROR&&pixel[0]>=62&&pixel[0]<=66&&pixel[1]>=126&&pixel[1]<=130&&pixel[2]>=189&&pixel[2]<=193;
    printf("%s: format=%x modifier=%llx; diagnostic readback=%u,%u,%u,%u\n",ok?"DMABUF_PROBE_OK":"DMABUF_PROBE_FAILED",fourcc,(unsigned long long)modifier,pixel[0],pixel[1],pixel[2],pixel[3]);
    glDeleteTextures(1,&other);destroy(d,imported);destroy(d,image);glDeleteFramebuffers(1,&fbo);glDeleteTextures(1,&tex);
    eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,c);eglDestroySurface(d,p);eglTerminate(d);
    return ok?0:1;
}
