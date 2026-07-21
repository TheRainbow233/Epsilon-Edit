package com.github.epsilon.modules.impl.misc.antibot;

import com.github.epsilon.modules.impl.misc.AntiBot;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Detects Intave anti-cheat's "Heavy" bot type.
 * Intave sends UPDATE_LATENCY packets only for its bots shortly after they join,
 * with the ping delta matching the original latency within a 15ms window.
 *
 * Tested on: gamster.org and private servers with Intave (as of 7/28/2022).
 * Note: UPDATE_LATENCY action may not exist on newer MC versions.
 */
public class IntaveHeavyAntiBotMode extends AntiBotMode {

    private final Map<UUID, SuspectInfo> suspectList = new HashMap<>();
    private final Set<UUID> botList = new HashSet<>();

    /**
     * When true, only flags if a single entry is in the UPDATE_LATENCY packet.
     * When false, checks all entries (may produce false positives).
     */
    private static final boolean INTAVE_BUG_FIX = false;

    public IntaveHeavyAntiBotMode() {
        super("IntaveHeavy");
    }

    public void onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket updatePacket) {
            handleListPacket(updatePacket);
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket removePacket) {
            handlePlayerRemove(removePacket);
        }
    }

    private void handleListPacket(ClientboundPlayerInfoUpdatePacket packet) {
        // Determine which action triggered this packet
        boolean isAddPlayer = false;
        boolean isUpdateLatency = false;

        for (ClientboundPlayerInfoUpdatePacket.Action action : packet.actions()) {
            if (action == ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) {
                isAddPlayer = true;
            }
            if (action == ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY) {
                isUpdateLatency = true;
            }
        }

        if (isAddPlayer) {
            handlePlayerListAddPlayers(packet.entries());
        } else if (isUpdateLatency) {
            handlePlayerListUpdateLatency(packet.entries());
        }
    }

    private void handlePlayerRemove(ClientboundPlayerInfoRemovePacket packet) {
        for (UUID id : packet.profileIds()) {
            suspectList.remove(id);
            botList.remove(id);
        }
    }

    private void handlePlayerListAddPlayers(java.util.List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : entries) {
            if (entry.profile() == null) continue;

            if (entry.latency() < 2 || AntiBot.isGameProfileUnique(entry.profile())) {
                continue;
            }

            suspectList.put(entry.profileId(), new SuspectInfo(entry.latency(), System.currentTimeMillis()));
        }
    }

    /**
     * On older Intave versions, latency update packets would only update their bots'
     * ping rather than every player. We detect bots by checking if the delta ping
     * matches the original latency within a very short time window.
     */
    private void handlePlayerListUpdateLatency(java.util.List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
        if (INTAVE_BUG_FIX && entries.size() > 1) {
            return;
        }

        for (ClientboundPlayerInfoUpdatePacket.Entry entry : entries) {
            SuspectInfo info = suspectList.get(entry.profileId());
            if (info == null) continue;

            int deltaPing = info.latency - entry.latency();
            long deltaMs = System.currentTimeMillis() - info.timestamp;

            // Intave instantly sends this packet. We allow 15ms tolerance for server lag.
            if (deltaPing == info.latency && deltaMs <= 15) {
                botList.add(entry.profileId());
            }

            suspectList.remove(entry.profileId());
        }
    }

    @Override
    public boolean isBot(Player entity) {
        return botList.contains(entity.getUUID());
    }

    @Override
    public void reset() {
        suspectList.clear();
        botList.clear();
    }

    private record SuspectInfo(int latency, long timestamp) {}
}
