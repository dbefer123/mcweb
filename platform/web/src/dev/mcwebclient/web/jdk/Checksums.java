package dev.mcwebclient.web.jdk;

/** Pure-Java CRC-32 (IEEE, reflected) and Adler-32, matching java.util.zip semantics. */
final class Checksums {
    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            }
            CRC_TABLE[n] = c;
        }
    }

    private Checksums() {
    }

    static int crc32(int crc, int b) {
        int c = ~crc;
        c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
        return ~c;
    }

    static int crc32(int crc, byte[] b, int off, int len) {
        int c = ~crc;
        for (int i = off, end = off + len; i < end; i++) {
            c = CRC_TABLE[(c ^ b[i]) & 0xff] ^ (c >>> 8);
        }
        return ~c;
    }

    private static final int ADLER_BASE = 65521;
    /** Largest n such that 255n(n+1)/2 + (n+1)(BASE-1) fits in 32 bits unsigned. */
    private static final int ADLER_NMAX = 5552;

    static int adler32(int adler, byte[] b, int off, int len) {
        long s1 = adler & 0xffff;
        long s2 = (adler >>> 16) & 0xffff;
        while (len > 0) {
            int n = Math.min(len, ADLER_NMAX);
            len -= n;
            while (n-- > 0) {
                s1 += b[off++] & 0xff;
                s2 += s1;
            }
            s1 %= ADLER_BASE;
            s2 %= ADLER_BASE;
        }
        return (int) ((s2 << 16) | s1);
    }
}
