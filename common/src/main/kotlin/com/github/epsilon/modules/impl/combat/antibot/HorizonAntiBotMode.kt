package com.github.epsilon.modules.impl.combat.antibot

import java.util.HashSet
import java.util.UUID
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.entity.player.Player

/**
 * Horizon 反作弊服务器的假人：在玩家信息包中 gameMode 为 null。
 */
class HorizonAntiBotMode : AntiBotMode("Horizon") {

    private val botList: MutableSet<UUID> = HashSet()

    override fun onPacketReceive(packet: Packet<*>) {
        if (packet is ClientboundPlayerInfoUpdatePacket) {
            for (action in packet.actions()) {
                if (action != ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) continue
                for (entry in packet.entries()) {
                    if (entry.gameMode() == null) {
                        botList.add(entry.profileId())
                    }
                }
            }
        } else if (packet is ClientboundPlayerInfoRemovePacket) {
            botList.removeAll(packet.profileIds())
        }
    }

    override fun isBot(entity: Player): Boolean = botList.contains(entity.uuid)

    override fun reset() {
        botList.clear()
    }

}
