package dev.mcwebclient.web.jdk;

import java.util.zip.DataFormatException;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image has no native zlib. These substitutions replace only the native methods of
 * Inflater/Deflater/CRC32/Adler32; the JDK's Java logic around them is unchanged, so
 * GZIP, ZipInputStream, ZipFile and the zip file system all work on top.
 */
final class ZipSubstitutions {
    private ZipSubstitutions() {
    }
}

@TargetClass(java.util.zip.Inflater.class)
final class Target_java_util_zip_Inflater {
    @Alias int inputConsumed;
    @Alias int outputConsumed;

    @Substitute
    private static void initIDs() {
    }

    @Substitute
    private static long init(boolean nowrap) {
        return ZlibHandles.newInflater(nowrap);
    }

    @Substitute
    private static void setDictionary(long addr, byte[] b, int off, int len) {
        ZlibHandles.setInflaterDictionary(addr, b, off, len);
    }

    @Substitute
    private static void setDictionaryBuffer(long addr, long bufAddress, int len) {
        throw new UnsupportedOperationException("direct-buffer dictionary");
    }

    @Substitute
    private long inflateBytesBytes(long addr, byte[] inputArray, int inputOff, int inputLen,
                                   byte[] outputArray, int outputOff, int outputLen) throws DataFormatException {
        int[] consumed = new int[2];
        try {
            return ZlibHandles.inflate(addr, inputArray, inputOff, inputLen, outputArray, outputOff, outputLen, consumed);
        } catch (DataFormatException e) {
            inputConsumed = consumed[0];
            outputConsumed = consumed[1];
            throw e;
        }
    }

    @Substitute
    private long inflateBytesBuffer(long addr, byte[] inputArray, int inputOff, int inputLen,
                                    long outputAddress, int outputLen) {
        throw new UnsupportedOperationException("direct-buffer inflate");
    }

    @Substitute
    private long inflateBufferBytes(long addr, long inputAddress, int inputLen,
                                    byte[] outputArray, int outputOff, int outputLen) {
        throw new UnsupportedOperationException("direct-buffer inflate");
    }

    @Substitute
    private long inflateBufferBuffer(long addr, long inputAddress, int inputLen,
                                     long outputAddress, int outputLen) {
        throw new UnsupportedOperationException("direct-buffer inflate");
    }

    @Substitute
    private static int getAdler(long addr) {
        return ZlibHandles.adler(addr);
    }

    @Substitute
    private static void reset(long addr) {
        ZlibHandles.resetInflater(addr);
    }

    @Substitute
    private static void end(long addr) {
        ZlibHandles.endInflater(addr);
    }
}

@TargetClass(java.util.zip.Deflater.class)
final class Target_java_util_zip_Deflater {
    @Substitute
    private static long init(int level, int strategy, boolean nowrap) {
        return ZlibHandles.newDeflater(level, strategy, nowrap);
    }

    @Substitute
    private static void setDictionary(long addr, byte[] b, int off, int len) {
        ZlibHandles.setDeflaterDictionary(addr, b, off, len);
    }

    @Substitute
    private static void setDictionaryBuffer(long addr, long bufAddress, int len) {
        throw new UnsupportedOperationException("direct-buffer dictionary");
    }

    @Substitute
    private long deflateBytesBytes(long addr, byte[] inputArray, int inputOff, int inputLen,
                                   byte[] outputArray, int outputOff, int outputLen, int flush, int params) {
        return ZlibHandles.deflate(addr, inputArray, inputOff, inputLen, outputArray, outputOff, outputLen, flush, params);
    }

    @Substitute
    private long deflateBytesBuffer(long addr, byte[] inputArray, int inputOff, int inputLen,
                                    long outputAddress, int outputLen, int flush, int params) {
        throw new UnsupportedOperationException("direct-buffer deflate");
    }

    @Substitute
    private long deflateBufferBytes(long addr, long inputAddress, int inputLen,
                                    byte[] outputArray, int outputOff, int outputLen, int flush, int params) {
        throw new UnsupportedOperationException("direct-buffer deflate");
    }

    @Substitute
    private long deflateBufferBuffer(long addr, long inputAddress, int inputLen,
                                     long outputAddress, int outputLen, int flush, int params) {
        throw new UnsupportedOperationException("direct-buffer deflate");
    }

    @Substitute
    private static int getAdler(long addr) {
        return ZlibHandles.adler(addr);
    }

    @Substitute
    private static void reset(long addr) {
        ZlibHandles.resetDeflater(addr);
    }

    @Substitute
    private static void end(long addr) {
        ZlibHandles.endDeflater(addr);
    }
}

@TargetClass(java.util.zip.CRC32.class)
final class Target_java_util_zip_CRC32 {
    @Substitute
    private static int update(int crc, int b) {
        return Checksums.crc32(crc, b);
    }

    @Substitute
    private static int updateBytes0(int crc, byte[] b, int off, int len) {
        return Checksums.crc32(crc, b, off, len);
    }

    @Substitute
    private static int updateByteBuffer0(int crc, long addr, int off, int len) {
        throw new UnsupportedOperationException("direct-buffer crc32");
    }
}

@TargetClass(java.util.zip.Adler32.class)
final class Target_java_util_zip_Adler32 {
    @Substitute
    private static int update(int adler, int b) {
        return Checksums.adler32(adler, new byte[] {(byte) b}, 0, 1);
    }

    @Substitute
    private static int updateBytes(int adler, byte[] b, int off, int len) {
        return Checksums.adler32(adler, b, off, len);
    }

    @Substitute
    private static int updateByteBuffer(int adler, long addr, int off, int len) {
        throw new UnsupportedOperationException("direct-buffer adler32");
    }
}
