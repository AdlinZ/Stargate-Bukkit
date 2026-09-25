package org.sgrewritten.stargate.thread.task;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.thread.SynchronousPopulator;

/**
 * Runs task in a region (Folia) or in the main thread (paper)
 */
public abstract class StargateRegionTask extends StargateTask {

    private final Location location;
    private final Stargate plugin;
    private static final SynchronousPopulator populator = new SynchronousPopulator();

    private final boolean bungee;

    protected StargateRegionTask(Location location, boolean bungee) {
        this(location, bungee, USING_FOLIA);
    }

    StargateRegionTask(Location location, boolean bungee, boolean usingFolia) {
        super(usingFolia);
        this.location = location.clone();
        this.plugin = Stargate.getInstance();
        this.bungee = bungee;
    }

    protected StargateRegionTask(Location location) {
        this(location, false);
    }

    @Override
    public void runNow() {
        if (!canSchedule()) {
            return;
        }
        if (usingFolia) {
            ScheduledTask theTask = Bukkit.getServer().getRegionScheduler().run(plugin, location, super::runTask);
            super.registerFoliaTask(theTask);
        } else {
            runPopulatorTask();
        }
    }

    @Override
    public void runDelayed(long delay) {
        if (!canSchedule()) {
            return;
        }
        if (usingFolia) {
            ScheduledTask theTask = Bukkit.getServer().getRegionScheduler().runDelayed(plugin, location, super::runTask, Math.max(1, delay));
            super.registerFoliaTask(theTask);
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(this::runPopulatorTask).runTaskLater(plugin, delay));
        }
    }

    @Override
    public void runTaskTimer(long period, long delay) {
        if (!canSchedule()) {
            return;
        }
        super.setRepeatable(true);
        if (usingFolia) {
            ScheduledTask theTask = Bukkit.getServer().getRegionScheduler().runAtFixedRate(plugin, location, super::runTask, Math.max(1, delay), period);
            super.registerFoliaTask(theTask);
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(this::runPopulatorTask).runTaskTimer(plugin, delay, period));
        }
    }

    private void runPopulatorTask() {
        if (!canSchedule()) {
            return;
        }
        populator.addAction(super::runTask, bungee);
        super.registerTask();
    }

    public static void startPopulator(Plugin plugin) {
        if (USING_FOLIA) {
            return;
        }
        new StargateBukkitRunnable(populator).runTaskTimer(plugin, 0, 1);
    }

    public static void clearPopulator() {
        populator.clear();
    }
}
