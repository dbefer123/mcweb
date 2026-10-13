package spike;

import org.graalvm.webimage.api.JS;

/**
 * JSPI probe: can Web Image code suspend on a JS promise and resume (green threads)?
 * build-spike3.mjs wraps {@link #suspendFor}'s import in WebAssembly.Suspending and runs
 * main (and coroutines started with {@link #startCoroutine}) through WebAssembly.promising.
 */
public final class Spike3 {
    private Spike3() {
    }

    @JS.Coerce
    @JS(value = "return new Promise((resolve) => setTimeout(() => resolve(ms * 2), ms));", args = {"ms"})
    static native int suspendFor(int ms);

    @JS.Coerce
    @JS(value = "globalThis.spikeLog(msg);", args = {"msg"})
    static native void log(String msg);

    /** Runs r on its own stack; the JS side calls r.run() through WebAssembly.promising. */
    @JS(value = "globalThis.spikeStart(r);", args = {"r"})
    static native void startCoroutine(Runnable r);

    private static long t0;

    private static String t() {
        return "+" + (System.currentTimeMillis() - t0) + "ms";
    }

    public static void main(String[] args) {
        t0 = System.currentTimeMillis();
        StringBuilder trace = new StringBuilder();
        log("main start " + t());
        int r = suspendFor(100);
        log("main woke r=" + r + " " + t());
        startCoroutine(() -> {
            for (int i = 0; i < 3; i++) {
                trace.append('c').append(i);
                log("coroutine " + i + " " + t());
                suspendFor(70);
            }
            log("coroutine done " + t());
        });
        log("main after startCoroutine " + t());
        for (int i = 0; i < 3; i++) {
            trace.append('m').append(i);
            suspendFor(100);
            log("main loop " + i + " " + t());
        }
        try {
            throwAcrossSuspension();
        } catch (IllegalStateException e) {
            log("caught across suspension: " + e.getMessage());
        }
        log("main end " + t() + " trace=" + trace);
    }

    private static void throwAcrossSuspension() {
        suspendFor(10);
        throw new IllegalStateException("thrown after resume");
    }
}
