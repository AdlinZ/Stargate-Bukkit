package org.sgrewritten.stargate.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.api.config.ConfigurationOption;
import org.sgrewritten.stargate.api.formatting.LanguageManager;
import org.sgrewritten.stargate.api.formatting.TranslatableMessage;
import org.sgrewritten.stargate.api.manager.BungeeManager;
import org.sgrewritten.stargate.api.network.Network;
import org.sgrewritten.stargate.api.network.NetworkManager;
import org.sgrewritten.stargate.api.network.RegistryAPI;
import org.sgrewritten.stargate.api.network.portal.Portal;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.thread.task.StargateEntityTask;
import org.sgrewritten.stargate.api.network.portal.flag.PortalFlag;
import org.sgrewritten.stargate.config.ConfigurationHelper;
import org.sgrewritten.stargate.exception.UnimplementedFlagException;
import org.sgrewritten.stargate.exception.name.InvalidNameException;
import org.sgrewritten.stargate.exception.name.NameConflictException;
import org.sgrewritten.stargate.exception.name.NameLengthException;
import org.sgrewritten.stargate.network.StorageType;
import org.sgrewritten.stargate.network.portal.VirtualPortal;
import org.sgrewritten.stargate.property.StargateProtocolProperty;
import org.sgrewritten.stargate.property.StargateProtocolRequestType;
import org.sgrewritten.stargate.util.BungeeHelper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class StargateBungeeManager implements BungeeManager {

    private final RegistryAPI registry;
    private final @NotNull LanguageManager languageManager;
    private final Map<String, QueuedPortal> bungeeQueue = new LinkedHashMap<>();
    private final Map<String, Long> recentTeleports = new LinkedHashMap<>();
    private final LongSupplier clock;
    private static final long QUEUE_LIFETIME = 60_000;
    private static final int MAX_PENDING = 4096;
    private record QueuedPortal(Portal portal, long expiresAt) { }
    private final NetworkManager networkManager;

    /**
     * @param registry <p>A registry containing all information about portals</p>
     * @param languageManager <p>A manager able to provide localized messages</p>
     * @param networkManager <p>A network manager</p>
     */
    public StargateBungeeManager(@NotNull RegistryAPI registry, @NotNull LanguageManager languageManager, @NotNull NetworkManager networkManager) {
        this(registry, languageManager, networkManager, System::currentTimeMillis);
    }

    StargateBungeeManager(RegistryAPI registry, LanguageManager languageManager, NetworkManager networkManager,
                         LongSupplier clock) {
        this.clock = clock;
        this.registry = Objects.requireNonNull(registry);
        this.languageManager = Objects.requireNonNull(languageManager);
        this.networkManager = Objects.requireNonNull(networkManager);
    }

    @Override
    public void updateNetwork(String message) {
        Stargate.log(Level.FINEST, message);
        // Yes, a depricated method, needs to be there as spigot 1.16.5 does not support the new method
        JsonObject json = (JsonObject) new JsonParser().parse(message);

        String requestTypeString = json.get(StargateProtocolProperty.REQUEST_TYPE.toString()).getAsString();
        StargateProtocolRequestType requestType = StargateProtocolRequestType.valueOf(requestTypeString);
        switch (requestType) {
            case PORTAL_ADD, PORTAL_REMOVE -> portalAddOrRemove(json, requestType);
            case NETWORK_RENAME -> {
                String oldId = json.get(StargateProtocolProperty.NETWORK.toString()).getAsString();
                String newId = json.get(StargateProtocolProperty.NEW_NETWORK_NAME.toString()).getAsString();
                Network oldNetwork = registry.getNetwork(oldId, StorageType.INTER_SERVER);
                Network newNetwork = registry.getNetwork(newId, StorageType.INTER_SERVER);
                if (oldNetwork == null || oldNetwork == newNetwork) return;
                if (newNetwork != null) {
                    Stargate.log(Level.WARNING, "Ignoring network rename to an existing network: " + newId);
                    return;
                }
                try {
                    registry.renameNetwork(newId, oldId, StorageType.INTER_SERVER);
                } catch (InvalidNameException | UnimplementedFlagException | NameLengthException e) {
                    Stargate.log(e);
                }
            }
            case PORTAL_RENAME -> {
                String oldName = json.get(StargateProtocolProperty.PORTAL.toString()).getAsString();
                String newName = json.get(StargateProtocolProperty.NEW_PORTAL_NAME.toString()).getAsString();
                String networkId = json.get(StargateProtocolProperty.NETWORK.toString()).getAsString();
                Network network = registry.getNetwork(networkId, StorageType.INTER_SERVER);
                if (network == null) {
                    Stargate.log(Level.WARNING, "Could not rename cross server portal, as network did not exist");
                    return;
                }
                Portal oldPortal = network.getPortal(oldName);
                Portal newPortal = network.getPortal(newName);
                if (oldPortal == null || oldPortal == newPortal) return;
                if (newPortal != null) {
                    Stargate.log(Level.WARNING, "Ignoring portal rename to an existing portal: " + newName);
                    return;
                }
                try {
                    network.renamePortal(newName, oldName);
                } catch (InvalidNameException e) {
                    Stargate.log(e);
                }
            }
        }
    }

    private void portalAddOrRemove(JsonObject json, StargateProtocolRequestType requestType) {

        String portalName = json.get(StargateProtocolProperty.PORTAL.toString()).getAsString();
        String network = json.get(StargateProtocolProperty.NETWORK.toString()).getAsString();
        String server = json.get(StargateProtocolProperty.SERVER.toString()).getAsString();
        String flagString = json.get(StargateProtocolProperty.PORTAL_FLAG.toString()).getAsString();
        Set<PortalFlag> flags = PortalFlag.parseFlags(flagString);
        UUID ownerUUID = UUID.fromString(json.get(StargateProtocolProperty.OWNER.toString()).getAsString());

        Network targetNetwork = registry.getNetwork(network, StorageType.INTER_SERVER);
        if (targetNetwork == null && requestType == StargateProtocolRequestType.PORTAL_REMOVE) {
            return;
        }
        try {
            if (targetNetwork == null) {
                targetNetwork = networkManager.createNetwork(network, flags, false);
            }
            Portal existing = targetNetwork.getPortal(portalName);
            if (existing != null) {
                if (existing instanceof RealPortal && server.equals(Stargate.getServerName())) {
                    return; // Our own announcement must never replace/delete the real portal.
                }
                if (!(existing instanceof VirtualPortal remote) || !server.equals(remote.getServer())) {
                    Stargate.log(Level.WARNING, "Ignoring conflicting inter-server portal " + portalName
                            + " in " + network + " from server " + server);
                    return;
                }
                if (requestType == StargateProtocolRequestType.PORTAL_ADD
                        && ownerUUID.equals(existing.getOwnerUUID())
                        && flags.equals(PortalFlag.parseFlags(existing.getAllFlagsString()))) {
                    return;
                }
                targetNetwork.removePortal(existing);
            } else if (requestType == StargateProtocolRequestType.PORTAL_REMOVE) {
                return;
            }
            if (requestType == StargateProtocolRequestType.PORTAL_ADD) {
                targetNetwork.addPortal(new VirtualPortal(server, portalName, targetNetwork, flags, ownerUUID));
            }
            targetNetwork.updatePortals();
        } catch (NameConflictException | InvalidNameException | NameLengthException | UnimplementedFlagException e) {
            Stargate.log(e);
        }
    }

    @Override
    public void playerConnect(String message) {
        Stargate.log(Level.FINEST, message);

        // Yes, a depricated method, needs to be there as spigot 1.16.5 does not support the new method
        JsonObject json = (JsonObject) new JsonParser().parse(message);
        String playerName = json.get(StargateProtocolProperty.PLAYER.toString()).getAsString();
        String portalName = json.get(StargateProtocolProperty.PORTAL.toString()).getAsString();
        String networkName = json.get(StargateProtocolProperty.NETWORK.toString()).getAsString();

        String requestId = json.has(StargateProtocolProperty.REQUEST_ID.toString())
                ? json.get(StargateProtocolProperty.REQUEST_ID.toString()).getAsString() : null;
        if (duplicateTeleport(requestId == null ? "legacy:" + playerName + ":" + networkName + ":" + portalName
                : "id:" + requestId, requestId == null ? 1000 : QUEUE_LIFETIME)) {
            return;
        }
        Player player = Bukkit.getServer().getPlayerExact(playerName);
        if (player == null) {
            Stargate.log(Level.FINEST, "Player was null; adding to queue");
            addToQueue(playerName, portalName, networkName, StorageType.INTER_SERVER);
            return;
        }

        Stargate.log(Level.FINEST, "Player was not null; trying to teleport");
        Network network = registry.getNetwork(networkName, StorageType.INTER_SERVER);
        if (network == null) {
            player.sendMessage(languageManager.getErrorMessage(TranslatableMessage.BUNGEE_INVALID_NETWORK));
            return;
        }
        Portal destinationPortal = network.getPortal(portalName);
        if (destinationPortal == null) {
            player.sendMessage(languageManager.getErrorMessage(TranslatableMessage.BUNGEE_INVALID_GATE));
            return;
        }
        if (!(destinationPortal instanceof RealPortal) || destinationPortal.isDestroyed()) {
            Stargate.log(Level.WARNING, "The receiving portal for this bungee teleport message should not be a virtual portal, contact developers (do /sg for more info)");
            return;
        }
        teleportOnPlayerThread(player, destinationPortal);
    }

    @Override
    public void legacyPlayerConnect(String message) {
        String bungeeNetworkName = ConfigurationHelper.getString(ConfigurationOption.LEGACY_BUNGEE_NETWORK);

        String[] parts = message.split("#@#", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            Stargate.log(Level.WARNING, "Invalid legacy teleport request");
            return;
        }
        if (duplicateTeleport("U:" + message, 1000)) {
            return;
        }

        String playerName = parts[0];
        String destination = parts[1];

        Stargate.log(Level.FINER, "destination=" + destination + ",player=" + playerName);

        // Check if the player is online, if so, teleport, otherwise, queue
        Player player = Bukkit.getServer().getPlayer(playerName);
        if (player == null) {
            Stargate.log(Level.FINEST, "Player was null; adding to queue");

            addToQueue(playerName, destination, bungeeNetworkName, StorageType.LOCAL);
        } else {
            Network network;
            try {
                network = BungeeHelper.getLegacyBungeeNetwork(registry, networkManager, bungeeNetworkName);
            } catch (UnimplementedFlagException e) {
                Stargate.log(e);
                return;
            }
            if (network == null) {
                Stargate.log(Level.WARNING, "The legacy bungee network is missing, this is most definitly a bug please contact developers (/sg about)");
                return;
            }
            //If the destination is invalid, just let the player teleport to their last location
            Portal destinationPortal = network.getPortal(destination);
            if (destinationPortal == null) {
                Stargate.log(Level.FINE, String.format("Could not find destination portal with name '%s'", destination));
                return;
            }

            Stargate.log(Level.FINE, String.format("Teleporting player to destination portal '%s'", destinationPortal.getName()));
            teleportOnPlayerThread(player, destinationPortal);
        }
    }

    /**
     * Adds a player to the BungeeCord teleportation queue
     *
     * @param playerName  <p>The name of the player to add to the queue</p>
     * @param portalName  <p>The name of the portal the player is teleporting to</p>
     * @param networkName <p>The name of the network the entry portal belongs to</p>
     * @param storageType <p>Whether the entry portal belongs to an inter-server network</p>
     */
    private synchronized void addToQueue(String playerName, String portalName, String networkName,
                            StorageType storageType) {
        Network network = registry.getNetwork(networkName, storageType);

        /*
         * In some cases, there might be issues with a portal being deleted in a server, but still present in the
         * inter-server database. Therefore, we have to check for that...
         */
        if (network == null) {
            // Error: This bungee portal's %type% has been removed from the destination server instance.
            //(See Discussion One) %type% = network.
            String msg = String.format("Inter-server network ''%s'' could not be found", networkName);
            Stargate.log(Level.WARNING, msg);
        }
        Portal portal = network == null ? null : network.getPortal(portalName);
        if (portal == null) {
            // Error: This bungee portal's %type% has been removed from the destination server instance.
            //(See Discussion One) %type% = gate.
            String msg = String.format("Inter-server portal ''%s'' in network ''%s'' could not be found", portalName, networkName);
            Stargate.log(Level.WARNING, msg);
        }
        if (!(portal instanceof RealPortal) || portal.isDestroyed()) {
            return;
        }
        bungeeQueue.values().removeIf(entry -> entry.expiresAt() <= clock.getAsLong());
        if (bungeeQueue.size() >= MAX_PENDING) {
            bungeeQueue.remove(bungeeQueue.keySet().iterator().next());
        }
        bungeeQueue.put(playerName, new QueuedPortal(portal, clock.getAsLong() + QUEUE_LIFETIME));
    }

    @Override
    public synchronized Portal pullFromQueue(String playerName) {
        QueuedPortal entry = bungeeQueue.remove(playerName);
        return entry == null || entry.expiresAt() <= clock.getAsLong() || entry.portal().isDestroyed()
                ? null : entry.portal();
    }

    private synchronized boolean duplicateTeleport(String key, long lifetime) {
        long now = clock.getAsLong();
        recentTeleports.values().removeIf(expiry -> expiry <= now);
        if (recentTeleports.containsKey(key)) return true;
        if (recentTeleports.size() >= MAX_PENDING) {
            recentTeleports.remove(recentTeleports.keySet().iterator().next());
        }
        recentTeleports.put(key, now + lifetime);
        return false;
    }

    private void teleportOnPlayerThread(Player player, Portal destination) {
        if (!(destination instanceof RealPortal)) return;
        new StargateEntityTask(player) {
            @Override
            public void run() {
                if (player.isOnline() && !destination.isDestroyed()) destination.teleportHere(player, null);
            }
        }.runNow();
    }
}
