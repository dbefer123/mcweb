package dev.mcwebclient.web.exec;

import java.util.Set;

import dev.mcwebclient.web.WebJs;

/**
 * Thread.start on a single thread. A thread whose body finishes (connectors, one-shot
 * fetchers, authenticators) runs later as a cooperative task. Threads that loop for
 * the life of the game can't run that way; those are listed here and either replaced
 * elsewhere (server tick, sound) or simply not needed in the browser.
 */
public final class WebThreads {
    /** Long-running loops; each is replaced or unnecessary in the browser. */
    private static final Set<String> NOT_STARTED = Set.of(
            "Sound engine",        // SoundEngineExecutor loop → tasks run inline (audio layer)
            "Server thread",       // integrated server → Worker (Phase 4)
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
        if (NOT_STARTED.contains(name) || name.startsWith("Netty ") || type.endsWith("LanServerDetector")
                || type.endsWith("LanServerPinger")) {
            WebJs.log("[threads] not starting long-running thread '" + name + "' (" + type + ")");
            return;
        }
        WebScheduler.submit(thread::run);
    }
}
