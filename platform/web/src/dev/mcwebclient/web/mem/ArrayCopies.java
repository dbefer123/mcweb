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

    private static int index(long offset, int base, int scale) {
        return (int) ((offset - base) / scale);
    }
}
