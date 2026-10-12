package spike;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;

/**
 * Phase 0 toolchain spike. Measures what the Web Image backends can do before
 * any Minecraft code is involved: JS interop both ways, bulk byte transfer,
 * threads, raw memory, exceptions, and CPU throughput of typical game workloads.
 */
public final class Spike {
    private Spike() {
    }

    @JS.Coerce
    @JS(value = "console.log(msg); if (globalThis.spikeOut) globalThis.spikeOut(msg);", args = {"msg"})
    static native void log(String msg);

    @JS.Coerce
    @JS(value = "return performance.now();", args = {})
    static native double now();

    /** Hands a Java callback to JS; the host calls it repeatedly after main returns. */
    @JS(value = "globalThis.spikeTick = () => cb.run();", args = {"cb"})
    static native void registerTick(Runnable cb);

    /** What does JS see when it receives a byte[]? */
    @JS.Coerce
    @JS(value = "return typeof data + ':' + Object.prototype.toString.call(data) + ':len=' + data.length;", args = {"data"})
    static native String describeBytes(byte[] data);

    /** Sums a byte[] element-by-element on the JS side to time per-element access. */
    @JS.Coerce
    @JS(value = "let s = 0; const n = data.length; for (let i = 0; i < n; i++) s = (s + data[i]) | 0; return s;", args = {"data"})
    static native int sumBytesInJs(byte[] data);

    /** Can JS hand a typed array back as byte[] with coercion? */
    @JS.Coerce
    @JS(value = "const a = new Int8Array(n); for (let i = 0; i < n; i++) a[i] = i; return a;", args = {"n"})
    static native byte[] bytesFromJs(int n);

    @JS(value = "return { kind: 'plain-object', n: 42 };", args = {})
    static native JSObject makeObject();

    /** Zero-copy view of the injected linear memory from JS. */
    @JS.Coerce
    @JS(value = "const m = getExport('nmem'); const v = new Uint8Array(m.buffer, addr, len);"
            + " let s = 0; for (let i = 0; i < len; i++) s = (s + ((v[i] << 24) >> 24)) | 0; return s;",
            args = {"addr", "len"})
    static native int sumNmemInJs(int addr, int len);

    /** Hands vertex bytes in linear memory to the page's WebGPU test, if present. */
    @JS.Coerce
    @JS(value = "if (globalThis.spikeGpu) globalThis.spikeGpu(getExport('nmem'), addr, len);", args = {"addr", "len"})
    static native void gpuTriangle(int addr, int len);

    @JS.Coerce
    @JS(value = "return String(new Error().stack);", args = {})
    static native String jsStack();

    static int deep(int n) {
        if (n == 0) {
            throw new IllegalStateException("deep failure");
        }
        return deep(n - 1) + 1;
    }

    private static int ticks;

    public static void main(String[] args) {
        log("spike: main start, backend=" + System.getProperty("java.vm.name") + " / " + System.getProperty("os.arch"));
        run("interop.bytes.describe", () -> describeBytes(new byte[] {1, 2, 3}));
        run("interop.bytes.sumInJs(1MiB)", () -> {
            byte[] data = new byte[1 << 20];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) i;
            }
            double t0 = now();
            int s = sumBytesInJs(data);
            return "sum=" + s + " ms=" + fmt(now() - t0);
        });
        run("interop.bytes.fromJs", () -> {
            byte[] b = bytesFromJs(16);
            return b == null ? "null" : ("len=" + b.length + " b[15]=" + b[15]);
        });
        run("interop.jsobject", () -> {
            JSObject o = makeObject();
            return String.valueOf(o.get("kind")) + " n=" + o.get("n");
        });
        run("exceptions", () -> {
            try {
                Object o = null;
                o.hashCode();
                return "no NPE?!";
            } catch (NullPointerException e) {
                StackTraceElement[] st = e.getStackTrace();
                return "NPE caught, stack depth=" + st.length;
            }
        });
        run("threads.start+join", () -> {
            int[] box = {0};
            Thread t = new Thread(() -> box[0] = 7);
            t.start();
            t.join();
            return "ran=" + box[0];
        });
        run("threads.completableFuture", () -> CompletableFuture.supplyAsync(() -> 5).join().toString());
        run("sync.wait(1ms)", () -> {
            Object lock = new Object();
            synchronized (lock) {
                lock.wait(1);
            }
            return "returned";
        });
        run("unsafe.rawMemory", () -> {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
            long addr = u.allocateMemory(64);
            u.putFloat(addr + 8, 1.5f);
            u.putLong(addr + 16, 0x1122334455667788L);
            String r = "addr=" + addr + " f=" + u.getFloat(addr + 8) + " l=" + Long.toHexString(u.getLong(addr + 16));
            u.freeMemory(addr);
            return r;
        });
        run("nio.directBuffer", () -> {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.putFloat(4, 2.5f);
            return "isDirect=" + b.isDirect() + " f=" + b.getFloat(4);
        });
        run("nmem.roundtrip", () -> {
            int before = WasmIntrinsics.pages();
            int prev = WasmIntrinsics.grow(256);
            long a = 4096;
            WasmIntrinsics.putFloat(a, 1.25f);
            WasmIntrinsics.putInt(a + 4, -7);
            WasmIntrinsics.putLong(a + 8, 0x1122334455667788L);
            WasmIntrinsics.putDouble(a + 16, Math.PI);
            WasmIntrinsics.putShort(a + 24, (short) -2);
            WasmIntrinsics.putByte(a + 26, (byte) -3);
            byte[] src = new byte[1027];
            for (int i = 0; i < src.length; i++) {
                src[i] = (byte) (i * 7);
            }
            WasmIntrinsics.copyFromBytes(src, 0, a + 1024, src.length);
            byte[] back = new byte[src.length];
            WasmIntrinsics.copyToBytes(a + 1024, back, 0, back.length);
            WasmIntrinsics.copy(a + 1024, a + 4096, 1027);
            WasmIntrinsics.fill(a + 8192, 0x5A, 16);
            return "pages " + before + "->" + prev + "->" + WasmIntrinsics.pages()
                    + " f=" + WasmIntrinsics.getFloat(a) + " i=" + WasmIntrinsics.getInt(a + 4)
                    + " l=" + Long.toHexString(WasmIntrinsics.getLong(a + 8)) + " d=" + WasmIntrinsics.getDouble(a + 16)
                    + " s=" + WasmIntrinsics.getShort(a + 24) + " b=" + WasmIntrinsics.getByte(a + 26)
                    + " copyRoundtrip=" + java.util.Arrays.equals(src, back)
                    + " memmove=" + (WasmIntrinsics.getByte(a + 4096 + 1026) == src[1026])
                    + " fill=" + Integer.toHexString(WasmIntrinsics.getInt(a + 8192))
                    + " jsSeesSum=" + sumNmemInJs((int) (a + 1024), src.length) + " javaSum=" + javaSum(src);
        });
        bench("bench.nmem-putFloat(4M)", () -> {
            long base = 1 << 20;
            for (int i = 0; i < (4 << 20); i++) {
                WasmIntrinsics.putFloat(base + ((long) i << 2), i * 0.5f);
            }
            return WasmIntrinsics.getInt(base + 1024);
        });
        bench("bench.nmem-copyFromBytes(16MiB)", () -> {
            byte[] big = new byte[16 << 20];
            big[12345] = 9;
            double t0 = now();
            WasmIntrinsics.copyFromBytes(big, 0, 1 << 20, big.length);
            log("  copyFromBytes 16MiB only: " + fmt(now() - t0) + " ms");
            return WasmIntrinsics.getByte((1 << 20) + 12345);
        });
        bench("bench.int-xorshift(50M)", Spike::benchInt);
        bench("bench.long-pack(20M)", Spike::benchLong);
        bench("bench.alloc-small(10M)", Spike::benchAlloc);
        bench("bench.hashmap(2M)", Spike::benchMap);
        bench("bench.math-trig(5M)", Spike::benchMath);
        bench("bench.interface-dispatch(50M)", Spike::benchDispatch);
        bench("bench.byteArray-floatPut(16MiB)", Spike::benchBytePut);

        run("printStackTrace", () -> {
            try {
                deep(3);
            } catch (IllegalStateException e) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                return sw.toString().replace('\n', '|');
            }
            return "no throw?!";
        });
        run("jsStackFromJava", () -> jsStack().replace('\n', '|'));
        run("gpu.triangle", () -> {
            // x, y, r, g, b per vertex, written straight into linear memory.
            float[] v = {0f, 0.6f, 1f, 0.3f, 0.2f, -0.6f, -0.5f, 0.2f, 0.9f, 0.3f, 0.6f, -0.5f, 0.2f, 0.4f, 1f};
            long base = 256;
            for (int i = 0; i < v.length; i++) {
                WasmIntrinsics.putFloat(base + 4L * i, v[i]);
            }
            gpuTriangle((int) base, v.length * 4);
            return "submitted " + v.length * 4 + " bytes";
        });
        registerTick(() -> {
            ticks++;
            if (ticks == 1 || ticks % 60 == 0) {
                log("tick " + ticks);
            }
        });
        log("spike: main end (callback registered)");
    }

    interface Check {
        Object call() throws Throwable;
    }

    static void run(String name, Check c) {
        try {
            log("[ok]   " + name + ": " + c.call());
        } catch (Throwable t) {
            log("[fail] " + name + ": " + t);
        }
    }

    interface Bench {
        long call();
    }

    static void bench(String name, Bench b) {
        try {
            double t0 = now();
            long r = b.call();
            log("[bench] " + name + ": " + fmt(now() - t0) + " ms (r=" + r + ")");
        } catch (Throwable t) {
            log("[fail] " + name + ": " + t);
        }
    }

    static int javaSum(byte[] b) {
        int s = 0;
        for (byte x : b) {
            s += x;
        }
        return s;
    }

    static String fmt(double ms) {
        return String.valueOf(Math.round(ms * 10) / 10.0);
    }

    static long benchInt() {
        int x = 123456789;
        int acc = 0;
        int[] arr = new int[4096];
        for (int i = 0; i < 50_000_000; i++) {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            arr[x & 4095] += x;
            acc += arr[i & 4095];
        }
        return acc;
    }

    static long benchLong() {
        long acc = 0;
        for (int i = 0; i < 20_000_000; i++) {
            int x = i & 0x3FFFFFF;
            int y = (i >> 3) & 0xFFF;
            int z = (i * 31) & 0x3FFFFFF;
            long packed = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
            acc += (packed >> 38) + ((packed << 52) >> 52) + ((packed << 26) >> 38);
        }
        return acc;
    }

    record Vec(double x, double y, double z) {
        Vec add(Vec o) {
            return new Vec(x + o.x, y + o.y, z + o.z);
        }
    }

    static long benchAlloc() {
        Vec v = new Vec(0, 0, 0);
        Vec d = new Vec(1, 0.5, 0.25);
        for (int i = 0; i < 10_000_000; i++) {
            v = v.add(d);
        }
        return (long) v.x();
    }

    static long benchMap() {
        Map<Long, Integer> m = new HashMap<>();
        long acc = 0;
        for (int i = 0; i < 2_000_000; i++) {
            long k = (i * 2654435761L) & 0xFFFFF;
            Integer prev = m.put(k, i);
            if (prev != null) {
                acc += prev;
            }
        }
        return acc + m.size();
    }

    static long benchMath() {
        double acc = 0;
        for (int i = 0; i < 5_000_000; i++) {
            acc += Math.sin(i * 0.001) * Math.cos(i * 0.002) + Math.sqrt(i);
        }
        return (long) acc;
    }

    interface Op {
        int apply(int v);
    }

    static final class AddOp implements Op {
        public int apply(int v) {
            return v + 3;
        }
    }

    static final class MulOp implements Op {
        public int apply(int v) {
            return v * 3;
        }
    }

    static final class XorOp implements Op {
        public int apply(int v) {
            return v ^ 0x5555;
        }
    }

    static long benchDispatch() {
        Op[] ops = {new AddOp(), new MulOp(), new XorOp()};
        int v = 1;
        for (int i = 0; i < 50_000_000; i++) {
            v = ops[i % 3].apply(v);
        }
        return v;
    }

    static long benchBytePut() {
        // Mimics vertex writing: little-endian floats into a byte buffer.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16 << 20).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < (4 << 20); i++) {
            buf.putFloat(i << 2, i * 0.5f);
        }
        return buf.getInt(1024);
    }
}
