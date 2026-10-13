package dev.mcwebclient.web.mem;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;

import org.lwjgl.system.MemoryBackend;
import org.lwjgl.system.MemoryUtil;

/**
 * LWJGL memory backend on nmem. Raw accesses are wasm loads/stores (unaligned access
 * is native in wasm, so aligned and unaligned variants are the same). Pointers are
 * 8 bytes and C longs are 8 bytes (LP64), matching the struct layouts LWJGL uses on
 * 64-bit non-Windows platforms.
 */
public final class WasmMemoryBackend implements MemoryBackend {
    // ---- scalar access ----

    @Override public boolean getBoolean(long a) { return Nmem.getByte(a) != 0; }
    @Override public byte getByte(long a) { return Nmem.getByte(a); }
    @Override public char getChar(long a) { return (char) Nmem.getShort(a); }
    @Override public char getCharUnaligned(long a) { return (char) Nmem.getShort(a); }
    @Override public short getShort(long a) { return Nmem.getShort(a); }
    @Override public short getShortUnaligned(long a) { return Nmem.getShort(a); }
    @Override public int getInt(long a) { return Nmem.getInt(a); }
    @Override public int getIntUnaligned(long a) { return Nmem.getInt(a); }
    @Override public long getLong(long a) { return Nmem.getLong(a); }
    @Override public long getLongUnaligned(long a) { return Nmem.getLong(a); }

    @Override
    public long getLong(byte[] array, long offset) {
        int i = (int) offset;
        return (array[i] & 0xffL) | (array[i + 1] & 0xffL) << 8 | (array[i + 2] & 0xffL) << 16 | (array[i + 3] & 0xffL) << 24
                | (array[i + 4] & 0xffL) << 32 | (array[i + 5] & 0xffL) << 40 | (array[i + 6] & 0xffL) << 48 | (array[i + 7] & 0xffL) << 56;
    }

    @Override public float getFloat(long a) { return Nmem.getFloat(a); }
    @Override public float getFloatUnaligned(long a) { return Nmem.getFloat(a); }
    @Override public double getDouble(long a) { return Nmem.getDouble(a); }
    @Override public double getDoubleUnaligned(long a) { return Nmem.getDouble(a); }
    @Override public long getCLong(long a) { return Nmem.getLong(a); }
    @Override public long getCLongUnaligned(long a) { return Nmem.getLong(a); }
    @Override public long getAddress(long a) { return Nmem.getLong(a); }
    @Override public long getAddressUnaligned(long a) { return Nmem.getLong(a); }

    @Override public void putBoolean(long a, boolean v) { Nmem.putByte(a, (byte) (v ? 1 : 0)); }
    @Override public void putByte(long a, byte v) { Nmem.putByte(a, v); }
    @Override public void putChar(long a, char v) { Nmem.putShort(a, (short) v); }
    @Override public void putCharUnaligned(long a, char v) { Nmem.putShort(a, (short) v); }
    @Override public void putShort(long a, short v) { Nmem.putShort(a, v); }
    @Override public void putShortUnaligned(long a, short v) { Nmem.putShort(a, v); }
    @Override public void putInt(long a, int v) { Nmem.putInt(a, v); }
    @Override public void putIntUnaligned(long a, int v) { Nmem.putInt(a, v); }
    @Override public void putLong(long a, long v) { Nmem.putLong(a, v); }
    @Override public void putLongUnaligned(long a, long v) { Nmem.putLong(a, v); }
    @Override public void putFloat(long a, float v) { Nmem.putFloat(a, v); }
    @Override public void putFloatUnaligned(long a, float v) { Nmem.putFloat(a, v); }
    @Override public void putDouble(long a, double v) { Nmem.putDouble(a, v); }
    @Override public void putDoubleUnaligned(long a, double v) { Nmem.putDouble(a, v); }
    @Override public void putCLong(long a, long v) { Nmem.putLong(a, v); }
    @Override public void putCLongUnaligned(long a, long v) { Nmem.putLong(a, v); }
    @Override public void putAddress(long a, long v) { Nmem.putLong(a, v); }
    @Override public void putAddressUnaligned(long a, long v) { Nmem.putLong(a, v); }

    // ---- buffer addresses ----

    @Override public long getAddress0(Buffer b) { return NioAccess.address(b); }
    @Override public long getAddress0(ByteBuffer b) { return NioAccess.address(b); }
    @Override public long getAddress0(CharBuffer b) { return NioAccess.address(b); }
    @Override public long getAddress0(ShortBuffer b) { return NioAccess.address(b); }
    @Override public long getAddress0(IntBuffer b) { return NioAccess.address(b); }
    @Override public long getAddress0(LongBuffer b) { return NioAccess.address(b); }
    @Override public long getAddress0(FloatBuffer b) { return NioAccess.address(b); }
    @Override public long getAddress0(DoubleBuffer b) { return NioAccess.address(b); }

    private static int shift(Buffer b) {
        if (b instanceof ByteBuffer) {
            return 0;
        }
        if (b instanceof ShortBuffer || b instanceof CharBuffer) {
            return 1;
        }
        if (b instanceof IntBuffer || b instanceof FloatBuffer) {
            return 2;
        }
        return 3;
    }

    @Override public long getAddress(Buffer b) { return NioAccess.address(b) + ((long) b.position() << shift(b)); }
    @Override public long getAddress(Buffer b, int pos) { return NioAccess.address(b) + ((long) pos << shift(b)); }
    @Override public long getAddress(ByteBuffer b) { return NioAccess.address(b) + b.position(); }
    @Override public long getAddress(ByteBuffer b, int pos) { return NioAccess.address(b) + pos; }
    @Override public long getAddress(CharBuffer b) { return NioAccess.address(b) + ((long) b.position() << 1); }
    @Override public long getAddress(CharBuffer b, int pos) { return NioAccess.address(b) + ((long) pos << 1); }
    @Override public long getAddress(ShortBuffer b) { return NioAccess.address(b) + ((long) b.position() << 1); }
    @Override public long getAddress(ShortBuffer b, int pos) { return NioAccess.address(b) + ((long) pos << 1); }
    @Override public long getAddress(IntBuffer b) { return NioAccess.address(b) + ((long) b.position() << 2); }
    @Override public long getAddress(IntBuffer b, int pos) { return NioAccess.address(b) + ((long) pos << 2); }
    @Override public long getAddress(LongBuffer b) { return NioAccess.address(b) + ((long) b.position() << 3); }
    @Override public long getAddress(LongBuffer b, int pos) { return NioAccess.address(b) + ((long) pos << 3); }
    @Override public long getAddress(FloatBuffer b) { return NioAccess.address(b) + ((long) b.position() << 2); }
    @Override public long getAddress(FloatBuffer b, int pos) { return NioAccess.address(b) + ((long) pos << 2); }
    @Override public long getAddress(DoubleBuffer b) { return NioAccess.address(b) + ((long) b.position() << 3); }
    @Override public long getAddress(DoubleBuffer b, int pos) { return NioAccess.address(b) + ((long) pos << 3); }

    // ---- wrapping ----

    @Override public ByteBuffer wrapBufferByte(long a, int cap) { return NioAccess.wrap(a, cap); }
    @Override public CharBuffer wrapBufferChar(long a, int cap) { return NioAccess.wrap(a, (long) cap << 1).asCharBuffer(); }
    @Override public ShortBuffer wrapBufferShort(long a, int cap) { return NioAccess.wrap(a, (long) cap << 1).asShortBuffer(); }
    @Override public IntBuffer wrapBufferInt(long a, int cap) { return NioAccess.wrap(a, (long) cap << 2).asIntBuffer(); }
    @Override public LongBuffer wrapBufferLong(long a, int cap) { return NioAccess.wrap(a, (long) cap << 3).asLongBuffer(); }
    @Override public FloatBuffer wrapBufferFloat(long a, int cap) { return NioAccess.wrap(a, (long) cap << 2).asFloatBuffer(); }
    @Override public DoubleBuffer wrapBufferDouble(long a, int cap) { return NioAccess.wrap(a, (long) cap << 3).asDoubleBuffer(); }

    @Override public ByteBuffer duplicate(ByteBuffer b) { return b.duplicate().order(b.order()); }
    @Override public CharBuffer duplicate(CharBuffer b) { return b.duplicate(); }
    @Override public ShortBuffer duplicate(ShortBuffer b) { return b.duplicate(); }
    @Override public IntBuffer duplicate(IntBuffer b) { return b.duplicate(); }
    @Override public LongBuffer duplicate(LongBuffer b) { return b.duplicate(); }
    @Override public FloatBuffer duplicate(FloatBuffer b) { return b.duplicate(); }
    @Override public DoubleBuffer duplicate(DoubleBuffer b) { return b.duplicate(); }

    @Override public ByteBuffer slice(ByteBuffer b) { return b.slice().order(b.order()); }
    @Override public ByteBuffer slice(ByteBuffer b, int off, int cap) { return b.slice(off, cap).order(b.order()); }
    @Override public CharBuffer slice(CharBuffer b) { return b.slice(); }
    @Override public CharBuffer slice(CharBuffer b, int off, int cap) { return b.slice(off, cap); }
    @Override public ShortBuffer slice(ShortBuffer b) { return b.slice(); }
    @Override public ShortBuffer slice(ShortBuffer b, int off, int cap) { return b.slice(off, cap); }
    @Override public IntBuffer slice(IntBuffer b) { return b.slice(); }
    @Override public IntBuffer slice(IntBuffer b, int off, int cap) { return b.slice(off, cap); }
    @Override public LongBuffer slice(LongBuffer b) { return b.slice(); }
    @Override public LongBuffer slice(LongBuffer b, int off, int cap) { return b.slice(off, cap); }
    @Override public FloatBuffer slice(FloatBuffer b) { return b.slice(); }
    @Override public FloatBuffer slice(FloatBuffer b, int off, int cap) { return b.slice(off, cap); }
    @Override public DoubleBuffer slice(DoubleBuffer b) { return b.slice(); }
    @Override public DoubleBuffer slice(DoubleBuffer b, int off, int cap) { return b.slice(off, cap); }

    // ---- bulk ----

    @Override public void memset(long ptr, int value, long bytes) { Nmem.fill(ptr, value, bytes); }
    @Override public void memcpy(long src, long dst, long bytes) { Nmem.copy(src, dst, bytes); }

    @Override public void memcpy(byte[] src, long dst, int off, int n) { Nmem.copyFromBytes(src, off, dst, n); }
    @Override public void memcpy(char[] src, long dst, int off, int n) { Nmem.copyFromChars(src, off, dst, n); }
    @Override public void memcpy(short[] src, long dst, int off, int n) { Nmem.copyFromShorts(src, off, dst, n); }
    @Override public void memcpy(int[] src, long dst, int off, int n) { Nmem.copyFromInts(src, off, dst, n); }
    @Override public void memcpy(long[] src, long dst, int off, int n) { Nmem.copyFromLongs(src, off, dst, n); }
    @Override public void memcpy(float[] src, long dst, int off, int n) { Nmem.copyFromFloats(src, off, dst, n); }
    @Override public void memcpy(double[] src, long dst, int off, int n) { Nmem.copyFromDoubles(src, off, dst, n); }

    @Override public void memcpy(long src, byte[] dst, int off, int n) { Nmem.copyToBytes(src, dst, off, n); }
    @Override public void memcpy(long src, char[] dst, int off, int n) { Nmem.copyToChars(src, dst, off, n); }
    @Override public void memcpy(long src, short[] dst, int off, int n) { Nmem.copyToShorts(src, dst, off, n); }
    @Override public void memcpy(long src, int[] dst, int off, int n) { Nmem.copyToInts(src, dst, off, n); }
    @Override public void memcpy(long src, long[] dst, int off, int n) { Nmem.copyToLongs(src, dst, off, n); }
    @Override public void memcpy(long src, float[] dst, int off, int n) { Nmem.copyToFloats(src, dst, off, n); }
    @Override public void memcpy(long src, double[] dst, int off, int n) { Nmem.copyToDoubles(src, dst, off, n); }

    // ---- strings ----

    @Override
    public void putStringASCII(String text, boolean nullTerminated, long target) {
        putStringASCII((CharSequence) text, nullTerminated, target);
    }

    @Override
    public void putStringASCII(CharSequence text, boolean nullTerminated, long target) {
        int n = text.length();
        for (int i = 0; i < n; i++) {
            Nmem.putByte(target + i, (byte) text.charAt(i));
        }
        if (nullTerminated) {
            Nmem.putByte(target + n, (byte) 0);
        }
    }

    @Override
    public ByteBuffer allocateUTF8(String text, boolean nullTerminated, MemoryUtil.MemoryAllocator allocator) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int size = bytes.length + (nullTerminated ? 1 : 0);
        long addr = allocator.malloc(size);
        Nmem.copyFromBytes(bytes, 0, addr, bytes.length);
        if (nullTerminated) {
            Nmem.putByte(addr + bytes.length, (byte) 0);
        }
        return NioAccess.wrap(addr, size);
    }

    @Override
    public void putStringUTF8(CharSequence text, boolean nullTerminated, long target) {
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        Nmem.copyFromBytes(bytes, 0, target, bytes.length);
        if (nullTerminated) {
            Nmem.putByte(target + bytes.length, (byte) 0);
        }
    }

    @Override
    public int putStringUTF8(String text, boolean nullTerminated, long target, int maxLength) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length + (nullTerminated ? 1 : 0);
        if (length > maxLength) {
            throw new java.nio.BufferOverflowException();
        }
        Nmem.copyFromBytes(bytes, 0, target, bytes.length);
        if (nullTerminated) {
            Nmem.putByte(target + bytes.length, (byte) 0);
        }
        return length;
    }

    @Override
    public void putStringUTF16(String text, boolean nullTerminated, long target) {
        putStringUTF16((CharSequence) text, nullTerminated, target);
    }

    @Override
    public void putStringUTF16(CharSequence text, boolean nullTerminated, long target) {
        int n = text.length();
        for (int i = 0; i < n; i++) {
            Nmem.putShort(target + 2L * i, (short) text.charAt(i));
        }
        if (nullTerminated) {
            Nmem.putShort(target + 2L * n, (short) 0);
        }
    }

    static {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new AssertionError("nmem is little-endian");
        }
    }
}
