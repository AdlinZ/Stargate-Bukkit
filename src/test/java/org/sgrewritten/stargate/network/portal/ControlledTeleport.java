package org.sgrewritten.stargate.network.portal;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A manually advanced teleport and entity scheduler, independent of wall-clock timing. */
class ControlledTeleport implements EntityScheduler {
    final CompletableFuture<Boolean> result = new CompletableFuture<>();
    Location destination;
    Runnable completion;
    Runnable retired;
    boolean reject;
    boolean executing;
    RuntimeException startFailure;
    RuntimeException scheduleFailure;

    CompletableFuture<Boolean> start(Location location) {
        if (startFailure != null) {
            throw startFailure;
        }
        destination = location;
        return result;
    }

    @Override
    public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delay) {
        if (scheduleFailure != null) {
            throw scheduleFailure;
        }
        if (reject) {
            return false;
        }
        this.completion = run;
        this.retired = retired;
        return true;
    }

    void runCompletion() {
        executing = true;
        try {
            completion.run();
        } finally {
            executing = false;
        }
    }

    @Override
    public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired) {
        throw new AssertionError("Unexpected scheduler method");
    }

    @Override
    public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired, long delay) {
        throw new AssertionError("Unexpected scheduler method");
    }

    @Override
    public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired,
                                        long initialDelay, long period) {
        throw new AssertionError("Unexpected scheduler method");
    }
}
