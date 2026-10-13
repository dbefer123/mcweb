package dev.mcwebclient.web.exec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Executor and timer work on green threads ({@link CoThreads}). Tasks from thread pools
 * (Minecraft's worker executors, ThreadPoolExecutor, ScheduledThreadPoolExecutor) are
 * queued here and run by worker coroutines, which yield to the browser between tasks once
 * their time slice is used up.
 *
 * <p>A new worker is started only when no existing one can take the task: all are busy and
 * blocked inside their task (parked on a future, say). Workers that block while tasks are
 * queued hand the queue on ({@link #onThreadBlocking}). That keeps "task waits for a task
 * queued behind it" from deadlocking, without a stack per queued task.
 */
public final class WebScheduler {
    private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();
    private static final PriorityQueue<Timed> TIMED = new PriorityQueue<>();
    private static final ArrayDeque<Thread> IDLE = new ArrayDeque<>();
    private static final List<Thread> WORKERS = new ArrayList<>();
    private static final int MAX_WORKERS = 64;

    private static Thread timer;
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
        wakeWorker();
    }

    public static void schedule(Runnable task, long delayNanos) {
        TIMED.add(new Timed(System.nanoTime() + Math.max(0, delayNanos), seq++, task));
        if (timer == null) {
            timer = new Thread(WebScheduler::timerLoop, "Web scheduler timer");
            timer.setDaemon(true);
            timer.start();
        } else {
            LockSupport.unpark(timer); // re-evaluate the earliest deadline
        }
    }

    /** Queued (not yet started) tasks, including delayed ones. */
    public static int pending() {
        return QUEUE.size() + TIMED.size();
    }

    /** Waits up to {@code timeoutNanos} for the run queue to empty (executor shutdown). */
    public static boolean awaitQueueEmpty(long timeoutNanos) {
        long end = System.nanoTime() + timeoutNanos;
        while (!QUEUE.isEmpty() && System.nanoTime() < end) {
            CoThreads.sleep(1_000_000L);
        }
        return QUEUE.isEmpty();
    }

    /**
     * Called when a thread is about to block (park, wait, sleep). A worker blocking inside its
     * task could wait for a task queued behind it: hand the queue to another worker.
     */
    static void onThreadBlocking(Thread t) {
        if (!QUEUE.isEmpty() && WORKERS.contains(t)) {
            wakeWorker();
        }
    }

    private static void wakeWorker() {
        Thread idle = IDLE.poll();
        if (idle != null) {
            LockSupport.unpark(idle);
            return;
        }
        for (Thread w : WORKERS) {
            if (!CoThreads.isBlocked(w)) {
                return; // a running (or yielding) worker takes the task when it gets there
            }
        }
        if (WORKERS.size() < MAX_WORKERS) {
            Thread w = new Thread(WebScheduler::workerLoop, "Worker-" + (WORKERS.size() + 1));
            w.setDaemon(true);
            WORKERS.add(w);
            w.start();
        }
    }

    private static void workerLoop() {
        Thread me = Thread.currentThread();
        while (true) {
            Runnable task = QUEUE.poll();
            if (task == null) {
                IDLE.add(me);
                LockSupport.park(WebScheduler.class);
                IDLE.remove(me); // spurious wake-up: not taken from IDLE by wakeWorker
                continue;
            }
            try {
                task.run();
            } catch (Throwable t) {
                me.getUncaughtExceptionHandler().uncaughtException(me, t);
            }
            CoThreads.maybeYield();
        }
    }

    private static void timerLoop() {
        while (true) {
            long now = System.nanoTime();
            while (!TIMED.isEmpty() && TIMED.peek().due() <= now) {
                submit(TIMED.poll().task());
            }
            if (TIMED.isEmpty()) {
                LockSupport.park(WebScheduler.class);
            } else {
                LockSupport.parkNanos(WebScheduler.class, TIMED.peek().due() - now);
            }
        }
    }
}
