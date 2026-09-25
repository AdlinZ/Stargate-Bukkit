package org.sgrewritten.stargate.network.portal;

import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.BoatMock;
import be.seeseemelk.mockbukkit.entity.HorseMock;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.StargateAPIMock;
import org.sgrewritten.stargate.StargateExtension;
import org.sgrewritten.stargate.api.config.ConfigurationOption;
import org.sgrewritten.stargate.api.gate.ExplicitGateBuilder;
import org.sgrewritten.stargate.api.gate.GateFormatRegistry;
import org.sgrewritten.stargate.api.network.Network;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.economy.StargateEconomyManagerMock;
import org.sgrewritten.stargate.network.NetworkType;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.util.LanguageManagerMock;
import org.sgrewritten.stargate.util.StargateTestHelper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class LeashedTeleportTest {
    @MockBukkitInject private ServerMock server;
    private WorldMock world;
    private RealPortal origin;
    private RealPortal destination;
    private final ControlledTeleport holderTrip = new ControlledTeleport();
    private final ControlledTeleport boatTrip = new ControlledTeleport();
    private HorseMock holder;
    private LeashableBoat boat;

    /** Simulates the public non-living leash API available on Paper 1.21+. */
    public static class LeashableBoat extends BoatMock {
        private Entity holder;
        final ControlledTeleport trip;
        public LeashableBoat(ServerMock server, ControlledTeleport trip) { super(server, UUID.randomUUID()); this.trip = trip; }
        public boolean isLeashed() { return holder != null; }
        public Entity getLeashHolder() { return holder; }
        public boolean setLeashHolder(Entity entity) { holder = entity; return true; }
        @Override public double getWidth() { return 1.4; }
        @Override public boolean isValid() { return !isDead(); }
        @Override public CompletableFuture<Boolean> teleportAsync(Location location) { return trip.start(location); }
        @Override public EntityScheduler getScheduler() { return trip; }
    }

    @BeforeEach
    void setUp() throws Exception {
        world = server.addSimpleWorld("world");
        StargateAPIMock api = new StargateAPIMock();
        Network network = api.getNetworkManager().selectNetwork("network", NetworkType.CUSTOM, StorageType.LOCAL);
        origin = new TestPortalBuilder(api.getRegistry(), world).setName("origin").setNetwork(network)
                .setGateBuilder(new ExplicitGateBuilder(api.getRegistry(), new Location(world, 0, 10, 0),
                        GateFormatRegistry.getFormat("nether.gate"))).build();
        destination = new TestPortalBuilder(api.getRegistry(), world).setName("dest").setNetwork(network)
                .setGateBuilder(new ExplicitGateBuilder(api.getRegistry(), new Location(world, 4000, 50, 0),
                        GateFormatRegistry.getFormat("nether.gate"))).build();
        boat = new LeashableBoat(server, boatTrip);
        holder = new HorseMock(server, UUID.randomUUID()) {
            @Override public List<Entity> getNearbyEntities(double x, double y, double z) { return List.of(boat); }
            @Override public boolean isValid() { return !isDead(); }
            @Override public boolean eject() {
                // MockBukkit's eject clears the list but leaves passengers' vehicle references stale.
                List<Entity> passengers = List.copyOf(getPassengers());
                passengers.forEach(Entity::leaveVehicle);
                return !passengers.isEmpty();
            }
            @Override public CompletableFuture<Boolean> teleportAsync(Location location) { return holderTrip.start(location); }
            @Override public EntityScheduler getScheduler() { return holderTrip; }
        };
        holder.setLocation(new Location(world, 0, 10, 0));
        boat.setLocation(new Location(world, 1, 10, 0));
        boat.setLeashHolder(holder);
    }

    private void start() {
        new Teleporter(destination, origin, BlockFace.NORTH, BlockFace.SOUTH, 0, "arrived",
                new LanguageManagerMock(), new StargateEconomyManagerMock(), new EntityTeleportation(true)).teleport(holder);
        StargateTestHelper.runAllTasks();
    }

    private void finish(ControlledTeleport trip, boolean success) {
        if (success) {
            if (trip == holderTrip) holder.setLocation(trip.destination);
            else boat.setLocation(trip.destination);
        }
        trip.result.complete(success);
        trip.runCompletion();
        StargateTestHelper.runAllTasks();
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void boatFollowsAndLeashWaitsForBothResults(boolean boatFirst, boolean success) {
        start();
        assertNotNull(boatTrip.destination);
        assertNotNull(holderTrip.destination);
        assertNull(boat.getLeashHolder());
        finish(boatFirst ? boatTrip : holderTrip, success);
        assertNull(boat.getLeashHolder());
        finish(boatFirst ? holderTrip : boatTrip, success);
        assertSame(holder, boat.getLeashHolder());
    }

    @Test
    void partialFailureDoesNotAttachAcrossDistantRegions() {
        start(); finish(holderTrip, true); finish(boatTrip, false);
        assertNull(boat.getLeashHolder());
    }

    @Test
    void entityRetirementCannotReattachOrKeepBoatInFlight() {
        start();
        boatTrip.result.complete(true);
        boatTrip.retired.run();
        finish(holderTrip, true);
        assertNull(boat.getLeashHolder());
        // A fresh request must reach the boat again instead of being blocked by a leaked marker.
        boat.setLocation(holder.getLocation());
        boat.setLeashHolder(holder);
        holderTrip.destination = null;
        start();
        assertNotNull(holderTrip.destination);
        boatTrip.runCompletion(); holderTrip.runCompletion();
    }

    @Test
    void disabledLeashHandlingLeavesBoatUntouched() {
        Stargate.getFileConfiguration().set(ConfigurationOption.HANDLE_LEASHES.getConfigNode(), false);
        try {
            start();
            assertNull(boatTrip.destination);
            assertSame(holder, boat.getLeashHolder());
            finish(holderTrip, true);
        } finally {
            Stargate.getFileConfiguration().set(ConfigurationOption.HANDLE_LEASHES.getConfigNode(), null);
        }
    }

    @Test
    void oldApiBoatIsNotTreatedAsLeashable() {
        assertNull(LeashSupport.holder(new BoatMock(server, UUID.randomUUID())));
    }

    @Test
    void relationTraversalChargesEachEntityOnlyOnceEvenInCycle() {
        boat.setLeashHolder(holder);
        holder.setLeashHolder(boat);
        int[] checks = {0};
        TeleportedEntityRelationDFS search = new TeleportedEntityRelationDFS(entity -> { checks[0]++; return true; }, List.of(holder, boat));
        assertTrue(search.depthFirstSearch(holder));
        assertEquals(2, checks[0]);
    }

    @Test
    void passengerWaitsForVesselAndOwnTeleport() {
        boat.setLeashHolder(null);
        holder.addPassenger(boat);
        start();
        assertFalse(holder.getPassengers().contains(boat));
        finish(holderTrip, true);
        assertFalse(holder.getPassengers().contains(boat));
        finish(boatTrip, true);
        assertTrue(holder.getPassengers().contains(boat));
    }

    @Test
    void noSafeArrivalKeepsBoatInPlaceAndAllowsRetry() {
        Teleporter arrival = new Teleporter(destination, null, BlockFace.NORTH, BlockFace.SOUTH, 0, "arrived",
                new LanguageManagerMock(), new StargateEconomyManagerMock(), new EntityTeleportation(true));
        Location before = boat.getLocation();
        assertDoesNotThrow(() -> arrival.teleport(boat));
        StargateTestHelper.runAllTasks();
        assertNull(boatTrip.destination);
        assertEquals(before, boat.getLocation());
        assertTrue(boat.nextMessage().contains("DESTINATION_BLOCKED"));
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                destination.getExit().clone().add(x, -1, z).getBlock().setType(org.bukkit.Material.STONE);
            }
        }
        new Teleporter(destination, null, BlockFace.NORTH, BlockFace.SOUTH, 0, "arrived",
                new LanguageManagerMock(), new StargateEconomyManagerMock(), new EntityTeleportation(true)).teleport(boat);
        StargateTestHelper.runAllTasks();
        assertNotNull(boatTrip.destination);
        finish(boatTrip, true);
    }

    @Test
    void releasingLeashBeforeScheduledStartDoesNotLeaveBoatLocked() {
        new Teleporter(destination, origin, BlockFace.NORTH, BlockFace.SOUTH, 0, "arrived",
                new LanguageManagerMock(), new StargateEconomyManagerMock(), new EntityTeleportation(true)).teleport(holder);
        boat.setLeashHolder(null);
        StargateTestHelper.runAllTasks();
        assertNull(boatTrip.destination);
        finish(holderTrip, false);
        new Teleporter(destination, origin, BlockFace.NORTH, BlockFace.SOUTH, 0, "arrived",
                new LanguageManagerMock(), new StargateEconomyManagerMock(), new EntityTeleportation(true)).teleport(boat);
        StargateTestHelper.runAllTasks();
        assertNotNull(boatTrip.destination);
        finish(boatTrip, true);
    }

    @Test
    void cancellingSourceTaskBeforeItRunsReleasesRelatedBoatMarkers() {
        new Teleporter(destination, origin, BlockFace.NORTH, BlockFace.SOUTH, 0, "arrived",
                new LanguageManagerMock(), new StargateEconomyManagerMock(), new EntityTeleportation(true)).teleport(holder);
        org.sgrewritten.stargate.thread.task.StargateTask.cancelScheduledTasks();
        start();
        assertNotNull(boatTrip.destination);
        finish(holderTrip, true); finish(boatTrip, true);
    }
}
