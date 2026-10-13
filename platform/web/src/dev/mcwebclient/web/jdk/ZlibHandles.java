package dev.mcwebclient.web.jdk;

import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;

import com.jcraft.jzlib.JZlib;
import com.jcraft.jzlib.ZStream;

/**
 * Pure-Java zlib behind java.util.zip.Inflater/Deflater. The JDK keeps a native
 * z_stream address per instance; here that "address" is a handle into this table
 * of JZlib streams. Results use the JDK's packed-long protocol:
 * bits 0-30 bytes read, 31-61 bytes written, 62 finished, 63 needs-dictionary
 * (for Deflater bit 63 means "params not applied yet").
 */
public final class ZlibHandles {
    private static final Map<Long, ZStream> STREAMS = new HashMap<>();
    private static long next = 1;

    private ZlibHandles() {
    }

    static synchronized long register(ZStream z) {
        long h = next++;
        STREAMS.put(h, z);
        return h;
    }

    static synchronized ZStream get(long handle) {
        ZStream z = STREAMS.get(handle);
        if (z == null) {
            throw new IllegalStateException("zlib stream " + handle + " is closed");
        }
        return z;
    }

    static synchronized ZStream remove(long handle) {
        return STREAMS.remove(handle);
    }

    // ---- Inflater ----

    static long newInflater(boolean nowrap) {
        com.jcraft.jzlib.Inflater z = new com.jcraft.jzlib.Inflater();
        int r = z.init(nowrap);
        if (r != JZlib.Z_OK) {
            throw new InternalError("inflateInit failed: " + r);
        }
        long h = register(z);
        synchronized (ZlibHandles.class) {
            INFLATER_NOWRAP.put(h, nowrap);
        }
        return h;
    }

    /** Returns the packed result; on a data error reports consumed counts via {@code consumed}. */
    static long inflate(long handle, byte[] in, int inOff, int inLen, byte[] out, int outOff, int outLen,
                        int[] consumed) throws DataFormatException {
        com.jcraft.jzlib.Inflater z = (com.jcraft.jzlib.Inflater) get(handle);
        z.next_in = in;
        z.next_in_index = inOff;
        z.avail_in = inLen;
        z.next_out = out;
        z.next_out_index = outOff;
        z.avail_out = outLen;
        int r = z.inflate(JZlib.Z_PARTIAL_FLUSH);
        int read = inLen - z.avail_in;
        int written = outLen - z.avail_out;
        boolean finished = false;
        boolean needDict = false;
        switch (r) {
            case JZlib.Z_STREAM_END -> finished = true;
            case JZlib.Z_NEED_DICT -> needDict = true;
            case JZlib.Z_OK, JZlib.Z_BUF_ERROR -> {
            }
            case JZlib.Z_DATA_ERROR, JZlib.Z_STREAM_ERROR -> {
                consumed[0] = read;
                consumed[1] = written;
                throw new DataFormatException(z.msg != null ? z.msg : "invalid compressed data");
            }
            default -> throw new InternalError("inflate returned " + r);
        }
        z.next_in = null;
        z.next_out = null;
        return pack(read, written, finished, needDict);
    }

    static void setInflaterDictionary(long handle, byte[] b, int off, int len) {
        byte[] dict = off == 0 && len == b.length ? b : java.util.Arrays.copyOfRange(b, off, off + len);
        int r = ((com.jcraft.jzlib.Inflater) get(handle)).setDictionary(dict, len);
        if (r != JZlib.Z_OK) {
            throw new IllegalArgumentException("invalid dictionary (" + r + ")");
        }
    }

    static void resetInflater(long handle) {
        com.jcraft.jzlib.Inflater z = (com.jcraft.jzlib.Inflater) get(handle);
        boolean nowrap = INFLATER_NOWRAP.getOrDefault(handle, false);
        z.end();
        z.init(nowrap);
    }

    /** nowrap flag per inflater, needed to re-init on reset(). */
    static final Map<Long, Boolean> INFLATER_NOWRAP = new HashMap<>();

    static void endInflater(long handle) {
        ZStream z;
        synchronized (ZlibHandles.class) {
            z = STREAMS.remove(handle);
            INFLATER_NOWRAP.remove(handle);
        }
        if (z != null) {
            ((com.jcraft.jzlib.Inflater) z).end();
        }
    }

    // ---- Deflater ----

    private record DeflaterState(int level, int strategy, boolean nowrap) {
    }

    static final Map<Long, DeflaterState> DEFLATER_STATE = new HashMap<>();

    static long newDeflater(int level, int strategy, boolean nowrap) {
        com.jcraft.jzlib.Deflater z = new com.jcraft.jzlib.Deflater();
        int r = z.init(level, JZlib.MAX_WBITS, nowrap);
        if (r != JZlib.Z_OK) {
            throw new InternalError("deflateInit failed: " + r);
        }
        if (strategy != 0) {
            z.params(level, strategy);
        }
        long h = register(z);
        synchronized (ZlibHandles.class) {
            DEFLATER_STATE.put(h, new DeflaterState(level, strategy, nowrap));
        }
        return h;
    }

    static long deflate(long handle, byte[] in, int inOff, int inLen, byte[] out, int outOff, int outLen,
                        int flush, int params) {
        com.jcraft.jzlib.Deflater z = (com.jcraft.jzlib.Deflater) get(handle);
        z.next_in = in;
        z.next_in_index = inOff;
        z.avail_in = inLen;
        z.next_out = out;
        z.next_out_index = outOff;
        z.avail_out = outLen;
        boolean paramsPending = false;
        if (params != 0) {
            int strategy = (params >> 1) & 3;
            int level = params >> 3;
            int r = z.params(level, strategy);
            if (r == JZlib.Z_BUF_ERROR) {
                paramsPending = true;
            } else {
                synchronized (ZlibHandles.class) {
                    DeflaterState s = DEFLATER_STATE.get(handle);
                    DEFLATER_STATE.put(handle, new DeflaterState(level, strategy, s != null && s.nowrap()));
                }
            }
        }
        boolean finished = false;
        if (!paramsPending) {
            int zflush = switch (flush) {
                case 0 -> JZlib.Z_NO_FLUSH;
                case 2 -> JZlib.Z_SYNC_FLUSH;
                case 3 -> JZlib.Z_FULL_FLUSH;
                case 4 -> JZlib.Z_FINISH;
                default -> throw new IllegalArgumentException("flush " + flush);
            };
            int r = z.deflate(zflush);
            if (r == JZlib.Z_STREAM_END) {
                finished = true;
            } else if (r != JZlib.Z_OK && r != JZlib.Z_BUF_ERROR) {
                throw new InternalError("deflate returned " + r + (z.msg != null ? ": " + z.msg : ""));
            }
        }
        int read = inLen - z.avail_in;
        int written = outLen - z.avail_out;
        z.next_in = null;
        z.next_out = null;
        return pack(read, written, finished, paramsPending);
    }

    static void setDeflaterDictionary(long handle, byte[] b, int off, int len) {
        byte[] dict = off == 0 && len == b.length ? b : java.util.Arrays.copyOfRange(b, off, off + len);
        ((com.jcraft.jzlib.Deflater) get(handle)).setDictionary(dict, len);
    }

    static void resetDeflater(long handle) {
        com.jcraft.jzlib.Deflater z = (com.jcraft.jzlib.Deflater) get(handle);
        DeflaterState s;
        synchronized (ZlibHandles.class) {
            s = DEFLATER_STATE.get(handle);
        }
        z.end();
        z.init(s.level(), JZlib.MAX_WBITS, s.nowrap());
        if (s.strategy() != 0) {
            z.params(s.level(), s.strategy());
        }
    }

    static void endDeflater(long handle) {
        ZStream z;
        synchronized (ZlibHandles.class) {
            z = STREAMS.remove(handle);
            DEFLATER_STATE.remove(handle);
        }
        if (z != null) {
            ((com.jcraft.jzlib.Deflater) z).end();
        }
    }

    static int adler(long handle) {
        return (int) get(handle).getAdler();
    }

    private static long pack(int read, int written, boolean bit62, boolean bit63) {
        return (read & 0x7fff_ffffL) | ((written & 0x7fff_ffffL) << 31) | (bit62 ? 1L << 62 : 0) | (bit63 ? 1L << 63 : 0);
    }
}
