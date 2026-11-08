package com.github.epsilon.modules.impl.combat

import com.github.epsilon.events.api.handler
import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.AttackEntityEvent
import com.github.epsilon.events.impl.KeyboardInputEvent
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.kernel.jvm.NonNullContext
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import java.util.EnumSet
import java.util.Random
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity

/**
 * Super Knockback：通过操纵冲刺状态放大对目标的击退。
 *
 * 参考 LiquidBounce 的 ModuleSuperKnockback。
 */
object SuperKnockback : Module("Super Knockback", Category.COMBAT) {

    private enum class Mode {
        Packet,
        SprintTap,
    }

    private enum class Condition {
        OnlyFacing,
        OnlyOnGround,
        NotInWater,
    }

    private enum class TapState {
        IDLE,
        WAITING_STOP,
        WAITING_RESPRINT,
    }

    private val mode by enumSetting("Mode", Mode.Packet)

    private val hurtTime by intSetting("Hurt Time", 10, 0..10, 1)
    private val chance by intSetting("Chance", 100, 0..100, 1)

    private val conditions by multiEnumSetting("Conditions", EnumSet.of(Condition.NotInWater))

    private val onlyOnMove by boolSetting("Only On Move", true)
    private val onlyForward by boolSetting("Only Forward", true, { onlyOnMove })

    private val reSprintMin by intSetting("ReSprint Min", 0, 0..10, 1, { mode == Mode.SprintTap })
    private val reSprintMax by intSetting("ReSprint Max", 1, 0..10, 1, { mode == Mode.SprintTap })

    private var cancelSprint = false
    private var tapState = TapState.IDLE
    private var tapTickCounter = 0
    private val random = Random()

    init {
        onDisable {
            cancelSprint = false
            tapState = TapState.IDLE
            tapTickCounter = 0
        }

        safeEventHandler<PlayerTickEvent.Pre> {
            if (mode != Mode.SprintTap || tapState == TapState.IDLE) return@safeEventHandler
            handleSprintTapTick()
        }

        safeEventHandler<AttackEntityEvent> { event ->
            onAttack(event.entity)
        }

        handler<KeyboardInputEvent> { event ->
            if (cancelSprint && mode == Mode.SprintTap) {
                event.sprint = false
            }
        }
    }

    private fun NonNullContext.onAttack(target: Entity) {
        if (!shouldOperate(target)) return

        // 目标 hurtTime 过高时跳过
        if (target is LivingEntity && target.hurtTime > hurtTime) return

        if (random.nextInt(100) >= chance) return

        // 暴击（下坠中的空中攻击）时不介入
        if (player.fallDistance > 0.0f && !player.onGround() && !player.isInWater) return

        when (mode) {
            Mode.Packet -> doPacketMode()
            Mode.SprintTap -> doSprintTap()
        }
    }

    private fun NonNullContext.doPacketMode() {
        val wasSprinting = player.isSprinting

        if (wasSprinting) {
            connection.send(
                ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING)
            )
        }

        // 快速切换冲刺状态以最大化击退
        connection.send(
            ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING)
        )
        connection.send(
            ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING)
        )
        connection.send(
            ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING)
        )

        player.isSprinting = true
        player.wasSprinting = true
    }

    private fun NonNullContext.doSprintTap() {
        if (!player.isSprinting || !player.wasSprinting) return
        if (tapState != TapState.IDLE) return

        tapState = TapState.WAITING_STOP
        cancelSprint = true
        tapTickCounter = 0
    }

    private fun NonNullContext.handleSprintTapTick() {
        when (tapState) {
            TapState.WAITING_STOP -> {
                if (!player.isSprinting && !player.wasSprinting) {
                    tapState = TapState.WAITING_RESPRINT
                    tapTickCounter = 0
                }
            }

            TapState.WAITING_RESPRINT -> {
                val min = minOf(reSprintMin, reSprintMax)
                val max = maxOf(reSprintMin, reSprintMax)
                val delay = min + random.nextInt(maxOf(1, max - min + 1))

                tapTickCounter++
                if (tapTickCounter >= delay) {
                    tapState = TapState.IDLE
                    cancelSprint = false
                }
            }

            TapState.IDLE -> Unit
        }
    }

    private fun NonNullContext.shouldOperate(target: Entity): Boolean {
        // OnlyFacing：目标必须背对玩家
        if (conditions.contains(Condition.OnlyFacing)) {
            if (target.lookAngle.dot(player.position().subtract(target.position())) >= 0) return false
        }

        if (conditions.contains(Condition.OnlyOnGround) && !player.onGround()) return false
        if (conditions.contains(Condition.NotInWater) && player.isInWater) return false

        if (onlyOnMove) {
            val move = player.input.moveVector
            val hasForward = move.y != 0.0f
            val hasStrafe = move.x != 0.0f

            if (!hasForward && !hasStrafe) return false
            if (onlyForward && hasStrafe && !hasForward) return false
        }

        return true
    }

}
