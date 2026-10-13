package dev.mcwebclient.web.jdk;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.exec.WebScheduler;
import dev.mcwebclient.web.exec.WebThreads;

/**
 * Blocking and threads on a single-threaded runtime. Replaces Web Image's versions,
 * which throw (park) or silently drop the thread (start0); see build-image.mjs for
 * the matching -H:DisableSubstitution entries.
 */
final class ConcurrencySubstitutions {
    private ConcurrencySubstitutions() {
    }
}

@TargetClass(className = "jdk.internal.misc.Unsafe")
final class Target_jdk_internal_misc_Unsafe_Park {
    @Substitute
    public void park(boolean isAbsolute, long time) {
        WebScheduler.onPark(isAbsolute, time);
    }

    @Substitute
    public void unpark(Object thread) {
        // Nothing is ever really parked; waiters re-check their condition.
    }
}

@TargetClass(Thread.class)
final class Target_java_lang_Thread_Start {
    @Substitute
    private void start0() {
        WebThreads.start((Thread) (Object) this);
    }
}

/**
 * Web Image runs ForkJoinPool.execute/invoke and ForkJoinTask.fork inline but leaves
 * submit() on the stock queues, which no worker drains (Fabric's mod discovery then
 * "times out" instantly). Run submitted tasks inline too, through quietlyInvoke() so the
 * task is marked done (isDone/get work) and failures surface from get().
 */
@TargetClass(java.util.concurrent.ForkJoinPool.class)
final class Target_java_util_concurrent_ForkJoinPool_Submit {
    @Substitute
    public <T> java.util.concurrent.ForkJoinTask<T> submit(java.util.concurrent.ForkJoinTask<T> task) {
        task.quietlyInvoke();
        return task;
    }

    @Substitute
    public <T> java.util.concurrent.ForkJoinTask<T> submit(java.util.concurrent.Callable<T> task) {
        java.util.concurrent.ForkJoinTask<T> t = java.util.concurrent.ForkJoinTask.adapt(task);
        t.quietlyInvoke();
        return t;
    }

    @Substitute
    public <T> java.util.concurrent.ForkJoinTask<T> submit(Runnable task, T result) {
        java.util.concurrent.ForkJoinTask<T> t = java.util.concurrent.ForkJoinTask.adapt(task, result);
        t.quietlyInvoke();
        return t;
    }

    @Substitute
    public java.util.concurrent.ForkJoinTask<?> submit(Runnable task) {
        java.util.concurrent.ForkJoinTask<?> t = java.util.concurrent.ForkJoinTask.adapt(task);
        t.quietlyInvoke();
        return t;
    }

    @Substitute
    public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
        return true;
    }
}

/**
 * Web Image's fork() calls exec() directly, so the task never becomes isDone() (its join()
 * reads the raw result and hides that). Run through the JDK's own doExec path instead.
 */
@TargetClass(java.util.concurrent.ForkJoinTask.class)
final class Target_java_util_concurrent_ForkJoinTask_Fork {
    @Substitute
    @SuppressWarnings("unchecked")
    public final java.util.concurrent.ForkJoinTask<Object> fork() {
        java.util.concurrent.ForkJoinTask<Object> self = (java.util.concurrent.ForkJoinTask<Object>) (Object) this;
        self.quietlyInvoke();
        return self;
    }
}

/**
 * Thread pools can't start worker threads here (Web Image reports a new thread as
 * already running, so addWorker throws IllegalThreadStateException). Their tasks run on
 * the cooperative scheduler instead; shutdown/awaitTermination keep their JDK behaviour
 * (no workers, empty queue → terminated).
 */
@TargetClass(java.util.concurrent.ThreadPoolExecutor.class)
final class Target_java_util_concurrent_ThreadPoolExecutor {
    @Alias
    final native void reject(Runnable command);

    @Substitute
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        if (((java.util.concurrent.ThreadPoolExecutor) (Object) this).isShutdown()) {
            reject(command);
            return;
        }
        WebScheduler.submit(command);
    }
}

/** Delayed and periodic tasks become timed scheduler entries. */
@TargetClass(java.util.concurrent.ScheduledThreadPoolExecutor.class)
final class Target_java_util_concurrent_ScheduledThreadPoolExecutor {
    @Substitute
    private void delayedExecute(java.util.concurrent.RunnableScheduledFuture<?> task) {
        WebScheduler.schedule(task, task.getDelay(java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    @Substitute
    void reExecutePeriodic(java.util.concurrent.RunnableScheduledFuture<?> task) {
        WebScheduler.schedule(task, task.getDelay(java.util.concurrent.TimeUnit.NANOSECONDS));
    }
}

@TargetClass(java.io.FileInputStream.class)
final class Target_java_io_FileInputStream_Length {
    /** 0 makes readAllBytes/readNBytes fall back to the generic InputStream loop. */
    @Substitute
    private long length0() {
        return 0;
    }

    @Substitute
    private long position0() {
        return 0;
    }
}

@TargetClass(className = "java.util.zip.ZipUtils")
final class Target_java_util_zip_ZipUtils {
    /** There is no native zip library; zlib is pure Java (see ZipSubstitutions). */
    @Substitute
    static void loadLibrary() {
    }
}
