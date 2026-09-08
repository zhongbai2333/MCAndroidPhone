// Diagnostic readback only. Production must never read pixels through CPU memory.
// Proves Metal -> IOSurface -> CGL -> one GPU cache copy on the actual Mac.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <IOSurface/IOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/CGLIOSurface.h>
#include <stdlib.h>
#import <OpenGL/gl3.h>
#include <stdio.h>

int main(void) { @autoreleasepool {
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();
    if(!device){fprintf(stderr,"UNAVAILABLE: Metal device\n");return 77;}
    size_t pitch=IOSurfaceAlignProperty(kIOSurfaceBytesPerRow,64*4);
    NSDictionary *props=@{(id)kIOSurfaceWidth:@64,(id)kIOSurfaceHeight:@64,
        (id)kIOSurfaceBytesPerElement:@4,(id)kIOSurfaceBytesPerRow:@(pitch),
        (id)kIOSurfaceAllocSize:@(pitch*64),(id)kIOSurfacePixelFormat:@(0x42475241)};
    IOSurfaceRef surface=IOSurfaceCreate((CFDictionaryRef)props);
    if(!surface){fprintf(stderr,"FAIL: IOSurfaceCreate\n");return 1;}
    MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:64 height:64 mipmapped:NO];
    desc.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    desc.storageMode=MTLStorageModeShared;
    id<MTLTexture> metal=[device newTextureWithDescriptor:desc iosurface:surface plane:0];
    if(!metal){fprintf(stderr,"FAIL: IOSurface Metal texture\n");return 1;}
    id<MTLCommandQueue> queue=[device newCommandQueue];
    id<MTLCommandBuffer> command=[queue commandBuffer];
    MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture=metal;
    pass.colorAttachments[0].loadAction=MTLLoadActionClear;
    pass.colorAttachments[0].storeAction=MTLStoreActionStore;
    pass.colorAttachments[0].clearColor=MTLClearColorMake(.25,.5,.75,1);
    [[command renderCommandEncoderWithDescriptor:pass] endEncoding];
    [command commit];[command waitUntilCompleted];
    if(command.status!=MTLCommandBufferStatusCompleted){fprintf(stderr,"FAIL: Metal clear\n");return 1;}
    CGLPixelFormatAttribute attrs[]={kCGLPFAOpenGLProfile,(CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,kCGLPFAAccelerated,0};
    CGLPixelFormatObj format=NULL;GLint count=0;CGLContextObj context=NULL;
    if(CGLChoosePixelFormat(attrs,&format,&count)!=kCGLNoError || !format ||
       CGLCreateContext(format,NULL,&context)!=kCGLNoError || CGLSetCurrentContext(context)!=kCGLNoError){
        fprintf(stderr,"UNAVAILABLE: accelerated CGL context\n");return 77;}
    GLuint source=0,destination=0,fbo[2];glGenTextures(1,&source);glBindTexture(GL_TEXTURE_RECTANGLE,source);
    CGLError error=CGLTexImageIOSurface2D(context,GL_TEXTURE_RECTANGLE,GL_RGBA8,64,64,GL_BGRA,GL_UNSIGNED_INT_8_8_8_8_REV,surface,0);
    if(error!=kCGLNoError){fprintf(stderr,"FAIL: CGL IOSurface import %d\n",error);return 1;}
    glGenTextures(1,&destination);glBindTexture(GL_TEXTURE_2D,destination);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,64,64,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    glGenFramebuffers(2,fbo);glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo[0]);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_RECTANGLE,source,0);
    if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)return 1;
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo[1]);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,destination,0);
    if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)return 1;
    glBlitFramebuffer(0,0,64,64,0,0,64,64,GL_COLOR_BUFFER_BIT,GL_NEAREST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo[1]);unsigned char pixel[4]={0};
    glReadPixels(32,32,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);GLenum glError=glGetError();
    int ok=glError==GL_NO_ERROR && abs(pixel[0]-64)<=2 && abs(pixel[1]-128)<=2 && abs(pixel[2]-191)<=2 && pixel[3]==255;
    printf("%s: Metal=%s CGL=%s pixel=%u,%u,%u,%u GLerror=%u\n",ok?"IOSURFACE_PROBE_OK":"IOSURFACE_PROBE_FAILED",
        device.name.UTF8String,glGetString(GL_RENDERER),pixel[0],pixel[1],pixel[2],pixel[3],glError);
    printf("Scope: same-process allocation/import/cache copy only; QEMU IOSurface export and cross-process synchronization remain unverified.\n");
    glDeleteFramebuffers(2,fbo);glDeleteTextures(1,&source);glDeleteTextures(1,&destination);
    CGLSetCurrentContext(NULL);CGLDestroyContext(context);CGLDestroyPixelFormat(format);CFRelease(surface);
    return ok?0:1;
}}
