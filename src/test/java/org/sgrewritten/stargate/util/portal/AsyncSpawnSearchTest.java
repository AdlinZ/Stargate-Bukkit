package org.sgrewritten.stargate.util.portal;

import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sgrewritten.stargate.StargateExtension;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class AsyncSpawnSearchTest {
    @MockBukkitInject private ServerMock server;
    private WorldMock world;
    private record Chunk(int x, int z) { }
    private interface Blocks { Material type(int x, int y, int z); }

    @BeforeEach void setUp() { world = server.addSimpleWorld("world"); }

    private ChunkSnapshot snapshot(Chunk chunk, Blocks blocks) {
        return (ChunkSnapshot) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ChunkSnapshot.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getBlockType")) throw new AssertionError(method);
                    int x = (int) args[0], y = (int) args[1], z = (int) args[2];
                    assertTrue(x >= 0 && x < 16 && z >= 0 && z < 16);
                    assertTrue(y >= world.getMinHeight() && y < world.getMaxHeight());
                    return blocks.type(chunk.x * 16 + x, y, chunk.z * 16 + z);
                });
    }

    @Test void waitsForEverySnapshotAndKeepsCandidatePreference() {
        Location preferred = new Location(world, -.5, 10, .5), later = new Location(world, 32.5, 10, .5);
        Map<Chunk, CompletableFuture<ChunkSnapshot>> pending = new LinkedHashMap<>();
        CompletableFuture<Location> result = AsyncSpawnSearch.find(1, 2, List.of(preferred, later), at ->
                pending.computeIfAbsent(new Chunk(at.getBlockX() >> 4, at.getBlockZ() >> 4), k -> new CompletableFuture<>()));
        assertEquals(2, pending.size());
        Chunk second = new Chunk(2, 0), first = new Chunk(-1, 0);
        Blocks floor = (x, y, z) -> y == 9 ? Material.STONE : Material.AIR;
        pending.get(second).complete(snapshot(second, floor));
        assertFalse(result.isDone());
        pending.get(first).complete(snapshot(first, floor));
        assertEquals(preferred, result.join());
    }

    @Test void checksTheFullEntityWidthAcrossNegativeChunkBoundaries() {
        Location blocked = new Location(world, 0, 10, 0), clear = new Location(world, 32, 10, 0);
        Map<Chunk, Integer> requests = new LinkedHashMap<>();
        Location result = AsyncSpawnSearch.find(2, 2, List.of(blocked, clear), at -> {
            Chunk chunk = new Chunk(at.getBlockX() >> 4, at.getBlockZ() >> 4);
            requests.merge(chunk, 1, Integer::sum);
            return CompletableFuture.completedFuture(snapshot(chunk, (x, y, z) ->
                    y == 9 || (x == -1 && y == 10 && z == -1) ? Material.STONE : Material.AIR));
        }).join();
        assertEquals(clear, result);
        assertTrue(requests.containsKey(new Chunk(-1, -1)));
        assertTrue(requests.containsKey(new Chunk(0, 0)));
        assertTrue(requests.values().stream().allMatch(n -> n == 1));
    }

    @Test void returnsNoSpawnWithoutSolidFloor() {
        assertNull(AsyncSpawnSearch.find(1, 2, List.of(new Location(world, .5, 10, .5)), at ->
                CompletableFuture.completedFuture(snapshot(new Chunk(0, 0), (x, y, z) -> Material.AIR))).join());
    }

    @Test void propagatesSnapshotFailureInsteadOfUsingLiveWorldBlocks() {
        CompletableFuture<Location> result = AsyncSpawnSearch.find(1, 2, List.of(new Location(world, .5, 10, .5)),
                at -> CompletableFuture.failedFuture(new IllegalStateException("snapshot unavailable")));
        assertThrows(CompletionException.class, result::join);
    }

    @Test void treatsCoordinatesOutsideBuildHeightAsAir() {
        assertNull(AsyncSpawnSearch.find(1, 2, List.of(new Location(world, .5, world.getMinHeight(), .5)), at ->
                CompletableFuture.completedFuture(snapshot(new Chunk(0, 0), (x, y, z) -> Material.AIR))).join());
    }
}
