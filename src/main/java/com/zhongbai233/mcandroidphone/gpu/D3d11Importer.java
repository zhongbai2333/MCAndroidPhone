package com.zhongbai233.mcandroidphone.gpu;

import com.zhongbai233.mcandroidphone.core.GpuFrame;
import org.lwjgl.opengl.*;

/** Imports the producer allocation without CPU pixels. One GPU copy preserves MC's deferred/static draw. */
public final class D3d11Importer implements AutoCloseable {
    private D3d11ReleaseVerifier verifier;
    public D3d11Importer() {}
    @Override public void close() {
        if (verifier != null) { verifier.close(); verifier = null; }
    }
    public static boolean supported() {
        var caps = GL.getCapabilities();
        return caps.GL_EXT_memory_object && caps.GL_EXT_memory_object_win32 &&
            caps.GL_EXT_win32_keyed_mutex && (caps.OpenGL43 || caps.GL_ARB_copy_image);
    }
    public boolean copyTo(GpuFrame frame, int destination) {
        if (!supported()) throw new IllegalStateException("GPU sharing needs GL_EXT_memory_object_win32, keyed_mutex and copy_image");
        int memory = EXTMemoryObject.glCreateMemoryObjectsEXT();
        int texture = GL11C.glGenTextures();
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        boolean acquired = false;
        try {
            EXTMemoryObject.glMemoryObjectParameteriEXT(memory, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11C.GL_TRUE);
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memory, 0,
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT, frame.handle());
            check("D3D11 import (same GPU required)");
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            var n = frame.notice();
            EXTMemoryObject.glTexStorageMem2DEXT(GL11C.GL_TEXTURE_2D, 1, GL11C.GL_RGBA8,
                n.textureWidth(), n.textureHeight(), memory, 0);
            check("D3D11 texture storage");
            acquired = EXTWin32KeyedMutex.glAcquireKeyedMutexWin32EXT(memory, 0, 3);
            check("GPU mutex acquire");
            if (!acquired) return false; // Busy GPU: drop this update, keep the last cached phone image.
            ARBCopyImage.glCopyImageSubData(texture, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                destination, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, n.textureWidth(), n.textureHeight(), 1);
            check("GPU texture copy");
            return true;
        } finally {
            try {
                if (acquired) {
                    boolean released = EXTWin32KeyedMutex.glReleaseKeyedMutexWin32EXT(memory, 0);
                    check("GPU mutex release");
                    if (!released) {
                        // Some drivers report false even after releasing. Never ignore it:
                        // independently acquire/release the same resource through DXGI.
                        if (verifier == null) {
                            verifier = new D3d11ReleaseVerifier();
                            System.getLogger("mcandroidphone.gpu").log(System.Logger.Level.INFO,
                                "GL release returned false without GL error; verifying every release through DXGI");
                        }
                        verifier.verifyAndRelease(frame.handle());
                    }
                }
            } finally {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
                GL11C.glDeleteTextures(texture);
                EXTMemoryObject.glDeleteMemoryObjectsEXT(memory);
            }
        }
    }
    private static void check(String operation) {
        int error = GL11C.glGetError();
        if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException(operation+": GL error "+error);
    }
}
