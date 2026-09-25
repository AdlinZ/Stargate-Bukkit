package org.sgrewritten.stargate.util.portal;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.api.network.portal.RealPortal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Reads destination chunks on their owning regions, then searches immutable snapshots. */
public final class AsyncSpawnSearch {
    private record ChunkPosition(int x, int z) { }

    private AsyncSpawnSearch() { }

    /** Must be called on the entity's owning thread; no entity is accessed after this call returns. */
    public static CompletableFuture<Location> find(Entity entity, RealPortal destination) {
        int width = (int) Math.ceil(entity.getWidth());
        int height = (int) Math.ceil(entity.getHeight());
        return find(width, height, TeleportationHelper.getSpawnCandidates(width, destination),
                AsyncSpawnSearch::snapshot);
    }

    static CompletableFuture<Location> find(int width, int height, List<Location> candidates,
                                            Function<Location, CompletableFuture<ChunkSnapshot>> snapshotProvider) {
        if (candidates.isEmpty()) return CompletableFuture.completedFuture(null);
        World world = candidates.getFirst().getWorld();
        int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        Map<ChunkPosition, CompletableFuture<ChunkSnapshot>> snapshots = new LinkedHashMap<>();
        for (Location candidate : candidates) {
            Location corner = candidate.clone().subtract(width / 2.0, 0, width / 2.0);
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    Location column = corner.clone().add(x, 0, z);
                    ChunkPosition key = new ChunkPosition(column.getBlockX() >> 4, column.getBlockZ() >> 4);
                    snapshots.computeIfAbsent(key, ignored -> snapshotProvider.apply(column));
                }
            }
        }
        return CompletableFuture.allOf(snapshots.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            for (Location candidate : candidates) {
                Location corner = candidate.clone().subtract(width / 2.0, 0, width / 2.0);
                boolean clear = true;
                for (Location occupied : TeleportationHelper.getOccupiedLocations(width, height, corner)) {
                    if (solid(occupied, snapshots, minY, maxY)) { clear = false; break; }
                }
                if (!clear) continue;
                for (Location floor : TeleportationHelper.getFloorLocations(width, corner)) {
                    if (solid(floor, snapshots, minY, maxY)) return candidate.clone();
                }
            }
            return null;
        });
    }

    private static boolean solid(Location at, Map<ChunkPosition, CompletableFuture<ChunkSnapshot>> snapshots,
                                 int minY, int maxY) {
        if (at.getBlockY() < minY || at.getBlockY() >= maxY) return false;
        ChunkSnapshot snapshot = snapshots.get(new ChunkPosition(at.getBlockX() >> 4, at.getBlockZ() >> 4)).join();
        return snapshot.getBlockType(at.getBlockX() & 15, at.getBlockY(), at.getBlockZ() & 15).isSolid();
    }

    private static CompletableFuture<ChunkSnapshot> snapshot(Location at) {
        CompletableFuture<ChunkSnapshot> result = new CompletableFuture<>();
        try {
            at.getWorld().getChunkAtAsync(at).whenComplete((chunk, error) -> {
                if (error != null) { result.completeExceptionally(error); return; }
                try {
                    Bukkit.getRegionScheduler().execute(Stargate.getInstance(), at, () -> {
                        try { result.complete(chunk.getChunkSnapshot(false, false, false)); }
                        catch (RuntimeException e) { result.completeExceptionally(e); }
                    });
                } catch (RuntimeException e) { result.completeExceptionally(e); }
            });
        } catch (RuntimeException e) { result.completeExceptionally(e); }
        return result;
    }
}
