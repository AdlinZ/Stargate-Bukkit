package org.sgrewritten.stargate.network.portal;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.sgrewritten.stargate.Stargate;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Completes teleport work on the entity's owning thread, including failed teleports.
 */
final class EntityTeleportation {

    private final boolean regionized;

    EntityTeleportation(boolean regionized) {
        this.regionized = regionized;
    }

    /**
     * Must be called on the source entity's owning thread. Cleanup must only touch
     * plugin bookkeeping: it may run after entity retirement or scheduler rejection.
     */
    void teleport(Entity entity, Location destination, Consumer<Boolean> completion, Runnable cleanup) {
        CompletableFuture<Boolean> result;
        try {
            Location snapshot = destination.clone();
            result = regionized ? entity.teleportAsync(snapshot)
                    : CompletableFuture.completedFuture(entity.teleport(snapshot));
        } catch (RuntimeException exception) {
            result = CompletableFuture.failedFuture(exception);
        }
        result.whenComplete((success, error) -> {
            if (error != null) {
                Stargate.log(error);
            }
            Runnable finish = () -> finish(completion, error == null && Boolean.TRUE.equals(success), cleanup);
            if (regionized) {
                scheduleCompletion(entity, finish, cleanup);
            } else {
                finish.run();
            }
        });
    }

    private void scheduleCompletion(Entity entity, Runnable finish, Runnable cleanup) {
        try {
            if (!entity.getScheduler().execute(Stargate.getInstance(), finish, cleanup, 1)) {
                cleanup.run();
            }
        } catch (RuntimeException exception) {
            cleanup.run();
            Stargate.log(exception);
        }
    }

    private void finish(Consumer<Boolean> completion, boolean success, Runnable cleanup) {
        try {
            completion.accept(success);
        } catch (RuntimeException exception) {
            Stargate.log(exception);
        } finally {
            cleanup.run();
        }
    }
}
