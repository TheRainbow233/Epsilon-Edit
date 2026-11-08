package com.github.epsilon.modules.impl.combat.antibot

import com.github.epsilon.kernel.MinecraftWrapper.mc
import com.github.epsilon.modules.impl.combat.AntiBot
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.util.EnumMap
import java.util.EnumSet
import java.util.function.Predicate
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAnimatePacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

private fun armorItems(vararg items: Item): Predicate<ItemStack> =
    Predicate { stack -> stack.item in items }

/**
 * 综合自定义反假人检测，条件可在模块设置中逐项开关。
 */
class CustomAntiBotMode : AntiBotMode("Custom") {

    private enum class ArmorPredicate(val tag: String, val predicate: Predicate<ItemStack>) {
        NOTHING("Nothing", Predicate { it.isEmpty }),
        LEATHER(
            "Leather", armorItems(
                Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS
            )
        ),
        CHAIN(
            "Chain", armorItems(
                Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS
            )
        ),
        IRON(
            "Iron", armorItems(
                Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS
            )
        ),
        GOLD(
            "Gold", armorItems(
                Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS
            )
        ),
        DIAMOND(
            "Diamond", armorItems(
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS
            )
        ),
        NETHERITE(
            "Netherite", armorItems(
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS
            )
        ),
        ELYTRA("Elytra", armorItems(Items.ELYTRA)),
        TURTLE_SCUTE("TurtleScute", armorItems(Items.TURTLE_HELMET)),
        PUMPKIN("Pumpkin", armorItems(Items.CARVED_PUMPKIN)),
        SKULL(
            "Skull", armorItems(
                Items.SKELETON_SKULL, Items.WITHER_SKELETON_SKULL,
                Items.PLAYER_HEAD, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD
            )
        ),
    }

    private val flyingSet = Int2IntOpenHashMap()
    private val hitSet = IntOpenHashSet()
    private val notAlwaysInRadiusSet = IntOpenHashSet()
    private val swungSet = IntOpenHashSet()
    private val crittedSet = IntOpenHashSet()
    private val attributesSet = IntOpenHashSet()
    private val armorSet = IntOpenHashSet()

    var invalidGroundEnabled = true
    var vlToConsiderAsBot = 10

    var alwaysInRadiusEnabled = false
    var alwaysInRadiusRange = 20.0f

    var armorEnabled = false

    var ageEnabled = false
    var ageMinimum = 20

    var nameEnabled = true
    var nameMinLength = 3
    var nameMaxLength = 16
    var nameValidateChars = true

    private val customConditions: MutableSet<CustomCondition> = EnumSet.noneOf(CustomCondition::class.java)

    fun setCustomConditions(conditions: Set<CustomCondition>) {
        customConditions.clear()
        customConditions.addAll(conditions)
    }

    override fun onPacketReceive(packet: Packet<*>) {
        when (packet) {
            is ClientboundMoveEntityPacket -> handleMove(packet)
            is ClientboundUpdateAttributesPacket -> attributesSet.add(packet.entityId)
            is ClientboundAnimatePacket -> {
                val action = packet.action
                // 26.3 移除了挥手动作常量，只保留暴击判定
                if (action == ClientboundAnimatePacket.CRITICAL_HIT ||
                    action == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT
                ) {
                    crittedSet.add(packet.id)
                }
            }

            is ClientboundRemoveEntitiesPacket -> {
                for (id in packet.entityIds()) {
                    attributesSet.remove(id)
                    flyingSet.remove(id)
                    hitSet.remove(id)
                    notAlwaysInRadiusSet.remove(id)
                    armorSet.remove(id)
                }
            }
        }
    }

    private fun handleMove(packet: ClientboundMoveEntityPacket) {
        if (!packet.hasPosition() || !invalidGroundEnabled) return

        val entity = packet.getEntity(mc.level ?: return) ?: return
        val id = entity.id
        val currentVl = flyingSet.getOrDefault(id, 0)

        // 只有 onGround 且实际发生 y 位移时才累计，避免静立误判
        if (entity.onGround() && entity.yo != entity.y) {
            flyingSet.put(id, currentVl + 1)
        } else if (!entity.onGround() && currentVl > 0) {
            val newVl = currentVl / 2
            if (newVl <= 0) flyingSet.remove(id) else flyingSet.put(id, newVl)
        }
    }

    override fun onAttack(entityId: Int) {
        hitSet.add(entityId)
    }

    override fun onTick() {
        val level = mc.level ?: return
        val player = mc.player ?: return

        val rangeSq = alwaysInRadiusRange * alwaysInRadiusRange

        for (entity in level.players()) {
            if (entity === player) continue
            val id = entity.id

            if (alwaysInRadiusEnabled) {
                if (player.distanceToSqr(entity) > rangeSq) {
                    notAlwaysInRadiusSet.add(id)
                }
            }

            if (armorEnabled && !isValidArmor(entity)) {
                armorSet.add(id)
            }
        }

        armorSet.removeIf { id ->
            val entity = level.getEntity(id)
            entity !is Player || isValidArmor(entity)
        }
    }

    override fun isBot(entity: Player): Boolean {
        val id = entity.id

        if (invalidGroundEnabled && isInvalidGround(id)) return true
        if (alwaysInRadiusEnabled && !notAlwaysInRadiusSet.contains(id)) return true
        if (ageEnabled && entity.tickCount < ageMinimum) return true
        if (armorEnabled && armorSet.contains(id)) return true
        if (nameEnabled && hasInvalidName(entity)) return true

        for (condition in customConditions) {
            if (condition.check(entity, this)) return true
        }
        return false
    }

    private fun isInvalidGround(entityId: Int): Boolean =
        flyingSet.getOrDefault(entityId, 0) >= vlToConsiderAsBot

    private fun hasInvalidName(entity: Player): Boolean {
        val name = entity.scoreboardName
        if (name.length < nameMinLength || name.length > nameMaxLength) return true
        if (nameValidateChars) {
            for (c in name) {
                if (!isVanillaChar(c)) return true
            }
        }
        return false
    }

    private fun isValidArmor(entity: Player): Boolean {
        for (slot in ALLOWED_ARMOR.keys) {
            val armor = entity.getItemBySlot(slot)
            val allowed = ALLOWED_ARMOR[slot] ?: continue
            if (allowed.none { it.predicate.test(armor) }) return false
        }
        return true
    }

    override fun reset() {
        flyingSet.clear()
        notAlwaysInRadiusSet.clear()
        hitSet.clear()
        swungSet.clear()
        crittedSet.clear()
        attributesSet.clear()
        armorSet.clear()
    }

    val hitEntities: IntOpenHashSet get() = hitSet
    val swungEntities: IntOpenHashSet get() = swungSet
    val crittedEntities: IntOpenHashSet get() = crittedSet
    val attributeEntities: IntOpenHashSet get() = attributesSet

    enum class CustomCondition {
        DUPLICATE {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean =
                AntiBot.isADuplicate(entity.gameProfile)
        },
        NO_GAME_MODE {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean {
                val info = mc.connection?.getPlayerInfo(entity.uuid) ?: return false
                return info.gameMode == null
            }
        },
        ILLEGAL_PITCH {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean = kotlin.math.abs(entity.xRot) > 90.0f
        },
        FAKE_ENTITY_ID {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean =
                entity.id < 0 || entity.id > 1_000_000_000
        },
        NEED_HIT {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean =
                !mode.hitEntities.contains(entity.id)
        },
        ILLEGAL_HEALTH {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean {
                val player = mc.player ?: return false
                return entity.health > player.maxHealth
            }
        },
        SWUNG {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean =
                !mode.swungEntities.contains(entity.id)
        },
        CRITTED {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean =
                !mode.crittedEntities.contains(entity.id)
        },
        ATTRIBUTES {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean =
                !mode.attributeEntities.contains(entity.id)
        },
        ILLEGAL_SCALE {
            override fun check(entity: Player, mode: CustomAntiBotMode): Boolean =
                entity.getAttributeValue(Attributes.SCALE) != 1.0
        },
        ;

        internal abstract fun check(entity: Player, mode: CustomAntiBotMode): Boolean
    }

    private companion object {

        private val BASE_PREDICATES = EnumSet.of(
            ArmorPredicate.NOTHING, ArmorPredicate.LEATHER,
            ArmorPredicate.CHAIN, ArmorPredicate.IRON,
            ArmorPredicate.GOLD, ArmorPredicate.DIAMOND,
            ArmorPredicate.NETHERITE
        )

        private val HELMET_PREDICATES = EnumSet.of(
            ArmorPredicate.NOTHING, ArmorPredicate.LEATHER,
            ArmorPredicate.CHAIN, ArmorPredicate.IRON,
            ArmorPredicate.GOLD, ArmorPredicate.DIAMOND,
            ArmorPredicate.NETHERITE, ArmorPredicate.TURTLE_SCUTE,
            ArmorPredicate.PUMPKIN, ArmorPredicate.SKULL
        )

        private val CHESTPLATE_PREDICATES = EnumSet.of(
            ArmorPredicate.NOTHING, ArmorPredicate.LEATHER,
            ArmorPredicate.CHAIN, ArmorPredicate.IRON,
            ArmorPredicate.GOLD, ArmorPredicate.DIAMOND,
            ArmorPredicate.NETHERITE, ArmorPredicate.ELYTRA
        )

        val ALLOWED_ARMOR: EnumMap<EquipmentSlot, EnumSet<ArmorPredicate>> =
            EnumMap<EquipmentSlot, EnumSet<ArmorPredicate>>(EquipmentSlot::class.java).apply {
                put(EquipmentSlot.HEAD, EnumSet.copyOf(HELMET_PREDICATES))
                put(EquipmentSlot.CHEST, EnumSet.copyOf(CHESTPLATE_PREDICATES))
                put(EquipmentSlot.LEGS, EnumSet.copyOf(BASE_PREDICATES))
                put(EquipmentSlot.FEET, EnumSet.copyOf(BASE_PREDICATES))
            }

        fun isVanillaChar(c: Char): Boolean =
            (c in '0'..'9') || (c in 'a'..'z') || (c in 'A'..'Z') || c == '_'

    }

}
