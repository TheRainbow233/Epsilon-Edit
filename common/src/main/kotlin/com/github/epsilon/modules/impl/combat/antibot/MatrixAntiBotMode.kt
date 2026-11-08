package com.github.epsilon.modules.impl.combat.antibot

import com.github.epsilon.kernel.MinecraftWrapper.mc
import com.github.epsilon.modules.impl.combat.AntiBot
import java.util.HashMap
import java.util.HashSet
import java.util.UUID
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Matrix 反作弊的假人：空 profile 属性，且会在 1 tick 内替换随机盔甲。
 */
class MatrixAntiBotMode : AntiBotMode("Matrix") {

    private val suspectList: MutableSet<UUID> = HashSet()
    private val botList: MutableSet<UUID> = HashSet()

    private val prevArmorMap: MutableMap<UUID, Array<ItemStack>> = HashMap()

    override fun onPacketReceive(packet: Packet<*>) {
        if (packet is ClientboundPlayerInfoUpdatePacket) {
            var isAddPlayer = false
            for (action in packet.actions()) {
                if (action == ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) {
                    isAddPlayer = true
                    break
                }
            }
            if (!isAddPlayer) return

            mc.execute {
                for (entry in packet.entries()) {
                    val profile = entry.profile() ?: continue

                    if (entry.latency() < 2 ||
                        !profile.properties().isEmpty ||
                        AntiBot.isGameProfileUnique(profile)
                    ) {
                        continue
                    }

                    if (AntiBot.isADuplicate(profile)) {
                        botList.add(entry.profileId())
                        continue
                    }

                    suspectList.add(entry.profileId())
                }
            }
        } else if (packet is ClientboundPlayerInfoRemovePacket) {
            mc.execute {
                for (uuid in packet.profileIds()) {
                    suspectList.remove(uuid)
                    botList.remove(uuid)
                    prevArmorMap.remove(uuid)
                }
            }
        }
    }

    override fun onTick() {
        val level = mc.level ?: return
        if (suspectList.isEmpty()) return

        for (entity in level.players()) {
            val uuid = entity.uuid
            if (!suspectList.contains(uuid)) continue

            val prevArmor = prevArmorMap[uuid]
            if (prevArmor == null) {
                // 第一次检查：记录当前盔甲，等待 1 tick
                prevArmorMap[uuid] = getArmorSlots(entity)
                continue
            }

            val armorChanged = !armorEquals(prevArmor, getArmorSlots(entity))
            if ((isFullyArmored(entity) || armorChanged) && entity.gameProfile.properties().isEmpty) {
                botList.add(uuid)
            }

            suspectList.remove(uuid)
            prevArmorMap.remove(uuid)
        }
    }

    private fun getArmorSlots(entity: Player): Array<ItemStack> =
        Array(ARMOR_SLOTS.size) { index -> entity.getItemBySlot(ARMOR_SLOTS[index]) }

    private fun armorEquals(a: Array<ItemStack>, b: Array<ItemStack>): Boolean {
        if (a.size != b.size) return false
        for (index in a.indices) {
            if (!ItemStack.isSameItemSameComponents(a[index], b[index])) return false
        }
        return true
    }

    private fun isFullyArmored(entity: Player): Boolean =
        ARMOR_SLOTS.all { slot -> entity.getItemBySlot(slot).isEnchanted }

    override fun isBot(entity: Player): Boolean = botList.contains(entity.uuid)

    override fun reset() {
        suspectList.clear()
        botList.clear()
        prevArmorMap.clear()
    }

    private companion object {
        val ARMOR_SLOTS = arrayOf(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        )
    }

}
