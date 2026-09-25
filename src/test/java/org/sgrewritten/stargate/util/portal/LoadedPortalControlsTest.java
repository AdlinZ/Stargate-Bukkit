package org.sgrewritten.stargate.util.portal;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sgrewritten.stargate.StargateAPIMock;
import org.sgrewritten.stargate.StargateExtension;
import org.sgrewritten.stargate.api.gate.GateFormatRegistry;
import org.sgrewritten.stargate.api.network.portal.PortalPosition;
import org.sgrewritten.stargate.api.network.portal.PositionType;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.api.network.portal.flag.CustomFlag;
import org.sgrewritten.stargate.api.network.portal.flag.StargateFlag;
import org.sgrewritten.stargate.exception.InvalidStructureException;
import org.sgrewritten.stargate.gate.Gate;
import org.sgrewritten.stargate.network.NetworkType;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.network.portal.TestPortalBuilder;
import org.sgrewritten.stargate.network.portal.portaldata.GateData;
import org.sgrewritten.stargate.property.StargateConstant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class LoadedPortalControlsTest {
    @MockBukkitInject private ServerMock server;
    private StargateAPIMock api;
    private Gate gate;
    private RealPortal portal;
    private BlockVector signVector;
    private BlockVector buttonVector;

    @BeforeEach
    void setUp() throws Exception {
        var world = server.addSimpleWorld("world");
        api = new StargateAPIMock();
        var network = api.getNetworkManager().createNetwork("network", NetworkType.CUSTOM, StorageType.LOCAL, false);
        gate = new Gate(new GateData(GateFormatRegistry.getFormat("nether.gate"), false,
                new Location(world, 0, 10, 0), BlockFace.SOUTH), api.getRegistry());
        signVector = gate.getFormat().getControlBlocks().getFirst();
        buttonVector = gate.getFormat().getControlBlocks().getLast();
        gate.getLocation(signVector).getBlock().setType(Material.OAK_WALL_SIGN);
        gate.addPortalPosition(new PortalPosition(PositionType.SIGN, signVector, StargateConstant.STARGATE_NAME));
        portal = new TestPortalBuilder(api.getRegistry(), world).setGate(gate).setNetwork(network).build();
    }

    @Test
    void restoresOneCoreButtonWithoutDuplicatingItOnAnotherPass() throws Exception {
        List<PortalPosition> restored = LoadedPortalControls.restore(portal, api);
        assertEquals(1, restored.size());
        assertEquals(PositionType.BUTTON, restored.getFirst().getPositionType());
        assertEquals(buttonVector, restored.getFirst().getRelativePositionLocation());
        assertEquals("Stargate", restored.getFirst().getPluginName());
        assertTrue(restored.getFirst().isActive());
        assertTrue(LoadedPortalControls.restore(portal, api).isEmpty());
    }

    @Test
    void alwaysOnPortalDoesNotGainAButton() throws Exception {
        portal.addFlag(StargateFlag.ALWAYS_ON);
        assertTrue(LoadedPortalControls.restore(portal, api).isEmpty());
        assertEquals(1, gate.getPortalPositions().size());
    }

    @Test
    void reRegistersExistingSignWhenItsStoredPositionIsMissing() throws Exception {
        gate.removePortalPosition(gate.getPortalPositions().getFirst());
        List<PortalPosition> restored = LoadedPortalControls.restore(portal, api);
        assertEquals(2, restored.size());
        PortalPosition sign = restored.stream().filter(p -> p.getPositionType() == PositionType.SIGN).findFirst().orElseThrow();
        assertEquals(signVector, sign.getRelativePositionLocation());
        assertNotNull(sign.getAttachment());
    }

    @Test
    void registeredAddonFlagKeepsItsControlLayout() throws Exception {
        api.getMaterialHandlerResolver().registerCustomFlag('E');
        portal.addFlag(CustomFlag.getOrCreate('E'));
        assertTrue(LoadedPortalControls.restore(portal, api).isEmpty());
        assertEquals(1, gate.getPortalPositions().size());
    }

    @Test
    void unregisteredAddonFlagDoesNotPreventButtonRecovery() throws Exception {
        portal.addFlag(CustomFlag.getOrCreate('E'));
        assertEquals(1, LoadedPortalControls.restore(portal, api).size());
    }

    @Test
    void installedAddonCanReactivateItsOwnStoredControls() throws Exception {
        var addon = MockBukkit.createMockPlugin();
        PortalPosition custom = new PortalPosition(PositionType.CUSTOM, buttonVector, addon.getName(), false);
        gate.addPortalPosition(custom);
        assertTrue(LoadedPortalControls.restore(portal, api).isEmpty());
        assertSame(custom, gate.getPortalPositions().stream().filter(p -> p.equals(custom)).findFirst().orElseThrow());
        assertFalse(custom.isActive());
    }

    @Test
    void doesNotOverwriteInactivePositionsBelongingToAbsentAddon() {
        gate.addPortalPosition(new PortalPosition(PositionType.CUSTOM, buttonVector, "MissingAddon", false));
        assertThrows(InvalidStructureException.class, () -> LoadedPortalControls.restore(portal, api));
        assertEquals(2, gate.getPortalPositions().size());
    }

    @Test
    void doesNotOverwriteBlocksToMakeRoomForButton() {
        gate.getLocation(buttonVector).getBlock().setType(Material.DIAMOND_BLOCK);
        assertThrows(InvalidStructureException.class, () -> LoadedPortalControls.restore(portal, api));
        assertEquals(Material.DIAMOND_BLOCK, gate.getLocation(buttonVector).getBlock().getType());
        assertEquals(1, gate.getPortalPositions().size());
    }

    @Test
    void rejectedRepairDoesNotPartiallyRegisterRediscoveredSign() {
        gate.removePortalPosition(gate.getPortalPositions().getFirst());
        gate.getLocation(buttonVector).getBlock().setType(Material.STONE);
        assertThrows(InvalidStructureException.class, () -> LoadedPortalControls.restore(portal, api));
        assertTrue(gate.getPortalPositions().isEmpty());
    }

    @Test
    void missingPhysicalSignIsReportedWithoutCreatingOrDeletingBlocks() {
        gate.removePortalPosition(gate.getPortalPositions().getFirst());
        gate.getLocation(signVector).getBlock().setType(Material.AIR);
        assertThrows(InvalidStructureException.class, () -> LoadedPortalControls.restore(portal, api));
        assertTrue(gate.getPortalPositions().isEmpty());
        assertEquals(Material.AIR, gate.getLocation(signVector).getBlock().getType());
    }

    @Test
    void doesNotClaimAnotherPortalsControlPosition() throws Exception {
        var otherGate = new Gate(new GateData(gate.getFormat(), false,
                gate.getTopLeft().clone().add(20, 0, 0), BlockFace.SOUTH), api.getRegistry());
        var other = new TestPortalBuilder(api.getRegistry(), gate.getTopLeft().getWorld())
                .setGate(otherGate).setNetwork(portal.getNetwork()).setName("other").build();
        Location occupied = gate.getLocation(buttonVector);
        PortalPosition foreign = new PortalPosition(PositionType.BUTTON, otherGate.getRelativeVector(occupied).toBlockVector(), "Stargate");
        api.getRegistry().registerPortalPosition(foreign, occupied, other);
        assertThrows(InvalidStructureException.class, () -> LoadedPortalControls.restore(portal, api));
        assertSame(other, api.getRegistry().getPortalPosition(occupied).getPortal());
    }
}
