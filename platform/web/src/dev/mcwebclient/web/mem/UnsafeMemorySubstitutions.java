package dev.mcwebclient.web.mem;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Unsafe's raw memory on nmem: allocateMemory/freeMemory/reallocateMemory use
 * {@link NativeHeap}, and setMemory/copyMemory with a null base (an absolute address)
 * act on nmem. Web Image has no unmanaged memory of its own (its malloc throws), so this
 * is what makes ByteBuffer.allocateDirect and FFM native segments work; direct buffers
 * are then read and written through ScopedMemoryAccess (ScopedMemoryAccessSubstitutions).
 *
 * <p>Heap (array) cases are done here too: an @Alias of the private natives would bind to
 * the bare natives, not to SVM's substitutions of them ("unsupported native method").
 */
final class UnsafeMemorySubstitutions {
    private UnsafeMemorySubstitutions() {
    }
}

@TargetClass(className = "jdk.internal.misc.Unsafe")
final class Target_jdk_internal_misc_Unsafe_Memory {
    @Substitute
    public long allocateMemory(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative size " + bytes);
        }
        return bytes == 0 ? 0 : NativeHeap.malloc(bytes);
    }

    @Substitute
    public long reallocateMemory(long address, long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative size " + bytes);
        }
        if (bytes == 0) {
            NativeHeap.free(address);
            return 0;
        }
        return NativeHeap.realloc(address, bytes);
    }

    @Substitute
    public void freeMemory(long address) {
        NativeHeap.free(address);
    }

    @Substitute
    public void setMemory(Object o, long offset, long bytes, byte value) {
        if (bytes <= 0) {
            return;
        }
        if (o == null) {
            Nmem.fill(offset, value, bytes);
        } else {
            ArrayCopies.fillHeap(o, offset, bytes, value);
        }
    }

    @Substitute
    public void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes) {
        if (bytes <= 0) {
            return;
        }
        if (srcBase == null && destBase == null) {
            Nmem.copy(srcOffset, destOffset, bytes);
        } else if (destBase == null) {
            ArrayCopies.arrayToNmem(srcBase, srcOffset, destOffset, bytes);
        } else if (srcBase == null) {
            ArrayCopies.nmemToArray(srcOffset, destBase, destOffset, bytes);
        } else {
            ArrayCopies.heapToHeap(srcBase, srcOffset, destBase, destOffset, bytes);
        }
    }
}
