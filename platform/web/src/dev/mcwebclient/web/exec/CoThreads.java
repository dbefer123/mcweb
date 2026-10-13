package dev.mcwebclient.web.exec;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalObject;

import dev.mcwebclient.web.WebJs;
import org.graalvm.webimage.api.JS;

/**
 * Java threads as green threads on the browser's single thread, using JSPI
 * (WebAssembly.Suspending / WebAssembly.promising; Chrome and Edge 137+).
 *
 * <p>Every started {@link Thread} runs on its own WebAssembly stack (a coroutine started
 * through a promising export call, see web/dev/host.js). Blocking operations suspend the
 * current stack on a JS promise and return control to the browser: {@code LockSupport.park}
 * until {@link #unpark} or a timeout, {@code Thread.sleep} on a timer, {@code Thread.yield}
 * and worker time slices through a message channel, {@code Object.wait} until notify.
 * Minecraft's own loops (client frame loop, integrated server tick loop, Netty event loops)
 * therefore run unmodified.
 *
 * <p>Only one stack runs at a time, so there are no data races; switches happen only at
 * suspension points. {@code Thread.currentThread()} is the runtime's single
 * current-thread slot, which is set to the coroutine's Thread whenever a stack resumes.
 *
 * <p>Monitors ({@code synchronized}) stay no-ops (Web Image's single-threaded monitor
 * support): a thread that suspends inside a synchronized block does not exclude others.
 *
 * <p>A watchdog thread reports threads stuck in an untimed park/wait (with the stack where
 * they parked) and dumps all threads when the page sets {@code mcweb.co.dumpRequested}.
 */
public final class CoThreads {
    /** Workers give the browser (and other coroutines) a turn after this much CPU time. */
    private static final long SLICE_NANOS = 6_000_000L;
    /** Record where untimed parks/waits happen, for the watchdog's stuck-thread reports. */
    private static final boolean TRACK_PARK_SITES = true;
    private static final long STUCK_NANOS = 10_000_000_000L;

    private static final int RUNNING = 0;
    private static final int PARKED = 1;
    private static final int PARKED_TIMED = 2;
    private static final int WAITING = 3;
    private static final int SLEEPING = 4;
    private static final int YIELDING = 5;
    private static final int AWAITING_FRAME = 6;
    private static final int AWAITING_IO = 7;
    private static final String[] STATE_NAMES = {"running", "parked", "parked (timed)", "waiting (Object.wait)",
        "sleeping", "yielding", "awaiting frame", "awaiting I/O"};

    private static final class Co {
        final Thread thread;
        final int key;
        boolean alive;
        boolean permit;
        /** True while suspended in park/wait (wakeable by unpark/notify). */
        boolean parked;
        int state;
        long since;
        Throwable parkSite;
        boolean reported;
        /** Waits for work for long stretches by design (an event loop): no stuck reports. */
        boolean idleWaits;

        Co(Thread thread, int key) {
            this.thread = thread;
            this.key = key;
        }
    }

    private static final Map<Thread, Co> THREADS = new IdentityHashMap<>();
    /** Object.wait sets: monitor object → waiting threads, in arrival order. */
    private static final Map<Object, List<Thread>> WAITERS = new IdentityHashMap<>();
    private static int nextKey = 1;
    private static long sliceStart;
    private static Thread mainThread;

    private CoThreads() {
    }

    /** Registers the thread running main (the first coroutine, started by the page). */
    public static void initMain() {
        mainThread = Thread.currentThread();
        co(mainThread).alive = true;
        sliceStart = System.nanoTime();
        Thread watchdog = new Thread(CoThreads::watchdog, "Thread watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public static Thread mainThread() {
        return mainThread;
    }

    /** Marks a thread (before it starts) whose untimed waits are idle time, not a hang. */
    public static void expectIdleWaits(Thread t) {
        co(t).idleWaits = true;
    }

    private static Co co(Thread t) {
        Co c = THREADS.get(t);
        if (c == null) {
            c = new Co(t, nextKey++);
            THREADS.put(t, c);
        }
        return c;
    }

    private static void setCurrent(Thread t) {
        Target_com_oracle_svm_core_thread_PlatformThreads.currentThread.set(t);
    }

    private static void enter(Co c, int state) {
        c.state = state;
        c.since = System.nanoTime();
        if (TRACK_PARK_SITES && (state == PARKED || state == WAITING)) {
            c.parkSite = new Throwable("parked here");
        }
        if (isBlocking(state)) {
            WebScheduler.onThreadBlocking(c.thread); // a worker blocked in a task: others take the queue
        }
    }

    private static boolean isBlocking(int state) {
        return state == PARKED || state == PARKED_TIMED || state == WAITING || state == SLEEPING;
    }

    /** True while the thread waits for something (park, wait, sleep), as opposed to running or yielding. */
    static boolean isBlocked(Thread t) {
        Co c = THREADS.get(t);
        return c != null && isBlocking(c.state);
    }

    /** Called after every suspension: this stack is running again. */
    private static void resumed(Thread me, Co c) {
        setCurrent(me);
        sliceStart = System.nanoTime();
        c.state = RUNNING;
        c.since = sliceStart;
        c.parkSite = null;
        c.reported = false;
    }

    // ---- threads ----

    /** Thread.start: run the thread's body on a new stack, starting at the next turn. */
    public static void start(Thread t) {
        Co c = co(t);
        if (c.alive) {
            throw new IllegalThreadStateException("already started: " + t.getName());
        }
        c.alive = true;
        Thread me = Thread.currentThread();
        startCoroutine(() -> body(t));
        setCurrent(me); // the new stack ran until its first suspension
    }

    private static void body(Thread t) {
        // Like a real start(): the starting thread carries on first.
        Co c = co(t);
        enter(c, YIELDING);
        suspendYield();
        resumed(t, c);
        try {
            t.run();
        } catch (Throwable e) {
            try {
                t.getUncaughtExceptionHandler().uncaughtException(t, e);
            } catch (Throwable ignored) {
                // nothing left to report to
            }
        } finally {
            THREADS.remove(t);
            c.alive = false;
            notify(t, true); // Thread.join waits on the Thread object
        }
    }

    public static boolean isAlive(Thread t) {
        Co c = THREADS.get(t);
        return c != null && c.alive;
    }

    // ---- blocking ----

    /** LockSupport.park/parkNanos/parkUntil. */
    public static void park(boolean absolute, long time) {
        Thread me = Thread.currentThread();
        Co c = co(me);
        if (c.permit) {
            c.permit = false;
            return;
        }
        double ms;
        if (absolute) {
            ms = time - System.currentTimeMillis();
            if (ms <= 0) {
                return;
            }
        } else if (time == 0) {
            ms = -1; // until unparked
        } else {
            ms = time / 1_000_000.0;
        }
        enter(c, ms < 0 ? PARKED : PARKED_TIMED);
        c.parked = true;
        suspendPark(c.key, ms);
        c.parked = false;
        resumed(me, c);
        c.permit = false;
    }

    /** LockSupport.unpark. */
    public static void unpark(Thread t) {
        Co c = t == null ? null : THREADS.get(t);
        if (c == null) {
            return; // not started (or finished): nothing to wake, as in the JDK
        }
        c.permit = true;
        if (c.parked) {
            c.parked = false;
            wake(c.key);
        }
    }

    /** Thread.sleep. */
    public static void sleep(long nanos) {
        Thread me = Thread.currentThread();
        Co c = co(me);
        enter(c, SLEEPING);
        suspendSleep(nanos / 1_000_000.0);
        resumed(me, c);
    }

    /** Thread.yield: let the browser and other coroutines run. */
    public static void yieldNow() {
        Thread me = Thread.currentThread();
        Co c = co(me);
        enter(c, YIELDING);
        suspendYield();
        resumed(me, c);
    }

    /** For long-running work loops: yields once the current slice has used its share. */
    public static void maybeYield() {
        if (System.nanoTime() - sliceStart > SLICE_NANOS) {
            yieldNow();
        }
    }

    /**
     * For code with its own suspending import (e.g. waiting for a WebSocket message): call
     * before suspending, and {@link #endIoWait} with the result right after resuming, so
     * {@code Thread.currentThread()} is this thread again. Every suspension must be bracketed
     * like this: after a resume, the current-thread slot still names whoever ran last.
     */
    public static Thread beginIoWait() {
        Thread me = Thread.currentThread();
        enter(co(me), AWAITING_IO);
        return me;
    }

    public static void endIoWait(Thread me) {
        resumed(me, co(me));
    }

    /** Waits for the next animation frame (or the hidden-page ticker). */
    public static void awaitFrame() {
        Thread me = Thread.currentThread();
        Co c = co(me);
        enter(c, AWAITING_FRAME);
        suspendFrame();
        resumed(me, c);
    }

    /** Object.wait: suspended until notify/notifyAll, the timeout, or a spurious wakeup. */
    static void waitOn(Object obj, long timeoutMillis) {
        Thread me = Thread.currentThread();
        Co c = co(me);
        List<Thread> set = WAITERS.computeIfAbsent(obj, k -> new ArrayList<>());
        set.add(me);
        enter(c, timeoutMillis == 0 ? WAITING : PARKED_TIMED);
        c.parked = true;
        suspendPark(c.key, timeoutMillis == 0 ? -1 : timeoutMillis);
        c.parked = false;
        resumed(me, c);
        List<Thread> now = WAITERS.get(obj);
        if (now != null) {
            now.remove(me);
            if (now.isEmpty()) {
                WAITERS.remove(obj);
            }
        }
    }

    /** Object.notify / notifyAll. */
    static void notify(Object obj, boolean all) {
        List<Thread> set = WAITERS.get(obj);
        if (set == null || set.isEmpty()) {
            return;
        }
        List<Thread> woken = all ? new ArrayList<>(set) : List.of(set.get(0));
        set.removeAll(woken);
        if (set.isEmpty()) {
            WAITERS.remove(obj);
        }
        for (Thread t : woken) {
            Co c = THREADS.get(t);
            if (c != null && c.parked) {
                c.parked = false;
                wake(c.key);
            }
        }
    }

    // ---- watchdog ----

    private static void watchdog() {
        long lastStuckCheck = System.nanoTime();
        while (true) {
            sleep(1_000_000_000L);
            if (dumpRequested()) {
                dump();
            }
            long now = System.nanoTime();
            if (now - lastStuckCheck >= STUCK_NANOS) {
                lastStuckCheck = now;
                reportStuck(now);
            }
        }
    }

    private static boolean idleByDesign(Co c) {
        return LockSupport.getBlocker(c.thread) == WebScheduler.class; // idle worker, timer
    }

    private static void reportStuck(long now) {
        for (Co c : new ArrayList<>(THREADS.values())) {
            if ((c.state == PARKED || c.state == WAITING) && !c.reported && !c.idleWaits && now - c.since >= STUCK_NANOS
                    && !idleByDesign(c)) {
                c.reported = true;
                WebJs.log("[threads] '" + c.thread.getName() + "' has been " + STATE_NAMES[c.state] + " for "
                        + (now - c.since) / 1_000_000_000L + " s on " + describe(LockSupport.getBlocker(c.thread)));
                if (c.parkSite != null) {
                    c.parkSite.printStackTrace();
                }
            }
        }
    }

    private static void dump() {
        long now = System.nanoTime();
        StringBuilder sb = new StringBuilder("[threads] dump:");
        for (Co c : new ArrayList<>(THREADS.values())) {
            if (!c.alive) {
                continue; // registered (e.g. marked) but never started
            }
            sb.append("\n  '").append(c.thread.getName()).append("' ").append(STATE_NAMES[c.state])
                    .append(" for ").append((now - c.since) / 1_000_000L).append(" ms");
            Object blocker = LockSupport.getBlocker(c.thread);
            if (blocker != null) {
                sb.append(" on ").append(describe(blocker));
            }
        }
        WebJs.log(sb.toString());
        for (Co c : new ArrayList<>(THREADS.values())) {
            if (c.parkSite != null && !idleByDesign(c)) {
                WebJs.log("[threads] '" + c.thread.getName() + "' parked at:");
                c.parkSite.printStackTrace();
            }
        }
    }

    private static String describe(Object blocker) {
        if (blocker == null) {
            return "(no blocker)";
        }
        return blocker instanceof Class<?> k ? k.getName() : blocker.getClass().getName();
    }

    // ---- JS side (web/dev/host.js, mcweb.co). The build wraps the suspend* imports in
    // WebAssembly.Suspending: they return promises and the calling stack waits for them.

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.co.park(key, ms);", args = {"key", "ms"})
    private static native int suspendPark(int key, double ms);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.co.sleep(ms);", args = {"ms"})
    private static native int suspendSleep(double ms);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.co.yieldNow();", args = {})
    private static native int suspendYield();

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.co.frame();", args = {})
    private static native int suspendFrame();

    @JS.Coerce
    @JS(value = "globalThis.mcweb.co.wake(key);", args = {"key"})
    private static native void wake(int key);

    @JS.Coerce
    @JS(value = "const r = !!globalThis.mcweb.co.dumpRequested; globalThis.mcweb.co.dumpRequested = false; return r;", args = {})
    private static native boolean dumpRequested();

    /** Runs r.run() on a new stack (a promising call); returns when that stack first suspends. */
    @JS(value = "globalThis.mcweb.co.start(r);", args = {"r"})
    private static native void startCoroutine(Runnable r);
}

/** The runtime's current-thread slot, and the sleep/isAlive hooks of java.lang.Thread. */
@TargetClass(className = "com.oracle.svm.core.thread.PlatformThreads")
final class Target_com_oracle_svm_core_thread_PlatformThreads {
    @Alias static FastThreadLocalObject<Thread> currentThread;

    @Substitute
    static void sleep(long nanos) throws InterruptedException {
        if (nanos < 0) {
            throw new IllegalArgumentException("Timeout value is negative");
        }
        CoThreads.sleep(nanos);
    }

    @Substitute
    static boolean isAlive(Thread thread) {
        return CoThreads.isAlive(thread);
    }
}

/**
 * Thread.sleep's native. SVM core's substitution of it (to PlatformThreads.sleep) is not
 * active in Web Image, and Web Image's own (a no-op) is disabled in build-image.mjs.
 */
@TargetClass(Thread.class)
final class Target_java_lang_Thread_Sleep {
    @Substitute
    private static void sleepNanos0(long nanos) throws InterruptedException {
        CoThreads.sleep(nanos);
    }
}

@TargetClass(className = "com.oracle.svm.webimage.threads.WebImageJSJavaThreads")
final class Target_com_oracle_svm_webimage_threads_WebImageJSJavaThreads {
    @Substitute
    protected void yieldCurrent() {
        CoThreads.yieldNow();
    }
}

/** Object.wait/notify on green threads (monitor enter/exit stay no-ops). */
@TargetClass(className = "com.oracle.svm.webimage.threads.WebImageSingleThreadedMonitorSupport")
final class Target_com_oracle_svm_webimage_threads_WebImageSingleThreadedMonitorSupport {
    @Substitute
    protected void doWait(Object obj, long timeoutMillis) {
        CoThreads.waitOn(obj, timeoutMillis);
    }

    @Substitute
    public void notify(Object obj, boolean notifyAll) {
        CoThreads.notify(obj, notifyAll);
    }
}
