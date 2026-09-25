package org.sgrewritten.stargate;

import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import be.seeseemelk.mockbukkit.scheduler.BukkitSchedulerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sgrewritten.stargate.api.config.ConfigurationOption;
import org.sgrewritten.stargate.api.gate.GateStructureType;
import org.sgrewritten.stargate.api.gate.ImplicitGateBuilder;
import org.sgrewritten.stargate.api.network.Network;
import org.sgrewritten.stargate.api.network.PortalBuilder;
import org.sgrewritten.stargate.api.network.portal.PortalPosition;
import org.sgrewritten.stargate.api.network.portal.PositionType;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.api.network.portal.flag.StargateFlag;
import org.sgrewritten.stargate.config.ConfigurationHelper;
import org.sgrewritten.stargate.database.TestCredential;
import org.sgrewritten.stargate.database.TestCredentialsManager;
import org.sgrewritten.stargate.exception.GateConflictException;
import org.sgrewritten.stargate.exception.InvalidStructureException;
import org.sgrewritten.stargate.exception.NoFormatFoundException;
import org.sgrewritten.stargate.exception.TranslatableException;
import org.sgrewritten.stargate.network.NetworkType;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.network.portal.PortalBlockGenerator;
import org.sgrewritten.stargate.thread.task.StargateGlobalTask;
import org.sgrewritten.stargate.thread.task.StargateQueuedAsyncTask;
import org.sgrewritten.stargate.util.ButtonHelper;
import org.sgrewritten.stargate.util.StargateTestHelper;
import org.sgrewritten.stargate.util.database.DatabaseHelper;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(StargateExtension.class)
class StargateTest {

    @StargateInject
    private Stargate plugin;
    @MockBukkitInject
    private ServerMock server;
    private RealPortal portal;
    private BukkitSchedulerMock scheduler;
    private RealPortal bungeePortal;

    private static final String PORTAL2 = "name2";
    private static final String PORTAL1 = "name1";
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setup() throws TranslatableException, NoFormatFoundException, GateConflictException, InvalidStructureException {
        scheduler = server.getScheduler();
        this.world = server.addSimpleWorld("world");
        this.player = server.addPlayer();

        Block signBlock1 = PortalBlockGenerator.generatePortal(new Location(world, 0, 10, 0));

        Network network = plugin.getNetworkManager().createNetwork("network", NetworkType.CUSTOM, StorageType.LOCAL, false);
        portal = new PortalBuilder(plugin, player, PORTAL1).setGateBuilder(new ImplicitGateBuilder(signBlock1.getLocation(), plugin.getRegistry())).setNetwork(network).build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyProxyArrivalFindsGateBeforeOrAfterPlayerJoin(boolean messageFirst) throws Exception {
        plugin.setConfigurationOptionValue(ConfigurationOption.USING_BUNGEE, true);
        createBungeePortal();
        StargateTestHelper.runAllTasks();
        Network network = plugin.getRegistry().getNetwork(ConfigurationHelper.getString(ConfigurationOption.LEGACY_BUNGEE_NETWORK), StorageType.LOCAL);
        RealPortal target = (RealPortal) network.getPortal(PORTAL2);
        // Provide an actual landing platform outside the elevated test gate.
        Location landing = target.getExit();
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                landing.clone().add(x, -1, z).getBlock().setType(Material.STONE);
            }
        }
        PlayerMock arriving = new PlayerMock(server, "incoming");
        arriving.setLocation(new Location(world, 1000, 10, 1000));
        java.io.ByteArrayOutputStream packet = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(packet);
        out.writeUTF(org.sgrewritten.stargate.property.PluginChannel.LEGACY_BUNGEE.getChannel());
        out.writeUTF("incoming#@#" + PORTAL2);
        var listener = new org.sgrewritten.stargate.listener.StargateBungeePluginMessageListener(plugin.getBungeeManager());
        if (!messageFirst) server.addPlayer(arriving);
        listener.onPluginMessageReceived(org.sgrewritten.stargate.property.PluginChannel.BUNGEE.getChannel(), player, packet.toByteArray());
        if (messageFirst) {
            server.addPlayer(arriving);
            server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerJoinEvent(arriving, (String) null));
        }
        StargateTestHelper.runAllTasks();
        Assertions.assertTrue(arriving.getLocation().distanceSquared(target.getExit()) < 100,
                "A valid forwarded U request must arrive at the reloaded gate, not the previous login location");
        Assertions.assertNull(plugin.getBungeeManager().pullFromQueue("incoming"));
    }

    @Test
    void reloadWaitsForAnAcceptedSlowDatabaseWrite() throws Exception {
        StargateTestHelper.runAllTasks();
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean saved = new java.util.concurrent.atomic.AtomicBoolean();
        new StargateQueuedAsyncTask() {
            @Override public void run() {
                writing.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Write was never released");
                    saved.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }.runNow();
        Assertions.assertTrue(writing.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var releaser = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            releaser.schedule(release::countDown, 300, java.util.concurrent.TimeUnit.MILLISECONDS);
            plugin.reload();
            Assertions.assertTrue(saved.get(), "Reload returned before an accepted database write completed");
        } finally {
            release.countDown();
            releaser.shutdownNow();
        }
        StargateTestHelper.runAllTasks();
        Assertions.assertNotNull(plugin.getRegistry().getNetwork("network", StorageType.LOCAL).getPortal(PORTAL1));
    }

    @Test
    void ownerAndNetworkChangesSurvivePluginReload() throws Exception {
        StargateTestHelper.runAllTasks();
        UUID owner = UUID.randomUUID();
        Network target = plugin.getNetworkManager().createNetwork("moved", NetworkType.CUSTOM, StorageType.LOCAL, false);
        portal.setOwner(owner);
        portal.setNetwork(target);
        plugin.reload();
        StargateTestHelper.runAllTasks();
        RealPortal loaded = (RealPortal) plugin.getRegistry().getNetwork("moved", StorageType.LOCAL).getPortal(PORTAL1);
        assertNotNull(loaded);
        Assertions.assertEquals(owner, loaded.getOwnerUUID());
        Network old = plugin.getRegistry().getNetwork("network", StorageType.LOCAL);
        Assertions.assertTrue(old == null || old.getPortal(PORTAL1) == null);
        Assertions.assertFalse(loaded.getGate().getPortalPositions().isEmpty());
    }

    @Test
    void disableCancelsWorldTasksAndDrainsAcceptedWrites() {
        AtomicInteger writes = new AtomicInteger();
        StargateGlobalTask delayed =
                new StargateGlobalTask() {
                    @Override public void run() { Assertions.fail("Disabled world task executed"); }
                };
        delayed.runDelayed(100);
        new StargateQueuedAsyncTask() {
            @Override public void run() { writes.incrementAndGet(); }
        }.runNow();
        server.getPluginManager().disablePlugin(plugin);
        Assertions.assertFalse(plugin.isEnabled());
        Assertions.assertEquals(1, writes.get());
        delayed.runNow();
        scheduler.performTicks(101);
    }

    @Test
    void loadingPortalsClearsIrisLeftOpenByPreviousShutdown() {
        StargateTestHelper.runAllTasks();
        Block iris = portal.getGate().getLocations(GateStructureType.IRIS)
                .getFirst().getLocation().getBlock();
        iris.setType(portal.getGate().getFormat().getIrisMaterial(true));
        Assertions.assertFalse(portal.isOpen());
        plugin.reload();
        StargateTestHelper.runAllTasks();
        Assertions.assertEquals(portal.getGate().getFormat().getIrisMaterial(false), iris.getType());
    }

    @Test
    void reloadRestoresAndPersistsMissingButton() throws Exception {
        StargateTestHelper.runAllTasks();
        // The fixture uses the same stored shape as an E gate after removing its
        // add-on: a normal non-always-on portal with a sign and no button record.
        Assertions.assertFalse(portal.hasFlag(StargateFlag.ALWAYS_ON));
        Assertions.assertTrue(portal.getGate().getPortalPositions().stream()
                .noneMatch(position -> position.getPositionType() == PositionType.BUTTON));
        for (int reload = 0; reload < 2; reload++) {
            plugin.reload();
            StargateTestHelper.runAllTasks();
            StargateQueuedAsyncTask.waitForEmptyQueue();
            RealPortal loaded = (RealPortal) plugin.getRegistry().getNetwork("network", StorageType.LOCAL).getPortal(PORTAL1);
            assertNotNull(loaded);
            Assertions.assertEquals(1, loaded.getGate().getPortalPositions().stream()
                    .filter(position -> position.getPositionType() == PositionType.BUTTON).count());
            PortalPosition button = loaded.getGate().getPortalPositions().stream()
                    .filter(position -> position.getPositionType() == PositionType.BUTTON).findFirst().orElseThrow();
            Block buttonBlock = loaded.getGate().getLocation(button.getRelativePositionLocation()).getBlock();
            Assertions.assertTrue(ButtonHelper.isButton(buttonBlock.getType()));
            Assertions.assertSame(loaded, plugin.getRegistry().getPortalPosition(buttonBlock.getLocation()).getPortal());
            String table = DatabaseHelper.getTableNameConfiguration(false).getPortalPositionTableName();
            try (var connection = DatabaseHelper.loadDatabase(plugin).getConnection();
                 var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE portalName = ? AND networkName = ?")) {
                query.setString(1, PORTAL1);
                query.setString(2, "network");
                try (var rows = query.executeQuery()) {
                    Assertions.assertTrue(rows.next());
                    Assertions.assertEquals(2, rows.getInt(1), "One sign and one button must be stored, without duplicates");
                }
            }
        }
    }

    @Test
    void unsafeControlRepairKeepsStoredPortalForLaterRecovery() throws Exception {
        StargateTestHelper.runAllTasks();
        var signVector = portal.getGate().getPortalPositions().getFirst().getRelativePositionLocation();
        var buttonVector = portal.getGate().getFormat().getControlBlocks().stream()
                .filter(vector -> !vector.equals(signVector)).findFirst().orElseThrow();
        Block occupied = portal.getGate().getLocation(buttonVector).getBlock();
        occupied.setType(Material.DIAMOND_BLOCK);
        plugin.reload();
        StargateTestHelper.runAllTasks();
        Assertions.assertNull(plugin.getRegistry().getNetwork("network", StorageType.LOCAL).getPortal(PORTAL1));
        Assertions.assertEquals(Material.DIAMOND_BLOCK, occupied.getType());
        // Removing the obstruction is enough to recover the same stored portal.
        occupied.setType(Material.AIR);
        plugin.reload();
        StargateTestHelper.runAllTasks();
        StargateQueuedAsyncTask.waitForEmptyQueue();
        assertNotNull(plugin.getRegistry().getNetwork("network", StorageType.LOCAL).getPortal(PORTAL1));
        Assertions.assertTrue(ButtonHelper.isButton(occupied.getType()));
    }

    @Test
    void getEconomyManager() {
        assertNotNull(plugin.getEconomyManager());
    }

    @Test
    void getAbsoluteDataFolder() {
        assertNotNull(plugin.getAbsoluteDataFolder());
    }

    @Test
    void setGetConfigurationOptionValue() {
        plugin.setConfigurationOptionValue(ConfigurationOption.UPKEEP_COST, 2);
        Assertions.assertEquals(2, plugin.getConfigurationOptionValue(ConfigurationOption.UPKEEP_COST));
        plugin.reload();
        Assertions.assertEquals(2, plugin.getConfigurationOptionValue(ConfigurationOption.UPKEEP_COST));
    }

    @Test
    void reload() {
        Stargate.log(Level.FINEST, "reloading");
        plugin.reload();
        Assertions.assertTrue(plugin.isEnabled());
    }

    @Test
    void reload_StupidDefaultNetworkNameUUID() {
        Stargate.setLogLevel(Level.OFF);
        plugin.setConfigurationOptionValue(ConfigurationOption.DEFAULT_NETWORK, UUID.randomUUID().toString());
        plugin.reload();
        Stargate.setLogLevel(Level.INFO);
        Assertions.assertFalse(plugin.isEnabled());
    }

    @ParameterizedTest
    @ValueSource(strings = {"thisNameIsWayTooLong", "", "Test1\nTest2"})
    void reload_StupidDefaultNetworkName(String name) {
        Stargate.setLogLevel(Level.OFF);
        plugin.setConfigurationOptionValue(ConfigurationOption.DEFAULT_NETWORK, name);
        plugin.reload();
        Stargate.setLogLevel(Level.INFO);
        Assertions.assertFalse(plugin.isEnabled());
    }

    @Test
    void reloadInterServer() {
        setInterServerEnabled();
        Assertions.assertTrue(plugin.isEnabled());
    }

    @Test
    void reloadConfig() {
        Assertions.assertDoesNotThrow(() -> plugin.reloadConfig());
    }

    @Test
    void restart() throws TranslatableException, InvalidStructureException, GateConflictException, NoFormatFoundException {
        plugin.setConfigurationOptionValue(ConfigurationOption.USING_BUNGEE, true);
        createBungeePortal();
        server.getScheduler().performOneTick();
        server.getPluginManager().disablePlugin(plugin);
        Assertions.assertNull(Stargate.getInstance());
        server.getScheduler().waitAsyncTasksFinished();
        server.getPluginManager().enablePlugin(plugin);
        server.getScheduler().performOneTick();
        Assertions.assertTrue(plugin.isEnabled());
        Network network = plugin.getRegistry().getNetwork(ConfigurationHelper.getString(ConfigurationOption.LEGACY_BUNGEE_NETWORK), StorageType.LOCAL);
        assertNotNull(network);
        assertNotNull(network.getPortal(PORTAL2));
    }

    @Test
    void restartInterServer() {
        setInterServerEnabled();
        server.getPluginManager().disablePlugin(plugin);
        Assertions.assertNull(Stargate.getInstance());
        server.getPluginManager().enablePlugin(plugin);
        Assertions.assertTrue(plugin.isEnabled());
        assertNotNull(Stargate.getServerUUID());
    }

    @Test
    void getMaterialResolver() {
        Assertions.assertNotNull(plugin.getMaterialHandlerResolver());
    }

    @Test
    void getNetworkManager_notNull() {
        Assertions.assertNotNull(plugin.getNetworkManager());
    }

    private void setInterServerEnabled() {
        plugin.setConfigurationOptionValue(ConfigurationOption.USING_BUNGEE, true);
        plugin.setConfigurationOptionValue(ConfigurationOption.USING_REMOTE_DATABASE, true);
        TestCredentialsManager credentialsManager = new TestCredentialsManager("mysql_credentials.secret");
        plugin.setConfigurationOptionValue(ConfigurationOption.BUNGEE_ADDRESS, credentialsManager.getCredentialString(TestCredential.MYSQL_DB_ADDRESS, "localhost"));
        plugin.setConfigurationOptionValue(ConfigurationOption.BUNGEE_USERNAME, credentialsManager.getCredentialString(TestCredential.MYSQL_DB_USER, "root"));
        plugin.setConfigurationOptionValue(ConfigurationOption.BUNGEE_PASSWORD, credentialsManager.getCredentialString(TestCredential.MYSQL_DB_PASSWORD, "root"));
        plugin.setConfigurationOptionValue(ConfigurationOption.BUNGEE_PORT, credentialsManager.getCredentialInt(TestCredential.MYSQL_DB_PORT, 3306));
        plugin.setConfigurationOptionValue(ConfigurationOption.BUNGEE_USE_SSL, true);
        plugin.setConfigurationOptionValue(ConfigurationOption.BUNGEE_DATABASE, credentialsManager.getCredentialString(TestCredential.MYSQL_DB_NAME, "Stargate"));
    }

    private void createBungeePortal() throws TranslatableException, InvalidStructureException, GateConflictException, NoFormatFoundException {
        Block signBlock2 = PortalBlockGenerator.generatePortal(new Location(world, 0, 20, 0));
        Set<StargateFlag> flags = new HashSet<>();
        flags.add(StargateFlag.LEGACY_INTERSERVER);
        PortalBuilder portalBuilder = new PortalBuilder(plugin, player, PORTAL2).setGateBuilder(new ImplicitGateBuilder(signBlock2.getLocation(), plugin.getRegistry())).setFlags(flags);
        bungeePortal = portalBuilder.setDestinationServerName("server").setDestination("destination").build();
        plugin.reload();
    }
}
