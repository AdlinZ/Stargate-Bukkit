package org.sgrewritten.stargate.thread.task;

import org.bukkit.Bukkit;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.util.BungeeHelper;

/**
 * Runs task on the global thread (Folia) or on the main thread (paper)
 */
public abstract class StargateGlobalTask extends StargateTask {
    private final Stargate plugin;
    private boolean bungee = false;

    protected StargateGlobalTask() {
        this(false);
    }

    protected StargateGlobalTask(boolean bungee) {
        this(bungee, USING_FOLIA);
    }

    StargateGlobalTask(boolean bungee, boolean usingFolia) {
        super(usingFolia);
        this.plugin = Stargate.getInstance();
        this.bungee = bungee;
    }

    @Override
    public void runDelayed(long delay) {
        if (!canSchedule()) {
            return;
        }
        if (usingFolia) {
            super.registerFoliaTask(Bukkit.getServer().getGlobalRegionScheduler().runDelayed(plugin, super::runTask, Math.max(1, delay)));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(super::runTask).runTaskLater(plugin, delay));
        }
    }

    @Override
    public void runNow() {
        if (!canSchedule()) {
            return;
        }
        super.setRepeatable(false);
        if (bungee && !BungeeHelper.canSendBungeeMessages()) {
            runTaskTimer(20, 20, () -> {
                if (BungeeHelper.canSendBungeeMessages()) {
                    this.run();
                    this.cancel();
                }
            });
            return;
        }
        if (usingFolia) {
            super.registerFoliaTask(Bukkit.getServer().getGlobalRegionScheduler().run(plugin, this::runTask));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(this::runTask).runTask(plugin));
        }
    }

    public void runTaskTimer(long period, long delay, Runnable runnable) {
        if (!canSchedule()) {
            return;
        }
        super.setRepeatable(true);
        if (usingFolia) {
            super.registerFoliaTask(Bukkit.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> super.runTask(runnable), Math.max(1, delay), period));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(() -> super.runTask(runnable)).runTaskTimer(plugin, delay, period));
        }
    }

    @Override
    public void runTaskTimer(long period, long delay) {
        if (!canSchedule()) {
            return;
        }
        runTaskTimer(period, delay, this);
    }
}
