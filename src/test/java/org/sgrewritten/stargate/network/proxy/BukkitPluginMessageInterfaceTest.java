package org.sgrewritten.stargate.network.proxy;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockBukkitInject;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sgrewritten.stargate.StargateExtension;
import org.sgrewritten.stargate.property.PluginChannel;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class BukkitPluginMessageInterfaceTest {
    @MockBukkitInject private ServerMock server;
    private final List<byte[]> sent = new ArrayList<>();

    private void addCarrier(String name) {
        server.addPlayer(new PlayerMock(server, name) {
            @Override public void sendPluginMessage(Plugin plugin, String channel, byte[] message) {
                assertEquals(PluginChannel.BUNGEE.getChannel(), channel);
                sent.add(message);
            }
        });
    }

    private Plugin plugin() {
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getMessenger().registerOutgoingPluginChannel(plugin, PluginChannel.BUNGEE.getChannel());
        return plugin;
    }

    @Test
    void broadcastUsesOneCarrierEvenWithSeveralOnlinePlayers() throws Exception {
        addCarrier("one"); addCarrier("two"); addCarrier("three");
        new BukkitPluginMessageInterface().sendMessage("test", PluginChannel.NETWORK_CHANGED, plugin());
        assertEquals(1, sent.size());
    }

    @Test
    void directedPacketSurvivesProxyForwardEnvelopeWithUnicode() throws Exception {
        addCarrier("one"); addCarrier("two");
        String message = "玩家#@#传送门";
        new BukkitPluginMessageInterface().sendDirectedMessage(message, PluginChannel.LEGACY_BUNGEE,
                plugin(), "destination");
        assertEquals(1, sent.size());
        DataInputStream proxy = new DataInputStream(new ByteArrayInputStream(sent.getFirst()));
        assertEquals("Forward", proxy.readUTF());
        assertEquals("destination", proxy.readUTF());
        String subchannel = proxy.readUTF();
        byte[] payload = proxy.readNBytes(proxy.readUnsignedShort());
        assertEquals(0, proxy.available());
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(response);
        out.writeUTF(subchannel); out.writeShort(payload.length); out.write(payload);
        DataInputStream backend = new DataInputStream(new ByteArrayInputStream(response.toByteArray()));
        assertEquals(PluginChannel.LEGACY_BUNGEE.getChannel(), backend.readUTF());
        assertEquals(message, backend.readUTF());
        assertEquals(0, backend.available());
    }

    @Test
    void absentCarrierIsReportedInsteadOfSilentlyLosingRequest() {
        assertThrows(IOException.class, () -> new BukkitPluginMessageInterface().sendDirectedMessage("test",
                PluginChannel.PLAYER_TELEPORT, plugin(), "destination"));
    }
}
