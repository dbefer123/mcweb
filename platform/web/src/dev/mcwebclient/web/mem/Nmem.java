package dev.mcwebclient.web.mem;

import com.oracle.svm.core.NeverInline;

/**
 * Native memory for the WasmGC image: one exported WebAssembly linear memory ("nmem")
 * that holds everything C code would keep off-heap (LWJGL allocations, direct NIO
 * buffers, vertex and pixel data). JS can view it zero-copy, so GPU uploads read
 * straight from it.
 *
 * <p>The Java bodies are placeholders. tools/watpatch replaces each function body in
 * the generated WAT with a raw load/store, memory.copy/fill or array copy loop on
 * nmem (see tools/watpatch/intrinsics.wat). Bodies must use every parameter so the
 * compiled signature stays as declared. Addresses are byte offsets into nmem; 0 is
 * never handed out (NULL).</p>
 */
public final class Nmem {
    private Nmem() {
    }

    private static volatile long sink;

    @NeverInline("wasm intrinsic")
    public static void putByte(long addr, byte v) {
        sink = addr + v;
    }

    @NeverInline("wasm intrinsic")
    public static byte getByte(long addr) {
        return (byte) (sink + addr);
    }

    @NeverInline("wasm intrinsic")
    public static void putShort(long addr, short v) {
        sink = addr + v;
    }

    @NeverInline("wasm intrinsic")
    public static short getShort(long addr) {
        return (short) (sink + addr);
    }

    @NeverInline("wasm intrinsic")
    public static void putInt(long addr, int v) {
        sink = addr + v;
    }

    @NeverInline("wasm intrinsic")
    public static int getInt(long addr) {
        return (int) (sink + addr);
    }

    @NeverInline("wasm intrinsic")
    public static void putLong(long addr, long v) {
        sink = addr + v;
    }

    @NeverInline("wasm intrinsic")
    public static long getLong(long addr) {
        return sink + addr;
    }

    @NeverInline("wasm intrinsic")
    public static void putFloat(long addr, float v) {
        sink = addr + Float.floatToRawIntBits(v);
    }

    @NeverInline("wasm intrinsic")
    public static float getFloat(long addr) {
        return Float.intBitsToFloat((int) (sink + addr));
    }

    @NeverInline("wasm intrinsic")
    public static void putDouble(long addr, double v) {
        sink = addr + Double.doubleToRawLongBits(v);
    }

    @NeverInline("wasm intrinsic")
    public static double getDouble(long addr) {
        return Double.longBitsToDouble(sink + addr);
    }

    /** memmove(dst, src, len). */
    @NeverInline("wasm intrinsic")
    public static void copy(long src, long dst, long len) {
        sink = src + dst + len;
    }

    @NeverInline("wasm intrinsic")
    public static void fill(long dst, int value, long len) {
        sink = dst + value + len;
    }

    @NeverInline("wasm intrinsic")
    public static void copyFromBytes(byte[] src, int srcOff, long dst, int len) {
        sink = src.length + srcOff + dst + len;
    }

    @NeverInline("wasm intrinsic")
    public static void copyToBytes(long src, byte[] dst, int dstOff, int len) {
        sink = src + dst.length + dstOff + len;
    }

    /** Current size in 64 KiB pages. */
    @NeverInline("wasm intrinsic")
    public static int pages() {
        return (int) sink;
    }

    /** Grows by delta pages; returns the previous size in pages, or -1. */
    @NeverInline("wasm intrinsic")
    public static int grow(int deltaPages) {
        return (int) sink + deltaPages;
    }

    /**
     * Verifies at startup that the intrinsics were patched. An unpatched placeholder
     * would not round-trip values through memory, so this fails loudly instead of the
     * game corrupting data later.
     */
    public static void selfCheck(long scratch) {
        putInt(scratch, 0x12345678);
        putLong(scratch + 8, 0x1122334455667788L);
        putShort(scratch + 16, (short) -2);
        putByte(scratch + 18, (byte) -3);
        putFloat(scratch + 20, 1.5f);
        putDouble(scratch + 24, -2.25);
        byte[] bytes = {9, 8, 7, 6, 5};
        copyFromBytes(bytes, 0, scratch + 32, bytes.length);
        copy(scratch + 32, scratch + 40, bytes.length);
        byte[] back = new byte[bytes.length];
        copyToBytes(scratch + 40, back, 0, back.length);
        fill(scratch + 48, 0x7f, 4);
        boolean ok = getInt(scratch) == 0x12345678
                && getLong(scratch + 8) == 0x1122334455667788L
                && getShort(scratch + 16) == (short) -2
                && getByte(scratch + 18) == (byte) -3
                && getInt(scratch + 20) == Float.floatToRawIntBits(1.5f)
                && getFloat(scratch + 20) == 1.5f
                && getLong(scratch + 24) == Double.doubleToRawLongBits(-2.25)
                && getDouble(scratch + 24) == -2.25
                && java.util.Arrays.equals(bytes, back)
                && getInt(scratch + 48) == 0x7f7f7f7f
                && pages() > 0;
        if (!ok) {
            throw new IllegalStateException("nmem intrinsics are not patched (see tools/watpatch)");
        }
    }

    // ---- Typed array copies (Java loops; hot paths use the byte intrinsics above) ----

    public static void copyFromInts(int[] src, int srcOff, long dst, int count) {
        for (int i = 0; i < count; i++) {
            putInt(dst + 4L * i, src[srcOff + i]);
        }
    }

    public static void copyToInts(long src, int[] dst, int dstOff, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOff + i] = getInt(src + 4L * i);
        }
    }

    public static void copyFromFloats(float[] src, int srcOff, long dst, int count) {
        for (int i = 0; i < count; i++) {
            putFloat(dst + 4L * i, src[srcOff + i]);
        }
    }

    public static void copyToFloats(long src, float[] dst, int dstOff, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOff + i] = getFloat(src + 4L * i);
        }
    }

    public static void copyFromShorts(short[] src, int srcOff, long dst, int count) {
        for (int i = 0; i < count; i++) {
            putShort(dst + 2L * i, src[srcOff + i]);
        }
    }

    public static void copyToShorts(long src, short[] dst, int dstOff, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOff + i] = getShort(src + 2L * i);
        }
    }

    public static void copyFromChars(char[] src, int srcOff, long dst, int count) {
        for (int i = 0; i < count; i++) {
            putShort(dst + 2L * i, (short) src[srcOff + i]);
        }
    }

    public static void copyToChars(long src, char[] dst, int dstOff, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOff + i] = (char) getShort(src + 2L * i);
        }
    }

    public static void copyFromLongs(long[] src, int srcOff, long dst, int count) {
        for (int i = 0; i < count; i++) {
            putLong(dst + 8L * i, src[srcOff + i]);
        }
    }

    public static void copyToLongs(long src, long[] dst, int dstOff, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOff + i] = getLong(src + 8L * i);
        }
    }

    public static void copyFromDoubles(double[] src, int srcOff, long dst, int count) {
        for (int i = 0; i < count; i++) {
            putDouble(dst + 8L * i, src[srcOff + i]);
        }
    }

    public static void copyToDoubles(long src, double[] dst, int dstOff, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOff + i] = getDouble(src + 8L * i);
        }
    }
}
