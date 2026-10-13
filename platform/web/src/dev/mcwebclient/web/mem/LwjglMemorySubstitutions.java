package dev.mcwebclient.web.mem;

import java.util.function.Consumer;

import org.lwjgl.system.MemoryBackend;
import org.lwjgl.system.MemoryUtil;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * LWJGL core without its JNI library: memory goes to nmem (WasmMemoryBackend +
 * NativeHeap) and native-library loading becomes a no-op. Module bindings (SDL,
 * OpenAL, ...) are replaced function by function elsewhere.
 */
final class LwjglMemorySubstitutions {
    private LwjglMemorySubstitutions() {
    }
}

/** malloc/free for LWJGL, backed by {@link NativeHeap}. Function pointers don't exist. */
final class NmemAllocator implements MemoryUtil.MemoryAllocator {
    static final NmemAllocator INSTANCE = new NmemAllocator();

    @Override public long getMalloc() { return 0; }
    @Override public long getCalloc() { return 0; }
    @Override public long getRealloc() { return 0; }
    @Override public long getFree() { return 0; }
    @Override public long getAlignedAlloc() { return 0; }
    @Override public long getAlignedFree() { return 0; }
    @Override public long malloc(long size) { return NativeHeap.malloc(size); }
    @Override public long calloc(long num, long size) { return NativeHeap.calloc(num, size); }
    @Override public long realloc(long ptr, long size) { return NativeHeap.realloc(ptr, size); }
    @Override public void free(long ptr) { NativeHeap.free(ptr); }
    @Override public long aligned_alloc(long alignment, long size) { return NativeHeap.alignedAlloc(alignment, size); }
    @Override public void aligned_free(long ptr) { NativeHeap.free(ptr); }
}

@TargetClass(MemoryUtil.class)
final class Target_org_lwjgl_system_MemoryUtil {
    @Substitute
    private static MemoryBackend createBackend() {
        return new WasmMemoryBackend();
    }

    @Substitute
    static int ngetPageSize() {
        return 4096;
    }

    @Substitute
    static int ngetCacheLineSize() {
        return 64;
    }
}

@TargetClass(className = "org.lwjgl.system.MemoryManage")
final class Target_org_lwjgl_system_MemoryManage {
    @Substitute
    static MemoryUtil.MemoryAllocator getInstance() {
        return NmemAllocator.INSTANCE;
    }
}

@TargetClass(className = "org.lwjgl.system.MemoryAccessJNI")
final class Target_org_lwjgl_system_MemoryAccessJNI {
    @Substitute
    static int getPointerSize() {
        return 8;
    }

    @Substitute
    private static long malloc() {
        return 0;
    }

    @Substitute
    private static long calloc() {
        return 0;
    }

    @Substitute
    private static long realloc() {
        return 0;
    }

    @Substitute
    private static long free() {
        return 0;
    }

    @Substitute
    private static long aligned_alloc() {
        return 0;
    }

    @Substitute
    private static long aligned_free() {
        return 0;
    }
}

@TargetClass(className = "org.lwjgl.system.Library")
final class Target_org_lwjgl_system_Library {
    /** There are no JNI libraries in the browser; bindings are substituted instead. */
    @Substitute
    public static void loadSystem(String module, String name) {
    }

    @Substitute
    public static void loadSystem(Consumer<String> load, Consumer<String> loadLibrary, Class<?> context,
                                  String module, String name) {
    }
}
