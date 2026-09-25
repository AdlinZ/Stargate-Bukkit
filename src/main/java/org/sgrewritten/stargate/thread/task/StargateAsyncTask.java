package org.sgrewritten.stargate.thread.task;

import org.bukkit.Bukkit;
import org.sgrewritten.stargate.Stargate;

import java.util.concurrent.TimeUnit;

/**
 * Runs tasks asynchronously
 */
public abstract class StargateAsyncTask extends StargateTask {

    private final Stargate plugin;

    protected StargateAsyncTask() {
        this(USING_FOLIA);
    }

    StargateAsyncTask(boolean usingFolia) {
        super(usingFolia);
        this.plugin = Stargate.getInstance();
    }

    static long ticksToMillis(long ticks) {
        return Math.multiplyExact(ticks, 50L);
    }

    @Override
    public void runNow() {
        if (!canSchedule()) {
            return;
        }
        if (usingFolia) {
            super.registerFoliaTask(Bukkit.getServer().getAsyncScheduler().runNow(plugin, super::runTask));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(super::runTask).runTaskAsynchronously(plugin));
        }
    }

    @Override
    public void runDelayed(long delay) {
        if (!canSchedule()) {
            return;
        }
        if (usingFolia) {
            super.registerFoliaTask(Bukkit.getServer().getAsyncScheduler().runDelayed(plugin, super::runTask, ticksToMillis(delay), TimeUnit.MILLISECONDS));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(super::runTask).runTaskLaterAsynchronously(plugin, delay));
        }
    }

    @Override
    public void runTaskTimer(long period, long delay) {
        if (!canSchedule()) {
            return;
        }
        super.setRepeatable(true);
        if (usingFolia) {
            super.registerFoliaTask(Bukkit.getServer().getAsyncScheduler().runAtFixedRate(plugin, super::runTask, ticksToMillis(delay), ticksToMillis(period), TimeUnit.MILLISECONDS));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(super::runTask).runTaskTimerAsynchronously(plugin, delay, period));
        }
    }
}
