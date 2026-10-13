package dev.mcwebclient.web.mc;

import dev.mcwebclient.web.WebJs;
import dev.mcwebclient.web.exec.CoThreads;
import dev.mcwebclient.web.gpu.WebGpu;

/**
 * Frame pacing for Minecraft's own loop, which runs on the main green thread. Each loop
 * iteration starts by waiting for the browser's next animation frame (the previous frame
 * is presented meanwhile); a frame rate cap below the display rate skips frames.
 */
public final class GameLoop {
    /** Minecraft's "unlimited" setting; it only calls the limiter below this. */
    private static final int UNCAPPED = 260;

    private static int pendingLimit = UNCAPPED;
    private static long lastFrameNanos;
    private static boolean started;

    private GameLoop() {
    }

    static void setFramerateLimit(int limit) {
        pendingLimit = Math.max(1, limit);
    }

    static void awaitFrame() {
        if (!started) {
            started = true;
            WebJs.stage("running");
        }
        int limit = pendingLimit;
        pendingLimit = UNCAPPED; // re-set by limitDisplayFPS while a cap applies
        long interval = limit < UNCAPPED ? 1_000_000_000L / limit - 1_000_000L : 0;
        do {
            CoThreads.awaitFrame();
        } while (System.nanoTime() - lastFrameNanos < interval);
        lastFrameNanos = System.nanoTime();
        WebGpu.pollReadbacks();
    }
}
