package dev.mcwebclient.web.gpu;

import java.util.HashMap;
import java.util.Map;

import org.graalvm.webimage.api.JS;

import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;

/**
 * The Java end of the WebGPU host (web/dev/webgpu.js): commands are appended as int32
 * words to a stream in nmem and executed by JS in batches ({@link #flush()}), so a
 * frame with thousands of draws costs a handful of Wasm→JS calls. Object ids are
 * allocated here and index the host's object table. Opcodes match webgpu.js.
 */
public final class WebGpu {
    private WebGpu() {
    }

    // Opcodes (webgpu.js OP)
    static final int CREATE_BUFFER = 1, DESTROY = 2, WRITE_BUFFER = 3, CREATE_TEXTURE = 4, CREATE_VIEW = 5,
            CREATE_SAMPLER = 6, CREATE_BGL = 7, CREATE_PIPELINE_LAYOUT = 8, CREATE_RENDER_PIPELINE = 9,
            CREATE_BIND_GROUP = 10, BEGIN_PASS = 11, END_PASS = 12, SET_PIPELINE = 13, SET_BIND_GROUP = 14,
            SET_VERTEX_BUFFER = 15, SET_INDEX_BUFFER = 16, SET_SCISSOR = 17, DRAW = 18, DRAW_INDEXED = 19,
            DRAW_INDEXED_INDIRECT = 20, DRAW_INDIRECT = 21, MULTI_DRAW_SEPARATE = 22, MULTI_DRAW_INTERLEAVED = 23,
            COPY_B2B = 24, COPY_B2T = 25, READBACK_TEXTURE = 26, COPY_T2T = 27, SUBMIT = 28, BLIT_CANVAS = 29,
            READBACK_BUFFER = 30, CLEAR_REGION = 31;

    // GPUBufferUsage / GPUTextureUsage / GPUShaderStage bits
    static final int BUF_MAP_READ = 0x1, BUF_MAP_WRITE = 0x2, BUF_COPY_SRC = 0x4, BUF_COPY_DST = 0x8, BUF_INDEX = 0x10,
            BUF_VERTEX = 0x20, BUF_UNIFORM = 0x40, BUF_STORAGE = 0x80, BUF_INDIRECT = 0x100;
    static final int TEX_COPY_SRC = 0x1, TEX_COPY_DST = 0x2, TEX_BINDING = 0x4, TEX_STORAGE = 0x8, TEX_RENDER = 0x10;
    static final int STAGE_VERTEX = 0x1, STAGE_FRAGMENT = 0x2;

    private static final int STREAM_WORDS = 1 << 20; // 4 MiB
    private static long stream;
    private static int used;
    private static int nextId = 1;
    private static final Map<Integer, Runnable> pendingReadbacks = new HashMap<>();
    private static int nextRequest = 1;

    static synchronized void init() {
        if (stream == 0) {
            stream = NativeHeap.alignedAlloc(16, STREAM_WORDS * 4L);
            attach();
        }
    }

    static int newId() {
        return nextId++;
    }

    /** Reserves room for {@code words} more words, flushing first if needed. */
    static void begin(int words) {
        if (used + words > STREAM_WORDS) {
            flush();
        }
        if (words > STREAM_WORDS) {
            throw new IllegalArgumentException("command of " + words + " words is larger than the stream");
        }
    }

    static void put(int v) {
        Nmem.putInt(stream + 4L * used++, v);
    }

    static void putF(float v) {
        put(Float.floatToRawIntBits(v));
    }

    static void putAddr(long address) {
        put((int) address);
    }

    /** Executes everything recorded so far. */
    public static void flush() {
        if (used > 0) {
            int n = used;
            used = 0;
            exec((int) stream, n);
        }
    }

    static void destroy(int id) {
        if (id > 0) {
            begin(2);
            put(DESTROY);
            put(id);
        }
    }

    /** Copies {@code size} bytes at nmem {@code src} into a GPU buffer (queue.writeBuffer, immediate). */
    static void writeBuffer(int buffer, long offset, long src, long size) {
        begin(5);
        put(WRITE_BUFFER);
        put(buffer);
        put((int) offset);
        putAddr(src);
        put((int) size);
    }

    static int registerReadback(Runnable onComplete) {
        int id = nextRequest++;
        pendingReadbacks.put(id, onComplete);
        return id;
    }

    /** Runs callbacks of readbacks whose data has arrived (called once per frame). */
    public static void pollReadbacks() {
        if (pendingReadbacks.isEmpty()) {
            return;
        }
        int id;
        while ((id = nextCompleted()) >= 0) {
            Runnable r = pendingReadbacks.remove(id);
            if (r != null) {
                r.run();
            }
        }
    }

    // ---- host calls ----

    @JS.Coerce
    @JS(value = "globalThis.mcweb.gpu.attach(getExport('nmem'));", args = {})
    private static native void attach();

    @JS.Coerce
    @JS(value = "globalThis.mcweb.gpu.exec(addr >>> 0, count);", args = {"addr", "count"})
    private static native void exec(int addr, int count);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.gpu.createShaderModule(id, code, label);", args = {"id", "code", "label"})
    static native void createShaderModule(int id, String code, String label);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.gpu.setLabel(id, label);", args = {"id", "label"})
    static native void setLabel(int id, String label);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.gpu.configureCanvas(w, h);", args = {"w", "h"})
    static native void configureCanvas(int w, int h);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.gpu.nextCompleted();", args = {})
    private static native int nextCompleted();

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.gpu.info(key);", args = {"key"})
    static native String info(String key);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.gpu.limit(key);", args = {"key"})
    static native double limit(String key);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.gpu.hasFeature(name);", args = {"name"})
    static native boolean hasFeature(String name);

    @JS.Coerce
    @JS(value = "return !!globalThis.mcweb?.gpu;", args = {})
    static native boolean available();
}
