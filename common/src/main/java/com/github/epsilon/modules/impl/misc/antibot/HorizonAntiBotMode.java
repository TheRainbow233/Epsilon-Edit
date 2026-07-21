package com.github.epsilon.modules.impl.misc.antibot;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Detects bots on servers using Horizon anti-cheat.
 * Horizon bots have a null gameMode when added via player info packets.
 */
public class HorizonAntiBotMode extends AntiBotMode {

    private final Set<UUID> botList = new HashSet<>();

    public HorizonAntiBotMode() {
        super("Horizon");
    }

    /**
     * Process incoming packets to detect Horizon bots.
     * Call this from the parent module's packet event handler.
     */
    public void onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket updatePacket) {
            // Check for ADD_PLAYER action
            for (ClientboundPlayerInfoUpdatePacket.Action action : updatePacket.actions()) {
                if (action == ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) {
                    for (ClientboundPlayerInfoUpdatePacket.Entry entry : updatePacket.entries()) {
                        if (entry.gameMode() == null) {
                            botList.add(entry.profileId());
                        }
                    }
                }
            }
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket removePacket) {
            botList.removeAll(removePacket.profileIds());
        }
    }

    @Override
    public boolean isBot(Player entity) {
        return botList.contains(entity.getUUID());
    }

    @Override
    public void reset() {
        botList.clear();
    }
}
