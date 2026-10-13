package dev.mcwebclient.web.exec;

import java.util.ArrayDeque;
import java.util.PriorityQueue;

import dev.mcwebclient.web.WebJs;

/**
 * The image runs on one thread. Work that would run on executor or helper threads is
 * queued here and run cooperatively:
 * <ul>
 *   <li>every frame, for a time budget ({@link #pumpFrame});</li>
 *   <li>whenever code blocks (LockSupport.park → {@link #onPark}), so a thread waiting
 *       for a future lets the task that completes it run.</li>
 * </ul>
 * A park that finds nothing to run returns immediately (a legal spurious wake-up); a
 * caller that keeps parking with no work anywhere for too long is a deadlock and fails
 * loudly instead of freezing the tab.
 */
public final class WebScheduler {
    private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();
    private static final PriorityQueue<Timed> TIMED = new PriorityQueue<>();
    private static final long DEADLOCK_NANOS = 20_000_000_000L;

    private static int depth;
    private static long idleParkSince = -1;
    private static long seq;

    private record Timed(long due, long seq, Runnable task) implements Comparable<Timed> {
        @Override
        public int compareTo(Timed o) {
            return due != o.due ? Long.compare(due, o.due) : Long.compare(seq, o.seq);
        }
    }

    private WebScheduler() {
    }

    public static void submit(Runnable task) {
        QUEUE.add(task);
    }

    public static void schedule(Runnable task, long delayNanos) {
        TIMED.add(new Timed(System.nanoTime() + Math.max(0, delayNanos), seq++, task));
    }

    public static int pending() {
        return QUEUE.size() + TIMED.size();
    }

    /** Runs one ready task; returns false if none was ready. */
    public static boolean runOne() {
        promoteDueTimers();
        Runnable task = QUEUE.poll();
        if (task == null) {
            return false;
        }
        depth++;
        try {
            task.run();
        } catch (Throwable t) {
            WebJs.log("[sched] task failed: " + t);
            t.printStackTrace();
        } finally {
            depth--;
        }
        return true;
    }

    /** Runs queued work until the budget is spent or the queue is empty. */
    public static void pumpFrame(long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        while (System.nanoTime() < end && runOne()) {
            // keep going
        }
    }

    /** Runs everything currently runnable (used where callers must observe completion). */
    public static void drain() {
        while (runOne()) {
            // keep going
        }
    }

    /** Hook for LockSupport.park: make progress elsewhere, then return. */
    public static void onPark(boolean absolute, long time) {
        if (runOne()) {
            idleParkSince = -1;
            return;
        }
        long now = System.nanoTime();
        if (idleParkSince < 0) {
            idleParkSince = now;
        } else if (now - idleParkSince > DEADLOCK_NANOS) {
            idleParkSince = -1;
            throw new IllegalStateException("single-threaded deadlock: parked for "
                    + DEADLOCK_NANOS / 1_000_000_000L + "s with no runnable work (depth " + depth + ")");
        }
    }

    private static void promoteDueTimers() {
        if (TIMED.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        while (!TIMED.isEmpty() && TIMED.peek().due() <= now) {
            QUEUE.add(TIMED.poll().task());
        }
    }
}
