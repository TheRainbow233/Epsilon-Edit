package com.github.epsilon.modules.impl.combat

import com.github.epsilon.events.api.handler
import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.AttackEntityEvent
import com.github.epsilon.events.impl.GameJoinedEvent
import com.github.epsilon.events.impl.GameLeftEvent
import com.github.epsilon.events.impl.PacketEvent
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.kernel.MinecraftWrapper.mc
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import com.github.epsilon.modules.impl.combat.antibot.AntiBotMode
import com.github.epsilon.modules.impl.combat.antibot.CustomAntiBotMode
import com.github.epsilon.modules.impl.combat.antibot.HorizonAntiBotMode
import com.github.epsilon.modules.impl.combat.antibot.IntaveHeavyAntiBotMode
import com.github.epsilon.modules.impl.combat.antibot.MatrixAntiBotMode
import com.github.epsilon.settings.group
import com.github.epsilon.settings.impl.EnumSetting
import com.github.epsilon.settings.impl.MultiEnumSetting
import com.mojang.authlib.GameProfile
import java.util.EnumSet
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * AntiBot 模块：用多种策略识别人造的假玩家，并把结果提供给其它模块过滤目标。
 *
 * 移植自 LiquidBounce 的 AntiBot 体系，保留可插拔模式与自定义条件。
 */
object AntiBot : Module("Anti Bot", Category.COMBAT) {

    enum class Mode {
        Custom,
        Horizon,
        IntaveHeavy,
        Matrix,
    }

    // 模式实例必须先于设置创建，供 onChanged 回调同步配置
    private val customMode = CustomAntiBotMode()
    private val horizonMode = HorizonAntiBotMode()
    private val intaveHeavyMode = IntaveHeavyAntiBotMode()
    private val matrixMode = MatrixAntiBotMode()

    private val modeSetting: EnumSetting<Mode> = enumSetting("Mode", Mode.Custom, onChanged = { syncCustomModeSettings() })
    private val mode by modeSetting

    private val literalNpc by boolSetting("Literal NPC", false)
    private val notInTabList by boolSetting("Not In Tab List", false)

    private val invalidGround by boolSetting("Invalid Ground", true, { mode == Mode.Custom }, { syncCustomModeSettings() })
    private val invalidGroundVl by intSetting(
        "Invalid Ground VL", 10, 1..50, 1,
        { mode == Mode.Custom && invalidGround }, { syncCustomModeSettings() }
    )

    private val alwaysInRadius by boolSetting("Always In Radius", false, { mode == Mode.Custom }, { syncCustomModeSettings() })
    private val alwaysInRadiusRange by intSetting(
        "Radius Range", 20, 5..30, 1,
        { mode == Mode.Custom && alwaysInRadius }, { syncCustomModeSettings() }
    )

    private val ageCheck by boolSetting("Age Check", false, { mode == Mode.Custom }, { syncCustomModeSettings() })
    private val ageMinimum by intSetting(
        "Age Minimum", 20, 0..120, 1,
        { mode == Mode.Custom && ageCheck }, { syncCustomModeSettings() }
    )

    private val armorCheck by boolSetting("Armor Check", false, { mode == Mode.Custom }, { syncCustomModeSettings() })

    private val nameCheck by boolSetting("Name Check", true, { mode == Mode.Custom }, { syncCustomModeSettings() })
    private val nameCheckList = settingGroup("Name Setting")
    private val nameMinLength by intSetting(
        "Name Min Length", 3, 1..32, 1,
        { mode == Mode.Custom && nameCheck }, { syncCustomModeSettings() }
    ).group(nameCheckList)
    private val nameMaxLength by intSetting(
        "Name Max Length", 16, 1..32, 1,
        { mode == Mode.Custom && nameCheck }, { syncCustomModeSettings() }
    ).group(nameCheckList)

    private val customConditions: MultiEnumSetting<CustomAntiBotMode.CustomCondition> = multiEnumSetting(
        "Conditions",
        EnumSet.of(
            CustomAntiBotMode.CustomCondition.NO_GAME_MODE,
            CustomAntiBotMode.CustomCondition.ILLEGAL_PITCH,
            CustomAntiBotMode.CustomCondition.FAKE_ENTITY_ID,
        ),
        { mode == Mode.Custom },
        { syncCustomModeSettings() },
    )

    init {
        safeEventHandler<PacketEvent.Receive> { event ->
            activeMode().onPacketReceive(event.packet)
        }

        safeEventHandler<PlayerTickEvent.Pre> {
            if (mode == Mode.Custom) customMode.onTick()
            if (mode == Mode.Matrix) matrixMode.onTick()
        }

        safeEventHandler<AttackEntityEvent> { event ->
            if (mode == Mode.Custom) customMode.onAttack(event.entity.id)
        }

        handler<GameJoinedEvent> { resetModes() }
        handler<GameLeftEvent> { resetModes() }

        onEnable { syncCustomModeSettings() }
        onDisable { resetModes() }
    }

    /** 供其它模块过滤目标；模块禁用时始终返回 false。 */
    fun isBot(entity: Entity): Boolean {
        if (!isEnabled) return false
        if (entity === mc.player) return false
        val player = entity as? Player ?: return false
        return checkBot(player)
    }

    private fun checkBot(player: Player): Boolean {
        val connection = mc.connection

        // 不在在线玩家 ID 列表中的实体
        if (literalNpc && connection != null && !connection.onlinePlayerIds.contains(player.uuid)) {
            return true
        }

        // 不在 tab 列表中的实体
        if (notInTabList && isMissingFromTabList(player)) {
            return true
        }

        return activeMode().isBot(player)
    }

    private fun activeMode(): AntiBotMode = when (mode) {
        Mode.Horizon -> horizonMode
        Mode.IntaveHeavy -> intaveHeavyMode
        Mode.Matrix -> matrixMode
        Mode.Custom -> customMode
    }

    private fun isMissingFromTabList(player: Player): Boolean {
        val connection = mc.connection ?: return false
        val uuid = player.uuid
        return connection.listedOnlinePlayers.none { it.profile.id == uuid }
    }

    private fun syncCustomModeSettings() {
        customMode.invalidGroundEnabled = invalidGround
        customMode.vlToConsiderAsBot = invalidGroundVl
        customMode.alwaysInRadiusEnabled = alwaysInRadius
        customMode.alwaysInRadiusRange = alwaysInRadiusRange.toFloat()
        customMode.ageEnabled = ageCheck
        customMode.ageMinimum = ageMinimum
        customMode.armorEnabled = armorCheck
        customMode.nameEnabled = nameCheck
        customMode.nameMinLength = nameMinLength
        customMode.nameMaxLength = nameMaxLength
        customMode.setCustomConditions(customConditions.value)
    }

    private fun resetModes() {
        customMode.reset()
        horizonMode.reset()
        intaveHeavyMode.reset()
        matrixMode.reset()
    }

    override fun resetCustomState() {
        resetModes()
    }

    /** 是否存在同名但 UUID 不同的在线玩家。 */
    fun isADuplicate(profile: GameProfile): Boolean {
        if (mc.connection == null) return false
        val level = mc.level ?: return false
        var count = 0
        for (player in level.players()) {
            val other = player.gameProfile
            if (other.name == profile.name && other.id != profile.id) count++
        }
        return count == 1
    }

    /** 是否存在同名且同 UUID 的在线玩家。 */
    fun isGameProfileUnique(profile: GameProfile): Boolean {
        if (mc.connection == null) return false
        val level = mc.level ?: return false
        var count = 0
        for (player in level.players()) {
            val other = player.gameProfile
            if (other.id == profile.id && other.name == profile.name) count++
        }
        return count == 1
    }

}
