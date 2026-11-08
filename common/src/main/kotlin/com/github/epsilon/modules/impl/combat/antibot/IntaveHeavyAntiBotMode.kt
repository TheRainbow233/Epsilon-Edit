package com.github.epsilon.modules.impl.combat.antibot

import com.github.epsilon.modules.impl.combat.AntiBot
import java.util.HashMap
import java.util.HashSet
import java.util.UUID
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.entity.player.Player

/**
 * Intave 反作弊的 Heavy 假人：通过 UPDATE_LATENCY 包的延迟差与时间窗口识别。
 */
class IntaveHeavyAntiBotMode : AntiBotMode("IntaveHeavy") {

    private val suspectList: MutableMap<UUID, SuspectInfo> = HashMap()
    private val botList: MutableSet<UUID> = HashSet()

    override fun onPacketReceive(packet: Packet<*>) {
        if (packet is ClientboundPlayerInfoUpdatePacket) {
            handleListPacket(packet)
        } else if (packet is ClientboundPlayerInfoRemovePacket) {
            for (id in packet.profileIds()) {
                suspectList.remove(id)
                botList.remove(id)
            }
        }
    }

    private fun handleListPacket(packet: ClientboundPlayerInfoUpdatePacket) {
        var isAddPlayer = false
        var isUpdateLatency = false

        for (action in packet.actions()) {
            if (action == ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) isAddPlayer = true
            if (action == ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY) isUpdateLatency = true
        }

        if (isAddPlayer) {
            handlePlayerListAddPlayers(packet.entries())
        } else if (isUpdateLatency) {
            handlePlayerListUpdateLatency(packet.entries())
        }
    }

    private fun handlePlayerListAddPlayers(entries: List<ClientboundPlayerInfoUpdatePacket.Entry>) {
        for (entry in entries) {
            val profile = entry.profile() ?: continue
            if (entry.latency() < 2 || AntiBot.isGameProfileUnique(profile)) continue
            suspectList[entry.profileId()] = SuspectInfo(entry.latency(), System.currentTimeMillis())
        }
    }

    private fun handlePlayerListUpdateLatency(entries: List<ClientboundPlayerInfoUpdatePacket.Entry>) {
        for (entry in entries) {
            val info = suspectList[entry.profileId()] ?: continue
            val deltaPing = info.latency - entry.latency()
            val deltaMs = System.currentTimeMillis() - info.timestamp

            // Intave 会立即发送该包，这里留 15ms 容忍服务器延迟
            if (deltaPing == info.latency && deltaMs <= 15) {
                botList.add(entry.profileId())
            }
            suspectList.remove(entry.profileId())
        }
    }

    override fun isBot(entity: Player): Boolean = botList.contains(entity.uuid)

    override fun reset() {
        suspectList.clear()
        botList.clear()
    }

    private data class SuspectInfo(val latency: Int, val timestamp: Long)

}
