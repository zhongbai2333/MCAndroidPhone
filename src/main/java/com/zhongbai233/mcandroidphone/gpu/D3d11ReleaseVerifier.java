package com.zhongbai233.mcandroidphone.gpu;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.system.MemoryStack;
import static java.lang.foreign.ValueLayout.*;

/** Verifies a driver's ambiguous GL release using the documented DXGI HRESULTs.
 * No staging textures/readback. Adapter matches the current OpenGL device LUID.
 */
final class D3d11ReleaseVerifier implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Map<String, SymbolLookup> LIBRARIES = Map.of(
        "dxgi", SymbolLookup.libraryLookup("dxgi", Arena.global()),
        "d3d11", SymbolLookup.libraryLookup("d3d11", Arena.global()));
    private static final MemorySegment NULL = MemorySegment.NULL;
    private static final FunctionDescriptor RELEASE = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor QUERY = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private final Arena arena = Arena.ofConfined();
    private MemorySegment device = NULL;
    private final MemorySegment textureGuid = guid("6f15aaf2-d208-4e89-9ab4-489535d34f9c");
    private final MemorySegment mutexGuid = guid("9d8e1289-d7b3-465f-8126-250e349af85d");

    private static MethodHandle symbol(String dll, String name, FunctionDescriptor type) {
        return LINKER.downcallHandle(LIBRARIES.get(dll).find(name).orElseThrow(), type);
    }
    private static Object call(MemorySegment object, int index, FunctionDescriptor type, Object... args) throws Throwable {
        MemorySegment table = object.reinterpret(8).get(ADDRESS,0).reinterpret((index+1L)*8);
        MethodHandle method = LINKER.downcallHandle(table.get(ADDRESS,index*8L),type);
        List<Object> values=new ArrayList<>(); values.add(object); Collections.addAll(values,args);
        return method.invokeWithArguments(values);
    }
    private MemorySegment guid(String text) {
        UUID id=UUID.fromString(text); MemorySegment data=arena.allocate(16,4);
        long hi=id.getMostSignificantBits(),lo=id.getLeastSignificantBits();
        data.set(JAVA_INT,0,(int)(hi>>>32));data.set(JAVA_SHORT,4,(short)(hi>>>16));data.set(JAVA_SHORT,6,(short)hi);
        for(int i=0;i<8;i++)data.set(JAVA_BYTE,8+i,(byte)(lo>>>(56-8*i)));
        return data;
    }
    private static void check(int result,String operation) {
        if(result!=0)throw new IllegalStateException(operation+": HRESULT 0x"+Integer.toHexString(result));
    }
    private static void release(MemorySegment object) {
        if(object.address()!=0)try { call(object,2,RELEASE); } catch(Throwable ignored) {}
    }
    D3d11ReleaseVerifier() {
        MemorySegment factory=NULL,adapter=NULL,base=NULL;
        try {
            byte[] luid=new byte[8];
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var bytes=stack.malloc(8);
                EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT,bytes);
                bytes.get(luid);
            }
            MemorySegment output=arena.allocate(ADDRESS);
            int hr=(int)symbol("dxgi","CreateDXGIFactory1",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS))
                .invokeExact(guid("770aae78-f26f-4dba-a829-253c83d1b387"),output);
            check(hr,"CreateDXGIFactory1");factory=output.get(ADDRESS,0);
            for(int i=0;i<32;i++) {
                hr=(int)call(factory,12,FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,ADDRESS),i,output);
                if(hr!=0)break;
                MemorySegment candidate=output.get(ADDRESS,0),desc=arena.allocate(312,8);
                try {
                    check((int)call(candidate,10,FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS),desc),"GetDesc1");
                    if(Arrays.equals(desc.asSlice(296,8).toArray(JAVA_BYTE),luid)) {adapter=candidate;break;}
                } finally {if(adapter.address()!=candidate.address())release(candidate);}
            }
            if(adapter.address()==0)throw new IllegalStateException("No DXGI adapter matches the OpenGL GPU");
            var create=symbol("d3d11","D3D11CreateDevice",FunctionDescriptor.of(JAVA_INT,
                ADDRESS,JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,ADDRESS,ADDRESS,ADDRESS));
            hr=(int)create.invokeExact(adapter,0,NULL,0x20,NULL,0,7,output,NULL,NULL);
            check(hr,"D3D11CreateDevice");base=output.get(ADDRESS,0);
            check((int)call(base,0,QUERY,guid("a04bfb29-08ef-43d6-a49c-a9bdbdcbe686"),output),"Query D3D11Device1");
            device=output.get(ADDRESS,0);
        } catch(Throwable error) {close();throw new IllegalStateException("Cannot verify shared GPU mutex",error);}
        finally {release(base);release(adapter);release(factory);}
    }
    void verifyAndRelease(long textureHandle) {
        MemorySegment texture=NULL,mutex=NULL;
        try(Arena temporary=Arena.ofConfined()) {
            MemorySegment output=temporary.allocate(ADDRESS);
            check((int)call(device,48,FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS,ADDRESS),
                MemorySegment.ofAddress(textureHandle),textureGuid,output),"OpenSharedResource1");
            texture=output.get(ADDRESS,0);
            check((int)call(texture,0,QUERY,mutexGuid,output),"Query KeyedMutex");
            mutex=output.get(ADDRESS,0);
            // If GL actually failed to release, this bounded acquire fails and we do NOT ACK as success.
            check((int)call(mutex,8,FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_LONG,JAVA_INT),0L,50),"Verify GL mutex release");
            check((int)call(mutex,9,FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_LONG),0L),"DXGI ReleaseSync");
        } catch(Throwable error) {throw new IllegalStateException("GPU mutex release verification failed",error);}
        finally {release(mutex);release(texture);}
    }
    @Override public void close() {release(device);device=NULL;arena.close();}
}
