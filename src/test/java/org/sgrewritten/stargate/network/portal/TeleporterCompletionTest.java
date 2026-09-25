package org.sgrewritten.stargate.network.portal;

import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.BoatMock;
import be.seeseemelk.mockbukkit.entity.HorseMock;
import be.seeseemelk.mockbukkit.entity.PoweredMinecartMock;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sgrewritten.stargate.StargateAPIMock;
import org.sgrewritten.stargate.StargateExtension;
import org.sgrewritten.stargate.api.gate.ExplicitGateBuilder;
import org.sgrewritten.stargate.api.event.portal.StargateTeleportPortalEvent;
import org.sgrewritten.stargate.api.gate.GateFormatRegistry;
import org.sgrewritten.stargate.api.network.Network;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.economy.StargateEconomyManagerMock;
import org.sgrewritten.stargate.network.NetworkType;
import org.sgrewritten.stargate.network.StargateNetwork;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.thread.SynchronousPopulator;
import org.sgrewritten.stargate.util.LanguageManagerMock;
import org.sgrewritten.stargate.util.StargateTestHelper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class TeleporterCompletionTest {
    @MockBukkitInject
    private ServerMock server;
    private WorldMock world;
    private RealPortal origin;
    private RealPortal destination;
    private final ControlledTeleport controlled = new ControlledTeleport();

    @BeforeEach
    void setUp() throws Exception {
        world = server.addSimpleWorld("world");
        StargateAPIMock api = new StargateAPIMock();
        Network network = new StargateNetwork("completion", NetworkType.CUSTOM, StorageType.LOCAL);
        origin = new TestPortalBuilder(api.getRegistry(), world).setName("origin").setNetwork(network)
                .setGateBuilder(new ExplicitGateBuilder(api.getRegistry(), new Location(world, 0, 10, 0),
                        GateFormatRegistry.getFormat("nether.gate")).setFacing(BlockFace.SOUTH)).build();
        destination = new TestPortalBuilder(api.getRegistry(), world).setName("destination").setNetwork(network)
                .setGateBuilder(new ExplicitGateBuilder(api.getRegistry(), new Location(world, 4096, 10, 0),
                        GateFormatRegistry.getFormat("nether.gate"))).build();
        new SynchronousPopulator();
    }

    private Teleporter teleporter() {
        return new Teleporter(destination, origin, BlockFace.NORTH, BlockFace.SOUTH, 0, "arrived",
                new LanguageManagerMock(), new StargateEconomyManagerMock(), new EntityTeleportation(true));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void appliesExitVelocityOnlyAfterSuccessfulCompletion(boolean success) {
        HorseMock horse = new HorseMock(server, UUID.randomUUID()) {
            @Override
            public CompletableFuture<Boolean> teleportAsync(Location location) {
                return controlled.start(location);
            }

            @Override
            public EntityScheduler getScheduler() {
                return controlled;
            }

            @Override
            public void setVelocity(Vector velocity) {
                if (controlled.destination != null) {
                    assertTrue(controlled.executing, "Velocity must be set on the owning thread");
                }
                super.setVelocity(velocity);
            }
        };
        horse.setLocation(new Location(world, 0, 10, 0));
        horse.setVelocity(new Vector(1, 0, 0));

        teleporter().teleport(horse);
        StargateTestHelper.runAllTasks();
        assertNotNull(controlled.destination);
        assertEquals(1, horse.getVelocity().getX());
        assertNull(horse.nextMessage());

        controlled.result.complete(success);
        assertEquals(1, horse.getVelocity().getX());
        controlled.runCompletion();
        assertEquals(success ? -1 : 1, horse.getVelocity().getX(), 0.00001);
        if (success) {
            assertEquals("arrived", horse.nextMessage());
        } else {
            assertNull(horse.nextMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restoresMinecartFuelAfterResultAndMomentumAfterSuccess(boolean success) {
        PoweredMinecartMock minecart = new PoweredMinecartMock(server, UUID.randomUUID()) {
            @Override
            public double getWidth() {
                return 0.98;
            }

            @Override
            public CompletableFuture<Boolean> teleportAsync(Location location) {
                return controlled.start(location);
            }

            @Override
            public EntityScheduler getScheduler() {
                return controlled;
            }
        };
        minecart.setLocation(new Location(world, 0, 10, 0));
        minecart.setFuel(40);
        minecart.setVelocity(new Vector(1, 0, 0));

        teleporter().teleport(minecart);
        StargateTestHelper.runAllTasks();
        assertNotNull(controlled.destination);
        assertEquals(0, minecart.getFuel());
        assertEquals(new Vector(), minecart.getVelocity());

        controlled.result.complete(success);
        assertEquals(0, minecart.getFuel());
        controlled.runCompletion();
        assertEquals(40, minecart.getFuel());
        StargateTestHelper.runAllTasks();
        assertEquals(success ? -1 : 1, minecart.getVelocity().getX(), 0.00001);
    }

    @Test
    void keepsBoatMarkedUntilCompletionAndAllowsRetryAfterFailure() {
        int[] attempts = {0};
        int[] permissionChecks = {0};
        server.getPluginManager().registerEvent(StargateTeleportPortalEvent.class, new Listener() { },
                EventPriority.NORMAL, (listener, event) -> permissionChecks[0]++, MockBukkit.createMockPlugin());
        BoatMock boat = new BoatMock(server, UUID.randomUUID()) {
            @Override
            public double getWidth() {
                return 1.4;
            }

            @Override
            public CompletableFuture<Boolean> teleportAsync(Location location) {
                attempts[0]++;
                return controlled.start(location);
            }

            @Override
            public EntityScheduler getScheduler() {
                return controlled;
            }
        };
        boat.setLocation(new Location(world, 0, 10, 0));
        teleporter().teleport(boat);
        StargateTestHelper.runAllTasks();
        teleporter().teleport(boat);
        StargateTestHelper.runAllTasks();
        assertEquals(1, attempts[0]);
        assertEquals(1, permissionChecks[0]);

        controlled.result.complete(false);
        teleporter().teleport(boat);
        StargateTestHelper.runAllTasks();
        assertEquals(1, attempts[0]);

        controlled.runCompletion();
        teleporter().teleport(boat);
        StargateTestHelper.runAllTasks();
        assertEquals(2, attempts[0]);
        assertEquals(2, permissionChecks[0]);
        controlled.runCompletion();
    }
}
