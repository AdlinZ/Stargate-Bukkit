package org.sgrewritten.stargate.util.portal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.util.BlockVector;
import org.sgrewritten.stargate.api.StargateAPI;
import org.sgrewritten.stargate.api.gate.GateAPI;
import org.sgrewritten.stargate.api.gate.GateStructureType;
import org.sgrewritten.stargate.api.network.portal.BlockLocation;
import org.sgrewritten.stargate.api.network.portal.PortalPosition;
import org.sgrewritten.stargate.api.network.portal.PositionType;
import org.sgrewritten.stargate.api.network.portal.RealPortal;
import org.sgrewritten.stargate.api.network.portal.flag.PortalFlag;
import org.sgrewritten.stargate.api.network.portal.flag.StargateFlag;
import org.sgrewritten.stargate.exception.InvalidStructureException;
import org.sgrewritten.stargate.network.portal.formatting.NoLineColorFormatter;
import org.sgrewritten.stargate.property.StargateConstant;
import org.sgrewritten.stargate.util.ButtonHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Restore missing core controls when loading a portal without its former add-on. */
public final class LoadedPortalControls {
    private LoadedPortalControls() { }

    public static List<PortalPosition> restore(RealPortal portal, StargateAPI api) throws InvalidStructureException {
        GateAPI gate = portal.getGate();
        List<PortalPosition> positions = gate.getPortalPositions();
        // Add-ons can intentionally omit signs/buttons. Their load-event handlers
        // must remain free to activate or replace their stored control positions.
        boolean managedByAddon = positions.stream().anyMatch(position ->
                !position.getPluginName().equals(StargateConstant.STARGATE_NAME)
                        && Bukkit.getPluginManager().isPluginEnabled(position.getPluginName()));
        if (managedByAddon || PortalFlag.parseFlags(portal.getAllFlagsString()).stream()
                .anyMatch(api.getMaterialHandlerResolver()::hasRegisteredCustomFlag)) {
            return List.of();
        }

        List<PortalPosition> restored = new ArrayList<>();
        List<PortalPosition> reserved = new ArrayList<>(positions);
        if (!hasCoreControl(positions, PositionType.SIGN)) {
            PortalPosition sign = findControl(gate, api, reserved, PositionType.SIGN);
            sign.setAttachment(new NoLineColorFormatter());
            restored.add(sign);
            reserved.add(sign);
        }
        if (!portal.hasFlag(StargateFlag.ALWAYS_ON) && !hasCoreControl(positions, PositionType.BUTTON)) {
            restored.add(findControl(gate, api, reserved, PositionType.BUTTON));
        }
        // Only modify the gate after a complete usable set has been found.
        restored.forEach(gate::addPortalPosition);
        return List.copyOf(restored);
    }

    private static boolean hasCoreControl(List<PortalPosition> positions, PositionType type) {
        return positions.stream().anyMatch(position -> position.isActive()
                && position.getPluginName().equals(StargateConstant.STARGATE_NAME)
                && position.getPositionType() == type);
    }

    private static PortalPosition findControl(GateAPI gate, StargateAPI api,
                                              List<PortalPosition> reserved, PositionType type)
            throws InvalidStructureException {
        List<BlockVector> candidates = gate.getFormat().getControlBlocks().stream()
                .sorted(Comparator.comparingInt(BlockVector::getBlockY)
                        .thenComparingInt(BlockVector::getBlockX).thenComparingInt(BlockVector::getBlockZ)).toList();
        // Prefer an existing control over placing a new button in an empty slot.
        for (boolean existingOnly : new boolean[]{true, false}) {
            for (BlockVector vector : candidates) {
                if (reserved.stream().anyMatch(position -> position.getRelativePositionLocation().equals(vector))) {
                    continue;
                }
                Location location = gate.getLocation(vector);
                if (api.getRegistry().getPortalPosition(location) != null
                        || api.getRegistry().getPortal(new BlockLocation(location), GateStructureType.IRIS) != null
                        || api.getRegistry().getPortal(new BlockLocation(location), GateStructureType.FRAME) != null) {
                    continue;
                }
                Material material = location.getBlock().getType();
                boolean suitable = type == PositionType.SIGN ? Tag.WALL_SIGNS.isTagged(material)
                        : ButtonHelper.isButton(material) || (!existingOnly && (material.isAir() || material == Material.WATER));
                if (suitable) {
                    return new PortalPosition(type, vector.clone(), StargateConstant.STARGATE_NAME);
                }
            }
        }
        throw new InvalidStructureException("No safe " + type + " control position; stored portal data retained");
    }
}
