package org.sgrewritten.stargate.thread.task;

import org.bukkit.entity.Entity;
import org.sgrewritten.stargate.Stargate;

/**
 * Runs task on an entity thread (Folia) or on the main thread (paper)
 */
public abstract class StargateEntityTask extends StargateTask {

    private final Entity entity;
    private final Stargate plugin;

    protected StargateEntityTask(Entity entity) {
        this(entity, USING_FOLIA);
    }

    StargateEntityTask(Entity entity, boolean usingFolia) {
        super(usingFolia);
        this.entity = entity;
        this.plugin = Stargate.getInstance();
    }

    @Override
    public void runNow() {
        if (!canSchedule()) {
            return;
        }
        if (usingFolia) {
            super.registerFoliaTask(entity.getScheduler().run(plugin, super::runTask, this::cancel));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(super::runTask).runTask(plugin));
        }
    }

    @Override
    public void runDelayed(long delay) {
        if (!canSchedule()) {
            return;
        }
        if (usingFolia) {
            super.registerFoliaTask(entity.getScheduler().runDelayed(plugin, super::runTask, this::cancel, Math.max(1, delay)));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(super::runTask).runTaskLater(plugin, delay));
        }
    }

    @Override
    public void runTaskTimer(long period, long delay) {
        if (!canSchedule()) {
            return;
        }
        super.setRepeatable(true);
        if (usingFolia) {
            super.registerFoliaTask(entity.getScheduler().runAtFixedRate(plugin, super::runTask, this::cancel, Math.max(1, delay), period));
        } else {
            super.registerBukkitTask(new StargateBukkitRunnable(super::runTask).runTaskTimer(plugin, delay, period));
        }
    }
}
