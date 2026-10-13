package dev.mcwebclient.web.mem;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import jdk.internal.foreign.MemorySessionImpl;

/**
 * Routes off-heap NIO accesses to nmem. Every direct-buffer read/write in the JDK goes
 * through jdk.internal.misc.ScopedMemoryAccess with base == null and offset == native
 * address; heap buffers pass their backing array as base. Null base → nmem (with byte
 * swapping when big-endian is requested); anything else → the JDK's own private
 * implementation, unchanged.
 */
final class ScopedMemoryAccessSubstitutions {
    private ScopedMemoryAccessSubstitutions() {
    }
}

@TargetClass(className = "jdk.internal.misc.ScopedMemoryAccess")
final class Target_jdk_internal_misc_ScopedMemoryAccess {
    // ---- original implementations for the heap case ----
    @Alias private native byte getByteInternal(MemorySessionImpl s, Object b, long o);
    @Alias private native void putByteInternal(MemorySessionImpl s, Object b, long o, byte v);
    @Alias private native short getShortInternal(MemorySessionImpl s, Object b, long o);
    @Alias private native void putShortInternal(MemorySessionImpl s, Object b, long o, short v);
    @Alias private native char getCharInternal(MemorySessionImpl s, Object b, long o);
    @Alias private native void putCharInternal(MemorySessionImpl s, Object b, long o, char v);
    @Alias private native int getIntInternal(MemorySessionImpl s, Object b, long o);
    @Alias private native void putIntInternal(MemorySessionImpl s, Object b, long o, int v);
    @Alias private native long getLongInternal(MemorySessionImpl s, Object b, long o);
    @Alias private native void putLongInternal(MemorySessionImpl s, Object b, long o, long v);
    @Alias private native float getFloatInternal(MemorySessionImpl s, Object b, long o);
    @Alias private native void putFloatInternal(MemorySessionImpl s, Object b, long o, float v);
    @Alias private native double getDoubleInternal(MemorySessionImpl s, Object b, long o);
    @Alias private native void putDoubleInternal(MemorySessionImpl s, Object b, long o, double v);
    @Alias private native short getShortUnalignedInternal(MemorySessionImpl s, Object b, long o, boolean be);
    @Alias private native void putShortUnalignedInternal(MemorySessionImpl s, Object b, long o, short v, boolean be);
    @Alias private native char getCharUnalignedInternal(MemorySessionImpl s, Object b, long o, boolean be);
    @Alias private native void putCharUnalignedInternal(MemorySessionImpl s, Object b, long o, char v, boolean be);
    @Alias private native int getIntUnalignedInternal(MemorySessionImpl s, Object b, long o, boolean be);
    @Alias private native void putIntUnalignedInternal(MemorySessionImpl s, Object b, long o, int v, boolean be);
    @Alias private native long getLongUnalignedInternal(MemorySessionImpl s, Object b, long o, boolean be);
    @Alias private native void putLongUnalignedInternal(MemorySessionImpl s, Object b, long o, long v, boolean be);
    @Alias private native void copyMemoryInternal(MemorySessionImpl ss, MemorySessionImpl ds, Object sb, long so,
                                                  Object db, long dOff, long bytes);
    @Alias private native void setMemoryInternal(MemorySessionImpl s, Object o, long off, long bytes, byte v);

    // ---- aligned (native order) ----

    @Substitute
    public byte getByte(MemorySessionImpl s, Object b, long o) {
        return b == null ? Nmem.getByte(o) : getByteInternal(s, b, o);
    }

    @Substitute
    public void putByte(MemorySessionImpl s, Object b, long o, byte v) {
        if (b == null) {
            Nmem.putByte(o, v);
        } else {
            putByteInternal(s, b, o, v);
        }
    }

    @Substitute
    public short getShort(MemorySessionImpl s, Object b, long o) {
        return b == null ? Nmem.getShort(o) : getShortInternal(s, b, o);
    }

    @Substitute
    public void putShort(MemorySessionImpl s, Object b, long o, short v) {
        if (b == null) {
            Nmem.putShort(o, v);
        } else {
            putShortInternal(s, b, o, v);
        }
    }

    @Substitute
    public char getChar(MemorySessionImpl s, Object b, long o) {
        return b == null ? (char) Nmem.getShort(o) : getCharInternal(s, b, o);
    }

    @Substitute
    public void putChar(MemorySessionImpl s, Object b, long o, char v) {
        if (b == null) {
            Nmem.putShort(o, (short) v);
        } else {
            putCharInternal(s, b, o, v);
        }
    }

    @Substitute
    public int getInt(MemorySessionImpl s, Object b, long o) {
        return b == null ? Nmem.getInt(o) : getIntInternal(s, b, o);
    }

    @Substitute
    public void putInt(MemorySessionImpl s, Object b, long o, int v) {
        if (b == null) {
            Nmem.putInt(o, v);
        } else {
            putIntInternal(s, b, o, v);
        }
    }

    @Substitute
    public long getLong(MemorySessionImpl s, Object b, long o) {
        return b == null ? Nmem.getLong(o) : getLongInternal(s, b, o);
    }

    @Substitute
    public void putLong(MemorySessionImpl s, Object b, long o, long v) {
        if (b == null) {
            Nmem.putLong(o, v);
        } else {
            putLongInternal(s, b, o, v);
        }
    }

    @Substitute
    public float getFloat(MemorySessionImpl s, Object b, long o) {
        return b == null ? Nmem.getFloat(o) : getFloatInternal(s, b, o);
    }

    @Substitute
    public void putFloat(MemorySessionImpl s, Object b, long o, float v) {
        if (b == null) {
            Nmem.putFloat(o, v);
        } else {
            putFloatInternal(s, b, o, v);
        }
    }

    @Substitute
    public double getDouble(MemorySessionImpl s, Object b, long o) {
        return b == null ? Nmem.getDouble(o) : getDoubleInternal(s, b, o);
    }

    @Substitute
    public void putDouble(MemorySessionImpl s, Object b, long o, double v) {
        if (b == null) {
            Nmem.putDouble(o, v);
        } else {
            putDoubleInternal(s, b, o, v);
        }
    }

    // ---- unaligned with explicit byte order (nmem is little-endian) ----

    @Substitute
    public short getShortUnaligned(MemorySessionImpl s, Object b, long o, boolean be) {
        if (b != null) {
            return getShortUnalignedInternal(s, b, o, be);
        }
        short v = Nmem.getShort(o);
        return be ? Short.reverseBytes(v) : v;
    }

    @Substitute
    public void putShortUnaligned(MemorySessionImpl s, Object b, long o, short v, boolean be) {
        if (b != null) {
            putShortUnalignedInternal(s, b, o, v, be);
        } else {
            Nmem.putShort(o, be ? Short.reverseBytes(v) : v);
        }
    }

    @Substitute
    public char getCharUnaligned(MemorySessionImpl s, Object b, long o, boolean be) {
        if (b != null) {
            return getCharUnalignedInternal(s, b, o, be);
        }
        char v = (char) Nmem.getShort(o);
        return be ? Character.reverseBytes(v) : v;
    }

    @Substitute
    public void putCharUnaligned(MemorySessionImpl s, Object b, long o, char v, boolean be) {
        if (b != null) {
            putCharUnalignedInternal(s, b, o, v, be);
        } else {
            Nmem.putShort(o, (short) (be ? Character.reverseBytes(v) : v));
        }
    }

    @Substitute
    public int getIntUnaligned(MemorySessionImpl s, Object b, long o, boolean be) {
        if (b != null) {
            return getIntUnalignedInternal(s, b, o, be);
        }
        int v = Nmem.getInt(o);
        return be ? Integer.reverseBytes(v) : v;
    }

    @Substitute
    public void putIntUnaligned(MemorySessionImpl s, Object b, long o, int v, boolean be) {
        if (b != null) {
            putIntUnalignedInternal(s, b, o, v, be);
        } else {
            Nmem.putInt(o, be ? Integer.reverseBytes(v) : v);
        }
    }

    @Substitute
    public long getLongUnaligned(MemorySessionImpl s, Object b, long o, boolean be) {
        if (b != null) {
            return getLongUnalignedInternal(s, b, o, be);
        }
        long v = Nmem.getLong(o);
        return be ? Long.reverseBytes(v) : v;
    }

    @Substitute
    public void putLongUnaligned(MemorySessionImpl s, Object b, long o, long v, boolean be) {
        if (b != null) {
            putLongUnalignedInternal(s, b, o, v, be);
        } else {
            Nmem.putLong(o, be ? Long.reverseBytes(v) : v);
        }
    }

    // ---- bulk ----

    @Substitute
    public void copyMemory(MemorySessionImpl ss, MemorySessionImpl ds, Object sb, long so, Object db, long dOff, long bytes) {
        if (sb == null && db == null) {
            Nmem.copy(so, dOff, bytes);
        } else if (db == null) {
            ArrayCopies.arrayToNmem(sb, so, dOff, bytes);
        } else if (sb == null) {
            ArrayCopies.nmemToArray(so, db, dOff, bytes);
        } else {
            copyMemoryInternal(ss, ds, sb, so, db, dOff, bytes);
        }
    }

    @Substitute
    public void copySwapMemory(MemorySessionImpl ss, MemorySessionImpl ds, Object sb, long so, Object db, long dOff,
                               long bytes, long elemSize) {
        // Non-native-order bulk copies (e.g. ICU reading big-endian data into a char[]).
        // Web Image has no Unsafe.copySwapMemory0, even for heap-to-heap copies, so swap
        // element by element in a byte[].
        byte[] tmp = new byte[(int) bytes];
        long tmpBase = sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;
        copyMemory(ss, null, sb, so, tmp, tmpBase, bytes);
        for (int i = 0; i + elemSize <= bytes; i += (int) elemSize) {
            for (int a = i, z = (int) (i + elemSize - 1); a < z; a++, z--) {
                byte t = tmp[a];
                tmp[a] = tmp[z];
                tmp[z] = t;
            }
        }
        copyMemory(null, ds, tmp, tmpBase, db, dOff, bytes);
    }

    @Substitute
    public void setMemory(MemorySessionImpl s, Object o, long off, long bytes, byte v) {
        if (o == null) {
            Nmem.fill(off, v, bytes);
        } else {
            setMemoryInternal(s, o, off, bytes, v);
        }
    }
}
