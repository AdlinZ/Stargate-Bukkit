package org.sgrewritten.stargate.database;

import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.StargateAPIMock;
import org.sgrewritten.stargate.StargateExtension;
import org.sgrewritten.stargate.api.network.Network;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.config.TableNameConfiguration;
import org.sgrewritten.stargate.database.property.PropertiesDatabaseMock;
import org.sgrewritten.stargate.exception.name.NameConflictException;
import org.sgrewritten.stargate.network.NetworkType;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.network.portal.TestPortalBuilder;
import org.sgrewritten.stargate.util.database.DatabaseHelper;

import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class PortalMutationTest {
    @MockBukkitInject private ServerMock server;
    @TempDir private Path temporary;
    private SQLiteDatabase database;
    private SQLDatabase storage;
    private StargateAPIMock api;
    private WorldMock world;

    @BeforeEach
    void setUp() throws Exception {
        world = server.addSimpleWorld("world");
        Stargate.setServerUUID(UUID.randomUUID());
        database = new SQLiteDatabase(temporary.resolve("portals.db").toFile());
        storage = new SQLDatabase(database, false, false, new PropertiesDatabaseMock());
        DatabaseHelper.createTables(database, new SQLQueryGenerator(new TableNameConfiguration("", ""), DatabaseDriver.SQLITE), true);
        api = new StargateAPIMock(storage);
    }

    private RealPortal portal(StargateAPIMock instance, String networkName, StorageType type) throws Exception {
        Network network = instance.getNetworkManager().selectNetwork(networkName, NetworkType.CUSTOM, type);
        RealPortal result = new TestPortalBuilder(instance.getRegistry(), world).setName("gate").setNetwork(network).setStorageType(type).build();
        result.getGate().addPortalPosition(new org.sgrewritten.stargate.api.network.portal.PortalPosition(
                org.sgrewritten.stargate.api.network.portal.PositionType.BUTTON,
                new org.bukkit.util.BlockVector(0, 0, 0), "absent-addon", false));
        return result;
    }

    private String value(String sql) throws Exception {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    @Test
    void ownerChangePersistsInFreshConnection() throws Exception {
        RealPortal portal = portal(api, "first", StorageType.LOCAL);
        portal.setName("MixedCase");
        storage.savePortalToStorage(portal);
        UUID owner = UUID.randomUUID();
        portal.setOwner(owner);
        assertEquals(owner.toString(), value("SELECT ownerUUID FROM Portal"));
        assertEquals(owner, portal.getOwnerUUID());
    }

    @Test
    void networkMovePersistsCascadingControlsAndFlagsAndUpdatesBothRegistries() throws Exception {
        RealPortal portal = portal(api, "first", StorageType.LOCAL);
        Network before = portal.getNetwork();
        before.addPortal(portal);
        storage.savePortalToStorage(portal);
        String positionCount = value("SELECT COUNT(*) FROM PortalPosition");
        String flagCount = value("SELECT COUNT(*) FROM PortalFlagRelation");
        Network after = api.getNetworkManager().selectNetwork("second", NetworkType.CUSTOM, StorageType.LOCAL);
        portal.setNetwork(after);
        assertNull(before.getPortal("gate"));
        assertSame(portal, after.getPortal("gate"));
        assertEquals("second", value("SELECT network FROM Portal"));
        assertEquals(positionCount, value("SELECT COUNT(*) FROM PortalPosition WHERE networkName = 'second'"));
        assertEquals(flagCount, value("SELECT COUNT(*) FROM PortalFlagRelation WHERE network = 'second'"));
        assertEquals("0", value("SELECT COUNT(*) FROM PortalPosition WHERE networkName = 'first'"));
    }

    @Test
    void databaseConflictLeavesNetworkAndControlsUntouched() throws Exception {
        RealPortal first = portal(api, "first", StorageType.LOCAL);
        first.getNetwork().addPortal(first);
        storage.savePortalToStorage(first);
        RealPortal other = portal(api, "second", StorageType.LOCAL);
        storage.savePortalToStorage(other); // Deliberately absent from this registry, like a stale snapshot.
        assertThrows(IllegalStateException.class, () -> first.setNetwork(other.getNetwork()));
        assertEquals("first", first.getNetwork().getId());
        assertSame(first, first.getNetwork().getPortal("gate"));
        assertEquals("2", value("SELECT COUNT(*) FROM Portal"));
        assertNotEquals("0", value("SELECT COUNT(*) FROM PortalPosition WHERE networkName = 'first'"));
    }

    @Test
    void failedOwnerWriteDoesNotPublishUnsavedOwner() throws Exception {
        RealPortal portal = portal(api, "first", StorageType.LOCAL);
        storage.savePortalToStorage(portal);
        UUID original = portal.getOwnerUUID();
        storage.removePortalFromStorage(portal);
        assertThrows(IllegalStateException.class, () -> portal.setOwner(UUID.randomUUID()));
        assertEquals(original, portal.getOwnerUUID());
    }

    @Test
    void storageTypeChangeIsRejectedBeforeMemoryOrDatabaseMutation() throws Exception {
        RealPortal portal = portal(api, "first", StorageType.LOCAL);
        storage.savePortalToStorage(portal);
        Network remote = api.getNetworkManager().selectNetwork("remote", NetworkType.CUSTOM, StorageType.INTER_SERVER);
        assertThrows(IllegalArgumentException.class, () -> portal.setNetwork(remote));
        assertEquals("first", portal.getNetwork().getId());
        assertEquals("first", value("SELECT network FROM Portal"));
    }

    @Test
    void sharedDatabaseRejectsSecondServerBeforeItRegistersOrAnnouncesPortal() throws Exception {
        RealPortal first = portal(api, "shared", StorageType.INTER_SERVER);
        api.getNetworkManager().savePortal(first, first.getNetwork());
        String firstServer = value("SELECT homeServerId FROM InterPortal");
        Stargate.setServerUUID(UUID.randomUUID());
        StargateAPIMock secondApi = new StargateAPIMock(storage);
        RealPortal second = portal(secondApi, "shared", StorageType.INTER_SERVER);
        assertThrows(NameConflictException.class, () -> secondApi.getNetworkManager().savePortal(second, second.getNetwork()));
        assertNull(second.getNetwork().getPortal("gate"));
        assertSame(first, first.getNetwork().getPortal("gate"));
        assertEquals("1", value("SELECT COUNT(*) FROM InterPortal"));
        assertEquals(firstServer, value("SELECT homeServerId FROM InterPortal"));
    }

    @Test
    void moveToDefaultNetworkChangesStoredTypeFlagAtomically() throws Exception {
        RealPortal portal = portal(api, "first", StorageType.LOCAL);
        portal.getNetwork().addPortal(portal);
        storage.savePortalToStorage(portal);
        Network target = api.getNetworkManager().selectNetwork(org.sgrewritten.stargate.property.StargateConstant.DEFAULT_NETWORK_ID,
                NetworkType.DEFAULT, StorageType.LOCAL);
        portal.setNetwork(target);
        assertTrue(portal.hasFlag(NetworkType.DEFAULT.getRelatedFlag()));
        assertFalse(portal.hasFlag(NetworkType.CUSTOM.getRelatedFlag()));
        assertEquals("1", value("SELECT COUNT(*) FROM PortalFlagRelation r JOIN Flag f ON f.id = r.flag WHERE f.character = '"
                + NetworkType.DEFAULT.getRelatedFlag().getCharacterRepresentation() + "'"));
        assertEquals("0", value("SELECT COUNT(*) FROM PortalFlagRelation r JOIN Flag f ON f.id = r.flag WHERE f.character = '"
                + NetworkType.CUSTOM.getRelatedFlag().getCharacterRepresentation() + "'"));
    }
}
