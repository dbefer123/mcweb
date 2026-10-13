package dev.mcwebclient.web.exec;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** An ExecutorService whose tasks run on {@link WebScheduler}'s worker coroutines. */
public final class WebExecutorService extends AbstractExecutorService {
    private final String name;
    private boolean shutdown;

    public WebExecutorService(String name) {
        this.name = name;
    }

    @Override
    public void execute(Runnable command) {
        if (shutdown) {
            throw new java.util.concurrent.RejectedExecutionException(name + " is shut down");
        }
        WebScheduler.submit(command);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && WebScheduler.pending() == 0;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return WebScheduler.awaitQueueEmpty(unit.toNanos(timeout));
    }

    @Override
    public String toString() {
        return "WebExecutorService[" + name + "]";
    }
}
