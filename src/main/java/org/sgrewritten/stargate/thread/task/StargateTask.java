package org.sgrewritten.stargate.thread.task;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.property.NonLegacyClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class StargateTask implements Runnable {
    protected static final boolean USING_FOLIA = NonLegacyClass.REGIONIZED_SERVER.isImplemented();
    private static final Set<StargateTask> tasks = ConcurrentHashMap.newKeySet();
    protected final boolean usingFolia;

    protected StargateTask() {
        this(USING_FOLIA);
    }

    StargateTask(boolean usingFolia) {
        this.usingFolia = usingFolia;
    }

    private final Stargate owner = Stargate.getInstance();
    private final List<Runnable> cancellations = new ArrayList<>();
    private boolean cancelled;
    private boolean running;
    private boolean completed;
    private boolean repeatable;

    /** All delays and periods exposed by these wrappers are measured in ticks. */
    public abstract void runDelayed(long delay);
    public abstract void runNow();
    public abstract void runTaskTimer(long period, long delay);

    public synchronized void cancel() {
        cancelled = true;
        cancellations.forEach(Runnable::run);
        cancellations.clear();
        tasks.remove(this);
    }

    protected synchronized boolean canSchedule() {
        if (owner != null && !owner.isEnabled()) {
            cancel();
        }
        return !cancelled && !completed;
    }

    protected synchronized void registerTask() {
        if (!cancelled && !completed) {
            tasks.add(this);
        }
    }

    protected synchronized void registerCancellation(Runnable cancellation) {
        if (owner != null && !owner.isEnabled()) {
            cancel();
        }
        if (cancelled || completed) {
            cancellation.run();
        } else {
            cancellations.add(cancellation);
            registerTask();
        }
    }

    protected void registerFoliaTask(ScheduledTask task) {
        if (task == null) {
            // Entity schedulers return null when the entity has already retired.
            cancel();
        } else {
            registerCancellation(task::cancel);
        }
    }

    protected void registerBukkitTask(BukkitTask task) {
        registerCancellation(task::cancel);
    }

    /** Cancel scheduled world work without discarding accepted database work. */
    public static void cancelScheduledTasks() {
        for (StargateTask task : List.copyOf(tasks)) {
            if (!(task instanceof StargateQueuedAsyncTask)) {
                task.cancel();
            }
        }
    }

    static void cancelQueuedTasks() {
        for (StargateTask task : List.copyOf(tasks)) {
            if (task instanceof StargateQueuedAsyncTask) {
                task.cancel();
            }
        }
    }

    /**
     * Legacy test utility. Never use to flush work during shutdown: Folia tasks
     * must execute on their owning scheduler, and database work on its queue.
     */
    @Deprecated
    public static void forceRunAllTasks() {
        if (USING_FOLIA || !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Cannot force tasks outside the Paper main thread");
        }
        Set<StargateTask> visited = new HashSet<>();
        for (int cycle = 0; cycle < 10; cycle++) {
            List<StargateTask> pending = tasks.stream()
                    .filter(task -> !(task instanceof StargateQueuedAsyncTask) && visited.add(task)).toList();
            if (pending.isEmpty()) {
                return;
            }
            for (StargateTask task : pending) {
                task.runTask();
            }
        }
    }

    protected void runTask() {
        runTask(this);
    }

    protected void runTask(Runnable action) {
        synchronized (this) {
            if (cancelled || completed || running) {
                return;
            }
            running = true;
            completed = !repeatable;
        }
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            cancel();
            throw failure;
        } finally {
            synchronized (this) {
                running = false;
                if (completed) {
                    cancel();
                }
            }
        }
    }

    protected void runTask(ScheduledTask task) {
        runTask();
    }

    protected synchronized void setRepeatable(boolean repeatable) {
        this.repeatable = repeatable;
    }
}
