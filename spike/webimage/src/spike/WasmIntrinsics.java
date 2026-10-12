package spike;

import com.oracle.svm.core.NeverInline;

/**
 * Raw linear-memory access for the WasmGC image. The Java bodies are placeholders:
 * tools/watpatch replaces each function body in the generated WAT with a direct
 * load/store on an exported linear memory ("nmem"), which JS can view zero-copy.
 * Bodies must keep every parameter "used" so the compiled signature is stable.
 */
public final class WasmIntrinsics {
    private WasmIntrinsics() {
    }

    static volatile long sink;

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

    /** memmove semantics. */
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

    /** Current memory size in 64 KiB pages. */
    @NeverInline("wasm intrinsic")
    public static int pages() {
        return (int) sink;
    }

    /** Grows memory by delta pages; returns the previous size or -1. */
    @NeverInline("wasm intrinsic")
    public static int grow(int deltaPages) {
        return (int) sink + deltaPages;
    }
}
