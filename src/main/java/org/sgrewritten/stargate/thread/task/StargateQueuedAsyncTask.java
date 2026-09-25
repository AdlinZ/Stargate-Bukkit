package org.sgrewritten.stargate.thread.task;

import org.sgrewritten.stargate.Stargate;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/** Serial database work, drained by its own worker before shutdown completes. */
public abstract class StargateQueuedAsyncTask extends StargateTask {
    private static final Object QUEUE_LOCK = new Object();
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 10_000;
    private static final Runnable STOP = () -> { };
    /** @deprecated Submit through runNow(), or wait through waitForEmptyQueue(). */
    @Deprecated
    public static volatile BlockingQueue<Runnable> asyncQueue = new LinkedBlockingQueue<>();
    private static Thread worker;
    private static long workerId;
    private static boolean accepting;

    public static void waitForEmptyQueue() {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        synchronized (QUEUE_LOCK) {
            if (Thread.currentThread() == worker) {
                throw new IllegalStateException("The queue worker cannot wait for itself");
            }
            if (!accepting) {
                throw new IllegalStateException("The database queue is not running");
            }
            asyncQueue.add(() -> barrier.complete(null));
        }
        try {
            barrier.get(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for database work", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Database work did not finish", e);
        }
    }

    @Override
    public void runDelayed(long delay) {
        if (!canSchedule()) {
            return;
        }
        StargateAsyncTask timer = enqueueTimer();
        registerCancellation(timer::cancel);
        timer.runDelayed(delay);
    }

    @Override
    public void runNow() {
        synchronized (QUEUE_LOCK) {
            if (!canSchedule()) {
                return;
            }
            if (!accepting) {
                cancel();
                Stargate.log(Level.WARNING, "Database task rejected: the queue is stopping or stopped");
                return;
            }
            registerTask();
            asyncQueue.add(super::runTask);
        }
    }

    @Override
    public void runTaskTimer(long period, long delay) {
        if (!canSchedule()) {
            return;
        }
        setRepeatable(true);
        StargateAsyncTask timer = enqueueTimer();
        registerCancellation(timer::cancel);
        timer.runTaskTimer(period, delay);
    }

    private StargateAsyncTask enqueueTimer() {
        return new StargateAsyncTask() {
            @Override
            public void run() {
                StargateQueuedAsyncTask.this.runNow();
            }
        };
    }

    public static void disableAsyncQueue(long id) {
        disableAsyncQueue(id, SHUTDOWN_TIMEOUT_MILLIS);
    }

    static void disableAsyncQueue(long id, long timeoutMillis) {
        Thread stoppingWorker;
        synchronized (QUEUE_LOCK) {
            if (worker == null || workerId != id) {
                return;
            }
            if (accepting) {
                accepting = false;
                asyncQueue.add(STOP);
            }
            stoppingWorker = worker;
        }
        if (Thread.currentThread() == stoppingWorker) {
            return;
        }
        try {
            stoppingWorker.join(timeoutMillis);
            if (stoppingWorker.isAlive()) {
                Stargate.log(Level.SEVERE, "Database queue shutdown timed out; accepted writes may still be pending");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Stargate.log(Level.WARNING, "Interrupted while draining the database queue; writes may still be pending");
        }
    }

    public static void enableAsyncQueue(long id) {
        synchronized (QUEUE_LOCK) {
            if (worker != null && worker.isAlive()) {
                if (workerId == id && accepting) {
                    return;
                }
                throw new IllegalStateException("The previous database queue worker has not stopped");
            }
            BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
            asyncQueue = queue;
            workerId = id;
            accepting = true;
            worker = new Thread(() -> cycleThroughAsyncQueue(queue), "Stargate-database-" + id);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void cycleThroughAsyncQueue(BlockingQueue<Runnable> queue) {
        try {
            while (true) {
                Runnable action = queue.take();
                if (action == STOP) {
                    return;
                }
                try {
                    action.run();
                } catch (Exception e) {
                    Stargate.log(e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Stargate.log(Level.WARNING, "Database queue interrupted before draining");
        } finally {
            synchronized (QUEUE_LOCK) {
                accepting = false;
            }
            StargateTask.cancelQueuedTasks();
        }
    }
}
