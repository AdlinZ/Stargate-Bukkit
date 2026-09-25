package org.sgrewritten.stargate.migration;

import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sgrewritten.stargate.StargateAPIMock;
import org.sgrewritten.stargate.StargateExtension;
import org.sgrewritten.stargate.api.network.Network;
import org.sgrewritten.stargate.api.network.portal.flag.StargateFlag;
import org.sgrewritten.stargate.database.SQLDatabase;
import org.sgrewritten.stargate.database.SQLiteDatabase;
import org.sgrewritten.stargate.database.property.PropertiesDatabaseMock;
import org.sgrewritten.stargate.network.NetworkType;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.network.portal.portaldata.PortalData;
import org.sgrewritten.stargate.property.StargateConstant;
import org.sgrewritten.stargate.thread.task.StargateQueuedAsyncTask;
import org.sgrewritten.stargate.util.LegacyPortalStorageLoader;
import org.sgrewritten.stargate.util.database.PortalStorageHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class LegacyNetworkMigrationTest {
    @MockBukkitInject private ServerMock server;
    @TempDir private Path temporary;

    private String row(String name, String network, int x) {
        return name + ":" + x + ",10,0:" + x + ",10,3:-1:0:0:" + x +
                ",12,0:nether.gate::" + network + ":00000000-0000-0000-0000-000000000001";
    }

    @ParameterizedTest
    @ValueSource(strings = {"<@default@>", "<@DEFAULT@>", " <@default@> "})
    void customNetworkCannotOccupyInternalDefaultId(String legacyName) throws Exception {
        World world = server.addSimpleWorld("world");
        PortalData data = PortalStorageHelper.loadPortalData(row("gate", legacyName, 0).split(":"), world, "central");
        assertEquals(":<@default@>", data.networkName());
        assertTrue(data.flags().contains(StargateFlag.CUSTOM_NETWORK));
        assertFalse(data.flags().contains(StargateFlag.DEFAULT_NETWORK));
    }

    @Test
    void explicitlyConfiguredDefaultNameIsNotEscaped() {
        World world = server.addSimpleWorld("world");
        PortalData data = PortalStorageHelper.loadPortalData(row("gate", "<@default@>", 0).split(":"), world, "<@default@>");
        assertEquals(StargateConstant.DEFAULT_NETWORK_ID, data.networkName());
        assertTrue(data.flags().contains(StargateFlag.DEFAULT_NETWORK));
    }

    @Test
    void ordinaryCustomNetworkIsUnchanged() {
        World world = server.addSimpleWorld("world");
        PortalData data = PortalStorageHelper.loadPortalData(row("gate", "<@default@>1", 0).split(":"), world, "central");
        assertEquals("<@default@>1", data.networkName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void importsCustomAndDefaultNetworksWithoutMergingSameNamedPortals(boolean customFirst) throws Exception {
        server.addSimpleWorld("world");
        SQLiteDatabase database = new SQLiteDatabase(temporary.resolve("stargate.db").toFile());
        SQLDatabase storage = new SQLDatabase(database, false, false, new PropertiesDatabaseMock());
        StargateAPIMock api = new StargateAPIMock(storage);
        String custom = row("same", "<@default@>", 0);
        String normal = row("same", "central", 20);
        Files.write(temporary.resolve("world.db"), customFirst ? List.of(custom, normal) : List.of(normal, custom));
        assertEquals(2, LegacyPortalStorageLoader.loadWorld(temporary.resolve("world.db").toFile(), "central", api)
                .orElseThrow().size());
        StargateQueuedAsyncTask.waitForEmptyQueue();
        Network defaultNetwork = api.getNetworkManager().selectNetwork(StargateConstant.DEFAULT_NETWORK_ID,
                NetworkType.DEFAULT, StorageType.LOCAL);
        Network customNetwork = api.getRegistry().getNetwork(":<@default@>", StorageType.LOCAL);
        assertNotNull(customNetwork);
        assertNotNull(customNetwork.getPortal("same"));
        assertNotNull(defaultNetwork.getPortal("same"));
        assertEquals(NetworkType.CUSTOM, customNetwork.getType());
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT network FROM Portal WHERE name = 'same' ORDER BY network")) {
            List<String> networks = new ArrayList<>();
            while (rows.next()) networks.add(rows.getString(1));
            assertEquals(List.of(":<@default@>", "<@default@>"), networks);
        }
    }
}
