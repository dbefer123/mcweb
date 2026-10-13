package dev.mcwebclient.web.exec;

import java.util.Set;

import dev.mcwebclient.web.WebJs;
import dev.mcwebclient.web.mods.EntityCullingSubstitutions;
import net.minecraft.client.Minecraft;

/**
 * Thread.start: every thread becomes a green thread ({@link CoThreads}), so long-running
 * loops (integrated server, Netty event loops) run as written. A few threads have nothing
 * to do in the browser and are not started.
 */
public final class WebThreads {
    /** Threads with no job in the browser. */
    private static final Set<String> NOT_STARTED = Set.of(
            "Sound engine",        // audio layer not yet available (Phase 7)
            "Timer hack thread",   // Windows timer-resolution hack
            "Server Watchdog",
            "Server console handler",
            "Client Shutdown Thread",
            "Server Shutdown Thread");

    private WebThreads() {
    }

    public static void start(Thread thread) {
        String name = thread.getName();
        String type = thread.getClass().getName();
        if (NOT_STARTED.contains(name) || type.endsWith("LanServerDetector") || type.endsWith("LanServerPinger")) {
            WebJs.log("[threads] not starting thread '" + name + "' (" + type + ")");
            return;
        }
        if (name.equals("CullThread")) {
            // EntityCulling: run() is substituted with a single pass (mods/EntityCullingSubstitutions).
            repeat(thread, EntityCullingSubstitutions.sleepDelayMillis() * 1_000_000L);
            return;
        }
        CoThreads.start(thread);
    }

    /** Runs a thread's (single-pass) body every {@code periodNanos} until the game stops. */
    private static void repeat(Thread thread, long periodNanos) {
        WebScheduler.schedule(() -> {
            if (!Minecraft.getInstance().isRunning()) {
                return;
            }
            try {
                thread.run();
            } finally {
                repeat(thread, periodNanos);
            }
        }, periodNanos);
    }
}
