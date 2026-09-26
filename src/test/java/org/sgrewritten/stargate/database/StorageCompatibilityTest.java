package org.sgrewritten.stargate.database;

import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.StargateAPIMock;
import org.sgrewritten.stargate.StargateExtension;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.config.TableNameConfiguration;
import org.sgrewritten.stargate.database.property.PropertiesDatabaseMock;
import org.sgrewritten.stargate.network.NetworkType;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.network.portal.TestPortalBuilder;
import org.sgrewritten.stargate.thread.task.StargateQueuedAsyncTask;
import org.sgrewritten.stargate.util.database.DatabaseHelper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class StorageCompatibilityTest {
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
        DatabaseHelper.createTables(database,
                new SQLQueryGenerator(new TableNameConfiguration("", ""), DatabaseDriver.SQLITE), true);
        api = new StargateAPIMock(storage);
    }

    private RealPortal portal(StorageType type) throws Exception {
        var network = api.getNetworkManager().selectNetwork("original", NetworkType.CUSTOM, type);
        return new TestPortalBuilder(api.getRegistry(), world)
                .setName("gate").setNetwork(network).setStorageType(type).build();
    }

    private Map<String, List<String>> snapshot() throws Exception {
        Map<String, List<String>> result = new TreeMap<>();
        try (var connection = database.getConnection(); var schema = connection.createStatement();
             var tables = schema.executeQuery("SELECT name, sql FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            while (tables.next()) {
                String table = tables.getString(1);
                List<String> rows = new ArrayList<>();
                try (var query = connection.createStatement();
                     var data = query.executeQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"")) {
                    while (data.next()) {
                        List<String> row = new ArrayList<>();
                        for (int column = 1; column <= data.getMetaData().getColumnCount(); column++) {
                            row.add(data.getString(column));
                        }
                        rows.add(row.toString());
                    }
                }
                rows.sort(String::compareTo);
                result.put(tables.getString(2), rows);
            }
        }
        return result;
    }

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void savedOwnerAndNetworkSettersLeaveStoredSchemaAndRowsUnchanged(StorageType type) throws Exception {
        RealPortal portal = portal(type);
        storage.savePortalToStorage(portal);
        var before = snapshot();
        UUID owner = UUID.randomUUID();
        var target = api.getNetworkManager().selectNetwork("changed", NetworkType.CUSTOM, type);

        portal.setOwner(owner);
        portal.setNetwork(target);
        StargateQueuedAsyncTask.waitForEmptyQueue();

        assertEquals(owner, portal.getOwnerUUID());
        assertSame(target, portal.getNetwork());
        assertEquals(before, snapshot(), "Upstream setters must not implicitly persist edits");
    }

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void creationUsesExistingQueueForBothStorageTypes(StorageType type) throws Exception {
        RealPortal portal = portal(type);
        var before = snapshot();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        new StargateQueuedAsyncTask() {
            @Override public void run() {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) fail("Queue was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }.runNow();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            api.getNetworkManager().savePortal(portal, portal.getNetwork());
            assertSame(portal, portal.getNetwork().getPortal(portal.getName()));
            assertEquals(before, snapshot(), "Creation must not synchronously reserve shared storage");
        } finally {
            release.countDown();
            StargateQueuedAsyncTask.waitForEmptyQueue();
        }
        assertNotEquals(before, snapshot(), "Normal queued portal saving must still work");
    }
}
