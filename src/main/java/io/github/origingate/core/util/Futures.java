package io.github.origingate.core.util;

import java.util.concurrent.*;

/** Per-caller deadlines never cancel a shared lookup. Idle timer threads expire. */
public final class Futures {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, threads("OriginGate Deadlines"));
    /**
     * Dependents of a timed-out future (a log write, a disconnect, an alert) run here, never on the shared timer.
     * They are short, so a few threads are enough; a burst of timeouts waits in the queue instead of starting a thread each.
     */
    private static final ThreadPoolExecutor EXPIRED = new ThreadPoolExecutor(4, 4, 1, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), threads("OriginGate Timeouts"));
    static {
        TIMER.setRemoveOnCancelPolicy(true);
        TIMER.setKeepAliveTime(1, TimeUnit.SECONDS);
        TIMER.allowCoreThreadTimeOut(true);
        EXPIRED.allowCoreThreadTimeOut(true);
    }
    private Futures() { }
    private static ThreadFactory threads(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }
    public static <T> CompletableFuture<T> copy(CompletableFuture<T> source) {
        return source.thenApply(value -> value);
    }
    public static <T> CompletableFuture<T> timeout(CompletableFuture<T> source, long time, TimeUnit unit) {
        CompletableFuture<T> result = copy(source);
        if (result.isDone()) return result;
        ScheduledFuture<?> deadline = TIMER.schedule(() -> expire(result), time, unit);
        result.whenComplete((value, error) -> deadline.cancel(false));
        return result;
    }
    private static void expire(CompletableFuture<?> result) {
        Runnable timeout = () -> result.completeExceptionally(new TimeoutException());
        try {
            EXPIRED.execute(timeout);
        } catch (RejectedExecutionException | OutOfMemoryError ex) {
            // No thread could be started: time out here rather than never.
            timeout.run();
        }
    }
}
