package com.zhongbai233.mcandroidphone.core;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Windows HANDLE ownership using JDK FFM; no Minecraft/LWJGL/native helper dependency. */
public final class Win32Handles {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static MethodHandle function(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(KERNEL.find(name).orElseThrow(), descriptor);
    }
    private static final MethodHandle OPEN = function("OpenProcess", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle CURRENT = function("GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle DUPLICATE = function("DuplicateHandle", FunctionDescriptor.of(JAVA_INT,
        ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle CLOSE = function("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private Win32Handles() {}

    public static long openSource(int pid) {
        try {
            MemorySegment result = (MemorySegment) OPEN.invokeExact(0x40, 0, pid); // PROCESS_DUP_HANDLE only
            if (result.address() == 0) throw new IllegalStateException("Cannot open GPU bridge process");
            return result.address();
        } catch (Throwable error) { throw failure(error); }
    }

    public static long duplicate(long process, long source) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(ADDRESS);
            MemorySegment current = (MemorySegment) CURRENT.invokeExact();
            int ok = (int) DUPLICATE.invokeExact(MemorySegment.ofAddress(process), MemorySegment.ofAddress(source),
                current, output, 0, 0, 2); // DUPLICATE_SAME_ACCESS; source remains leased to this connection
            if (ok == 0) throw new IllegalStateException("Cannot duplicate GPU texture handle");
            return output.get(ADDRESS, 0).address();
        } catch (Throwable error) { throw failure(error); }
    }

    public static void close(long handle) {
        if (handle == 0) return;
        try { int ignored = (int) CLOSE.invokeExact(MemorySegment.ofAddress(handle)); }
        catch (Throwable error) { throw failure(error); }
    }
    private static RuntimeException failure(Throwable error) {
        return error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
    }
}
