package dev.mcwebclient.web.mem;

import sun.misc.Unsafe;

/**
 * Bulk copies between Java primitive arrays and nmem, for the JDK's Unsafe-style
 * (array, offset) addressing: offset = array base offset + index * element size.
 */
final class ArrayCopies {
    private ArrayCopies() {
    }

    static void arrayToNmem(Object array, long arrayOffset, long dst, long bytes) {
        int n = (int) bytes;
        if (array instanceof byte[] a) {
            Nmem.copyFromBytes(a, (int) (arrayOffset - Unsafe.ARRAY_BYTE_BASE_OFFSET), dst, n);
        } else if (array instanceof int[] a) {
            Nmem.copyFromInts(a, index(arrayOffset, Unsafe.ARRAY_INT_BASE_OFFSET, 4), dst, n >> 2);
        } else if (array instanceof float[] a) {
            Nmem.copyFromFloats(a, index(arrayOffset, Unsafe.ARRAY_FLOAT_BASE_OFFSET, 4), dst, n >> 2);
        } else if (array instanceof short[] a) {
            Nmem.copyFromShorts(a, index(arrayOffset, Unsafe.ARRAY_SHORT_BASE_OFFSET, 2), dst, n >> 1);
        } else if (array instanceof char[] a) {
            Nmem.copyFromChars(a, index(arrayOffset, Unsafe.ARRAY_CHAR_BASE_OFFSET, 2), dst, n >> 1);
        } else if (array instanceof long[] a) {
            Nmem.copyFromLongs(a, index(arrayOffset, Unsafe.ARRAY_LONG_BASE_OFFSET, 8), dst, n >> 3);
        } else if (array instanceof double[] a) {
            Nmem.copyFromDoubles(a, index(arrayOffset, Unsafe.ARRAY_DOUBLE_BASE_OFFSET, 8), dst, n >> 3);
        } else {
            throw new IllegalArgumentException("unsupported array copy source " + array.getClass());
        }
    }

    static void nmemToArray(long src, Object array, long arrayOffset, long bytes) {
        int n = (int) bytes;
        if (array instanceof byte[] a) {
            Nmem.copyToBytes(src, a, (int) (arrayOffset - Unsafe.ARRAY_BYTE_BASE_OFFSET), n);
        } else if (array instanceof int[] a) {
            Nmem.copyToInts(src, a, index(arrayOffset, Unsafe.ARRAY_INT_BASE_OFFSET, 4), n >> 2);
        } else if (array instanceof float[] a) {
            Nmem.copyToFloats(src, a, index(arrayOffset, Unsafe.ARRAY_FLOAT_BASE_OFFSET, 4), n >> 2);
        } else if (array instanceof short[] a) {
            Nmem.copyToShorts(src, a, index(arrayOffset, Unsafe.ARRAY_SHORT_BASE_OFFSET, 2), n >> 1);
        } else if (array instanceof char[] a) {
            Nmem.copyToChars(src, a, index(arrayOffset, Unsafe.ARRAY_CHAR_BASE_OFFSET, 2), n >> 1);
        } else if (array instanceof long[] a) {
            Nmem.copyToLongs(src, a, index(arrayOffset, Unsafe.ARRAY_LONG_BASE_OFFSET, 8), n >> 3);
        } else if (array instanceof double[] a) {
            Nmem.copyToDoubles(src, a, index(arrayOffset, Unsafe.ARRAY_DOUBLE_BASE_OFFSET, 8), n >> 3);
        } else {
            throw new IllegalArgumentException("unsupported array copy target " + array.getClass());
        }
    }

    /** Unsafe-style copy between two heap arrays (same element type: arraycopy; else via nmem). */
    static void heapToHeap(Object src, long srcOffset, Object dst, long dstOffset, long bytes) {
        if (src.getClass() == dst.getClass()) {
            int base = baseOffset(src);
            int scale = indexScale(src);
            if ((srcOffset - base) % scale == 0 && (dstOffset - base) % scale == 0 && bytes % scale == 0) {
                System.arraycopy(src, index(srcOffset, base, scale), dst, index(dstOffset, base, scale), (int) (bytes / scale));
                return;
            }
        }
        long tmp = NativeHeap.malloc(bytes);
        try {
            arrayToNmem(src, srcOffset, tmp, bytes);
            nmemToArray(tmp, dst, dstOffset, bytes);
        } finally {
            NativeHeap.free(tmp);
        }
    }

    /** Unsafe-style fill of part of a heap array with a byte value. */
    static void fillHeap(Object array, long offset, long bytes, byte value) {
        if (array instanceof byte[] a) {
            int from = (int) (offset - Unsafe.ARRAY_BYTE_BASE_OFFSET);
            java.util.Arrays.fill(a, from, from + (int) bytes, value);
            return;
        }
        long tmp = NativeHeap.malloc(bytes);
        try {
            Nmem.fill(tmp, value, bytes);
            nmemToArray(tmp, array, offset, bytes);
        } finally {
            NativeHeap.free(tmp);
        }
    }

    private static int baseOffset(Object array) {
        if (array instanceof byte[]) {
            return Unsafe.ARRAY_BYTE_BASE_OFFSET;
        } else if (array instanceof int[]) {
            return Unsafe.ARRAY_INT_BASE_OFFSET;
        } else if (array instanceof float[]) {
            return Unsafe.ARRAY_FLOAT_BASE_OFFSET;
        } else if (array instanceof short[]) {
            return Unsafe.ARRAY_SHORT_BASE_OFFSET;
        } else if (array instanceof char[]) {
            return Unsafe.ARRAY_CHAR_BASE_OFFSET;
        } else if (array instanceof long[]) {
            return Unsafe.ARRAY_LONG_BASE_OFFSET;
        } else if (array instanceof double[]) {
            return Unsafe.ARRAY_DOUBLE_BASE_OFFSET;
        }
        throw new IllegalArgumentException("unsupported heap copy " + array.getClass());
    }

    private static int indexScale(Object array) {
        if (array instanceof byte[]) {
            return 1;
        } else if (array instanceof short[] || array instanceof char[]) {
            return 2;
        } else if (array instanceof int[] || array instanceof float[]) {
            return 4;
        }
        return 8;
    }

    private static int index(long offset, int base, int scale) {
        return (int) ((offset - base) / scale);
    }
}
