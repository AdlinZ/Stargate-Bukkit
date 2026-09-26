package org.sgrewritten.stargate.manager;

import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.StargateAPIMock;
import org.sgrewritten.stargate.StargateExtension;
import org.sgrewritten.stargate.api.StargateAPI;
import org.sgrewritten.stargate.api.config.ConfigurationOption;
import org.sgrewritten.stargate.api.network.Network;
import org.sgrewritten.stargate.api.network.RegistryAPI;
import org.sgrewritten.stargate.api.network.portal.Portal;
import org.sgrewritten.stargate.api.network.portal.flag.StargateFlag;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.config.ConfigurationHelper;
import org.sgrewritten.stargate.exception.GateConflictException;
import org.sgrewritten.stargate.exception.InvalidStructureException;
import org.sgrewritten.stargate.exception.NoFormatFoundException;
import org.sgrewritten.stargate.exception.TranslatableException;
import org.sgrewritten.stargate.network.NetworkType;
import org.sgrewritten.stargate.network.StargateNetwork;
import org.sgrewritten.stargate.network.StargateNetworkManager;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.network.portal.TestPortalBuilder;
import org.sgrewritten.stargate.property.StargateProtocolRequestType;
import org.sgrewritten.stargate.util.BungeeHelper;
import org.sgrewritten.stargate.util.LanguageManagerMock;

import java.util.Set;

@ExtendWith(StargateExtension.class)
class StargateBungeeManagerTest {

    @MockBukkitInject
    private ServerMock server;
    private RegistryAPI registry;
    private WorldMock world;
    private StargateBungeeManager bungeeManager;
    private RealPortal realPortal;
    private RealPortal bungeePortal;

    private static final String SERVER = "server";
    private static final String NETWORK = "network1";
    private static final String NETWORK2 = "network2";
    private static final String PORTAL = "portal";
    private static final String PORTAL2 = "portal2";
    private static final String PLAYER = "player";
    private static final String REGISTERED_PORTAL = "rPortal";
    private StargateNetworkManager networkManager;
    private StargateAPI stargateAPI;
    private int count = 0;
    private TestPortalBuilder testPortalBuilder;

    @BeforeEach
    void setUp() throws TranslatableException, InvalidStructureException, GateConflictException, NoFormatFoundException {
        Stargate.setServerName(SERVER);
        stargateAPI = new StargateAPIMock();
        registry = stargateAPI.getRegistry();
        this.networkManager = (StargateNetworkManager) stargateAPI.getNetworkManager();
        world = server.addSimpleWorld("world");
        Network network2 = networkManager.createNetwork(NETWORK2, NetworkType.CUSTOM, StorageType.INTER_SERVER, false);

        this.testPortalBuilder = new TestPortalBuilder(registry, world);
        testPortalBuilder.setNetwork(network2).setStorageType(StorageType.INTER_SERVER).setName(REGISTERED_PORTAL);
        realPortal = testPortalBuilder.build();
        network2.addPortal(realPortal);

        Network bungeeNetwork = networkManager.createNetwork(ConfigurationHelper.getString(ConfigurationOption.LEGACY_BUNGEE_NETWORK), NetworkType.CUSTOM, StorageType.LOCAL,
                false);
        testPortalBuilder.setNetwork(bungeeNetwork).setStorageType(StorageType.LOCAL).setFlags(Set.of(StargateFlag.LEGACY_INTERSERVER));
        bungeePortal = testPortalBuilder.build();
        bungeeNetwork.addPortal(bungeePortal);

        bungeeManager = new StargateBungeeManager(registry, new LanguageManagerMock(), networkManager);
    }

    @Test
    void updateNetwork() throws TranslatableException, InvalidStructureException, GateConflictException, NoFormatFoundException {
        //A network not assigned to a registry
        Network network = new StargateNetwork(NETWORK, NetworkType.CUSTOM, StorageType.INTER_SERVER);
        testPortalBuilder.setNetwork(network).setName(PORTAL).setStorageType(StorageType.INTER_SERVER);
        RealPortal portal = testPortalBuilder.build();
        testPortalBuilder.setName(PORTAL2);
        RealPortal portal2 = testPortalBuilder.build();

        bungeeManager.updateNetwork(BungeeHelper.generateJsonMessage(portal, StargateProtocolRequestType.PORTAL_ADD));
        bungeeManager.updateNetwork(BungeeHelper.generateJsonMessage(portal2, StargateProtocolRequestType.PORTAL_ADD));
        Network network1 = registry.getNetwork(NETWORK, StorageType.INTER_SERVER);
        Assertions.assertNotNull(network1);
        Assertions.assertNotNull(network1.getPortal(PORTAL));
        Assertions.assertNotNull(network1.getPortal(PORTAL2));
    }

    @Test
    void updateNetwork_renamePortal() throws TranslatableException, InvalidStructureException, GateConflictException, NoFormatFoundException {
        //A network not assigned to a registry
        Network network = new StargateNetwork(NETWORK, NetworkType.CUSTOM, StorageType.INTER_SERVER);
        testPortalBuilder.setNetwork(network).setName(PORTAL).setStorageType(StorageType.INTER_SERVER);
        RealPortal portal = testPortalBuilder.build();
        String newName = "new_portal";
        bungeeManager.updateNetwork(BungeeHelper.generateJsonMessage(portal, StargateProtocolRequestType.PORTAL_ADD));
        bungeeManager.updateNetwork(BungeeHelper.generateRenamePortalMessage(newName, portal.getName(), network));
        Network network1 = registry.getNetwork(NETWORK, StorageType.INTER_SERVER);
        Assertions.assertNotNull(network1);
        Assertions.assertNull(network1.getPortal(PORTAL));
        Assertions.assertNotNull(network1.getPortal(newName));
    }

    @Test
    void updateNetwork_renameNetwork() throws TranslatableException, InvalidStructureException, GateConflictException, NoFormatFoundException {
        //A network not assigned to a registry
        Network network = new StargateNetwork(NETWORK, NetworkType.CUSTOM, StorageType.INTER_SERVER);
        testPortalBuilder.setNetwork(network).setName(PORTAL).setStorageType(StorageType.INTER_SERVER);
        RealPortal portal = testPortalBuilder.build();
        String newName = "new_network";
        bungeeManager.updateNetwork(BungeeHelper.generateJsonMessage(portal, StargateProtocolRequestType.PORTAL_ADD));
        Network preRenameNetwork = registry.getNetwork(NETWORK, StorageType.INTER_SERVER);
        bungeeManager.updateNetwork(BungeeHelper.generateRenameNetworkMessage(newName, NETWORK));
        Network renamedNetwork = registry.getNetwork(newName, StorageType.INTER_SERVER);
        Assertions.assertEquals(preRenameNetwork, renamedNetwork);
        Assertions.assertNotNull(renamedNetwork);
        Assertions.assertNull(registry.getNetwork(NETWORK, StorageType.INTER_SERVER));
        Assertions.assertNotNull(renamedNetwork.getPortal(PORTAL));
    }


    @Test
    void playerConnectOnline() {
        PlayerMock player = server.addPlayer(PLAYER);

        bungeeManager.playerConnect(BungeeHelper.generateTeleportJsonMessage(PLAYER, realPortal));
        Component componentMessage = player.nextComponentMessage();
        Assertions.assertFalse(componentMessage != null && componentMessage.toString().contains("[ERROR]"), "An error message was sent to the player '" + componentMessage + "'");
    }

    @Test
    void playerConnectOffline() {
        bungeeManager.playerConnect(BungeeHelper.generateTeleportJsonMessage(PLAYER, realPortal));
        Portal pulledPortal = bungeeManager.pullFromQueue(PLAYER);
        Assertions.assertEquals(realPortal.getName(), pulledPortal.getName());
        Assertions.assertEquals(realPortal.getNetwork().getId(), pulledPortal.getNetwork().getId());
    }

    @Test
    void legacyPlayerConnectOnline() {
        PlayerMock player = server.addPlayer(PLAYER);

        bungeeManager.legacyPlayerConnect(BungeeHelper.generateLegacyTeleportMessage(PLAYER, bungeePortal));
        Component componentMessage = player.nextComponentMessage();
        Assertions.assertFalse(componentMessage != null && componentMessage.toString().contains("[ERROR]"), "An error message was sent to the player '" + componentMessage + "'");
    }

    @Test
    void legacyPlayerConnectOffline() {
        bungeeManager.legacyPlayerConnect(BungeeHelper.generateLegacyTeleportMessage(PLAYER, bungeePortal));
        Portal pulledPortal = bungeeManager.pullFromQueue(PLAYER);
        Assertions.assertEquals(bungeePortal, pulledPortal);
    }


    private String announcement(String name, String serverName, StargateProtocolRequestType type) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("REQUEST_TYPE", type.name());
        json.addProperty("NETWORK", NETWORK2);
        json.addProperty("PORTAL", name);
        json.addProperty("SERVER", serverName);
        json.addProperty("PORTAL_FLAG", realPortal.getAllFlagsString());
        json.addProperty("OWNER", realPortal.getOwnerUUID().toString());
        return json.toString();
    }

    @Test
    void replayedAddKeepsExistingRemoteInstance() {
        String message = announcement(PORTAL, "remote", StargateProtocolRequestType.PORTAL_ADD);
        bungeeManager.updateNetwork(message);
        Portal first = realPortal.getNetwork().getPortal(PORTAL);
        bungeeManager.updateNetwork(message);
        Assertions.assertSame(first, realPortal.getNetwork().getPortal(PORTAL));
    }

    @Test
    void remoteDeleteCannotRemoveLocalOrOtherServerPortal() {
        bungeeManager.updateNetwork(announcement(REGISTERED_PORTAL, "remote", StargateProtocolRequestType.PORTAL_REMOVE));
        Assertions.assertSame(realPortal, realPortal.getNetwork().getPortal(REGISTERED_PORTAL));
        bungeeManager.updateNetwork(announcement(PORTAL, "remote", StargateProtocolRequestType.PORTAL_ADD));
        Portal first = realPortal.getNetwork().getPortal(PORTAL);
        bungeeManager.updateNetwork(announcement(PORTAL, "other", StargateProtocolRequestType.PORTAL_REMOVE));
        Assertions.assertSame(first, realPortal.getNetwork().getPortal(PORTAL));
        bungeeManager.updateNetwork(announcement(PORTAL, "remote", StargateProtocolRequestType.PORTAL_REMOVE));
        bungeeManager.updateNetwork(announcement(PORTAL, "remote", StargateProtocolRequestType.PORTAL_REMOVE));
        Assertions.assertNull(realPortal.getNetwork().getPortal(PORTAL));
    }

    @Test
    void ownAnnouncementsCannotReplaceRealPortal() {
        bungeeManager.updateNetwork(announcement(REGISTERED_PORTAL, SERVER, StargateProtocolRequestType.PORTAL_ADD));
        bungeeManager.updateNetwork(announcement(REGISTERED_PORTAL, SERVER, StargateProtocolRequestType.PORTAL_REMOVE));
        Assertions.assertSame(realPortal, realPortal.getNetwork().getPortal(REGISTERED_PORTAL));
    }

    @Test
    void conflictingRemoteAddDoesNotReplaceExistingPortal() {
        bungeeManager.updateNetwork(announcement(PORTAL, "remote", StargateProtocolRequestType.PORTAL_ADD));
        Portal first = realPortal.getNetwork().getPortal(PORTAL);
        bungeeManager.updateNetwork(announcement(PORTAL, "other", StargateProtocolRequestType.PORTAL_ADD));
        Assertions.assertSame(first, realPortal.getNetwork().getPortal(PORTAL));
    }

    @Test
    void ownerChangeRefreshesRemotePortal() {
        String message = announcement(PORTAL, "remote", StargateProtocolRequestType.PORTAL_ADD);
        bungeeManager.updateNetwork(message);
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
        java.util.UUID owner = java.util.UUID.randomUUID();
        json.addProperty("OWNER", owner.toString());
        bungeeManager.updateNetwork(json.toString());
        Assertions.assertEquals(owner, realPortal.getNetwork().getPortal(PORTAL).getOwnerUUID());
    }

    @Test
    void offlineRequestNeverQueuesVirtualPortal() {
        bungeeManager.updateNetwork(announcement(PORTAL, "remote", StargateProtocolRequestType.PORTAL_ADD));
        bungeeManager.playerConnect(BungeeHelper.generateTeleportJsonMessage(PLAYER, realPortal.getNetwork().getPortal(PORTAL)));
        Assertions.assertNull(bungeeManager.pullFromQueue(PLAYER));
    }

    @Test
    void queueExpiresAndIsConsumedOnlyOnce() {
        long[] now = {0};
        StargateBungeeManager manager = new StargateBungeeManager(registry, new LanguageManagerMock(), networkManager, () -> now[0]);
        manager.playerConnect(BungeeHelper.generateTeleportJsonMessage(PLAYER, realPortal));
        now[0] = 60_001;
        Assertions.assertNull(manager.pullFromQueue(PLAYER));
        manager.playerConnect(BungeeHelper.generateTeleportJsonMessage(PLAYER, realPortal));
        Assertions.assertSame(realPortal, manager.pullFromQueue(PLAYER));
        Assertions.assertNull(manager.pullFromQueue(PLAYER));
    }

    private RealPortal countTeleports(RealPortal original, int[] calls) throws Exception {
        RealPortal counting = (RealPortal) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RealPortal.class}, (proxy, method, args) -> {
                    if (method.getName().equals("teleportHere")) { calls[0]++; return null; }
                    return method.invoke(original, args);
                });
        original.getNetwork().removePortal(original);
        original.getNetwork().addPortal(counting);
        return counting;
    }

    @Test
    void duplicateTeleportIdRunsOnceButNewIdCanTravelAgain() throws Exception {
        int[] calls = {0};
        RealPortal portal = countTeleports(realPortal, calls);
        server.addPlayer(PLAYER);
        String request = BungeeHelper.generateTeleportJsonMessage(PLAYER, portal);
        bungeeManager.playerConnect(request);
        bungeeManager.playerConnect(request);
        org.sgrewritten.stargate.util.StargateTestHelper.runAllTasks();
        Assertions.assertEquals(1, calls[0]);
        bungeeManager.playerConnect(BungeeHelper.generateTeleportJsonMessage(PLAYER, portal));
        org.sgrewritten.stargate.util.StargateTestHelper.runAllTasks();
        Assertions.assertEquals(2, calls[0]);
    }

    @Test
    void replayAfterQueueConsumptionDoesNotTeleportAgain() throws Exception {
        int[] calls = {0};
        RealPortal portal = countTeleports(realPortal, calls);
        String request = BungeeHelper.generateTeleportJsonMessage(PLAYER, portal);
        bungeeManager.playerConnect(request);
        Assertions.assertSame(portal, bungeeManager.pullFromQueue(PLAYER));
        server.addPlayer(PLAYER);
        bungeeManager.playerConnect(request);
        org.sgrewritten.stargate.util.StargateTestHelper.runAllTasks();
        Assertions.assertEquals(0, calls[0]);
    }

    @Test
    void legacyDuplicatesExpireWithoutSuppressingLaterTrips() throws Exception {
        int[] calls = {0};
        RealPortal portal = countTeleports(bungeePortal, calls);
        long[] now = {0};
        StargateBungeeManager manager = new StargateBungeeManager(registry, new LanguageManagerMock(), networkManager, () -> now[0]);
        server.addPlayer(PLAYER);
        String message = BungeeHelper.generateLegacyTeleportMessage(PLAYER, portal);
        manager.legacyPlayerConnect(message);
        manager.legacyPlayerConnect(message);
        org.sgrewritten.stargate.util.StargateTestHelper.runAllTasks();
        Assertions.assertEquals(1, calls[0]);
        now[0] = 1001;
        manager.legacyPlayerConnect(message);
        org.sgrewritten.stargate.util.StargateTestHelper.runAllTasks();
        Assertions.assertEquals(2, calls[0]);
    }

    @Test
    void malformedLegacyMessageIsIgnored() {
        Assertions.assertDoesNotThrow(() -> bungeeManager.legacyPlayerConnect("missing separator"));
        Assertions.assertDoesNotThrow(() -> bungeeManager.legacyPlayerConnect("name#@#"));
    }

    @Test
    void portalRenameReplayKeepsDestinationAndCannotOverwriteAnotherGate() {
        bungeeManager.updateNetwork(announcement(PORTAL, "remote", StargateProtocolRequestType.PORTAL_ADD));
        Network network = realPortal.getNetwork();
        Portal original = network.getPortal(PORTAL);
        String rename = BungeeHelper.generateRenamePortalMessage("renamed", "PoRtAl", network);
        bungeeManager.updateNetwork(rename);
        bungeeManager.updateNetwork(rename);
        Assertions.assertSame(original, network.getPortal("renamed"));
        Assertions.assertNull(network.getPortal(PORTAL));
        bungeeManager.updateNetwork(BungeeHelper.generateRenamePortalMessage(REGISTERED_PORTAL, "renamed", network));
        Assertions.assertSame(realPortal, network.getPortal(REGISTERED_PORTAL));
        Assertions.assertSame(original, network.getPortal("renamed"));
    }

    @Test
    void networkRenameReplayCannotOverwriteExistingNetwork() throws Exception {
        Network existing = networkManager.createNetwork("occupied", NetworkType.CUSTOM, StorageType.INTER_SERVER, false);
        Network original = realPortal.getNetwork();
        bungeeManager.updateNetwork(BungeeHelper.generateRenameNetworkMessage("occupied", NETWORK2));
        Assertions.assertSame(original, registry.getNetwork(NETWORK2, StorageType.INTER_SERVER));
        Assertions.assertSame(existing, registry.getNetwork("occupied", StorageType.INTER_SERVER));
        String rename = BungeeHelper.generateRenameNetworkMessage("renamed", NETWORK2);
        bungeeManager.updateNetwork(rename);
        bungeeManager.updateNetwork(rename);
        Assertions.assertSame(original, registry.getNetwork("renamed", StorageType.INTER_SERVER));
    }
}
