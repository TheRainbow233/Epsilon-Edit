package com.github.epsilon.modules.impl.combat

import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.kernel.gameplay.player.SwingMode
import com.github.epsilon.kernel.gameplay.rotation.Rotation
import com.github.epsilon.kernel.gameplay.rotation.RotationPriority
import com.github.epsilon.kernel.jvm.NonNullContext
import com.github.epsilon.kernel.render.TrajectorySimulator
import com.github.epsilon.kernel.render.TrajectorySimulator.TrajectoryDescriptor
import com.github.epsilon.managers.RotationManager
import com.github.epsilon.managers.target.TargetManager
import com.github.epsilon.managers.target.TargetRequest
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import kotlin.math.abs
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/**
 * Auto Projectile：对附近目标自动投掷弹射物。
 *
 * 通过 [TrajectorySimulator] 暴力搜索可命中的俯仰角，再交给 RotationManager 静默转向。
 */
object AutoProjectile : Module("Auto Projectile", Category.COMBAT) {

    private enum class TargetMode {
        KillAura,
        Nearest,
    }

    private val targetMode by enumSetting("Target Mode", TargetMode.KillAura)
    private val range by doubleSetting("Range", 20.0, 5.0..50.0, 1.0)
    private val maxAngle by doubleSetting("Max Angle", 30.0, 5.0..90.0, 1.0)
    private val silentRotate by boolSetting("Silent Rotate", true)
    private val maxTicks by intSetting("Max Ticks", 100, 30..300, 10)
    private val stopAtKARange by boolSetting("Stop At KA Range", true)
    private val throwDelay by intSetting("Throw Delay", 5, 1..40, 1)

    private var delayCounter = 0

    init {
        onDisable {
            delayCounter = 0
        }

        safeEventHandler<PlayerTickEvent.Pre> {
            if (mc.gui.screen() != null) return@safeEventHandler
            if (player.isUsingItem) return@safeEventHandler

            // 投掷间隔
            if (delayCounter > 0) {
                delayCounter--
                return@safeEventHandler
            }

            val stack = player.mainHandItem
            if (stack.isEmpty) return@safeEventHandler

            val desc = TrajectorySimulator.resolveHeldItem(player, stack, false) ?: return@safeEventHandler

            // 弓与弩交给其它模块
            if (desc.type == TrajectorySimulator.TrajectoryType.ARROW) return@safeEventHandler

            val target = findTarget() ?: return@safeEventHandler

            // KillAura 已能近战处理时不再投掷
            if (stopAtKARange && KillAura.isEnabled &&
                player.distanceToSqr(target) <= KillAura.aimRange * KillAura.aimRange
            ) {
                return@safeEventHandler
            }

            // 末影珍珠贴脸会伤到自己
            if (desc.type == TrajectorySimulator.TrajectoryType.ENDER_PEARL &&
                player.distanceToSqr(target) < 9.0
            ) {
                return@safeEventHandler
            }

            val partialTick = mc.deltaTracker.getGameTimeDeltaPartialTick(true)

            // 从主手右侧出发，近似真实投掷点
            val eyePos = player.getEyePosition(partialTick).subtract(0.0, 0.1, 0.0)
            val yaw = player.getViewYRot(partialTick)
            val pitch = player.getViewXRot(partialTick)
            val lookDir = Vec3.directionFromRotation(pitch, yaw)
            val rightDir = Vec3(-lookDir.z, 0.0, lookDir.x)
            val handPos = eyePos.add(rightDir.scale(0.4))

            val bestAngle = findLaunchAngle(handPos, yaw, desc, target, partialTick) ?: return@safeEventHandler

            val angleDiff = abs(bestAngle.pitch - pitch)
            if (angleDiff > maxAngle.toFloat()) return@safeEventHandler

            if (silentRotate) {
                RotationManager.setRotations(bestAngle, 10.0, RotationPriority.High)
            }

            gameMode.useItem(player, InteractionHand.MAIN_HAND)
            SwingMode.of(true).interactSwing(InteractionHand.MAIN_HAND)
            delayCounter = throwDelay
        }
    }

    private fun NonNullContext.findTarget(): LivingEntity? {
        if (targetMode == TargetMode.KillAura) {
            val killAuraTarget = TargetManager.acquirePrimary(
                TargetRequest.of(range, 360.0f, true, true, true, true, true, true, true, true, 1)
            )
            if (killAuraTarget != null) return killAuraTarget
        }

        // 兜底：最近的存活玩家
        return level.players()
            .asSequence()
            .filter { it !== player && it.isAlive && !it.isSpectator }
            .filter { player.distanceToSqr(it) <= range * range }
            .minByOrNull { player.distanceToSqr(it) }
    }

    private fun NonNullContext.findLaunchAngle(
        handPos: Vec3,
        yaw: Float,
        desc: TrajectoryDescriptor,
        target: LivingEntity,
        partialTick: Float,
    ): Rotation? {
        var best: Rotation? = null
        var bestDist = Double.MAX_VALUE

        val playerVelocity = player.deltaMovement
        val momentum = if (desc.params.copiesPlayerVelocity) {
            Vec3(playerVelocity.x, if (player.onGround()) 0.0 else playerVelocity.y, playerVelocity.z)
        } else {
            Vec3.ZERO
        }

        // 从当前俯仰向下搜索可命中的角度
        val startPitch = player.getViewXRot(partialTick)
        val endPitch = maxOf(startPitch - 45.0f, -90.0f)

        var testPitch = startPitch
        while (testPitch >= endPitch) {
            val lookDir = Vec3.directionFromRotation(testPitch, yaw)
            val velocity = lookDir.scale(desc.params.initialVelocity).add(momentum)

            val result = TrajectorySimulator.simulate(
                handPos, velocity, desc.params, desc.type, maxTicks, player
            )

            val hit = result.hitResult
            if (hit is EntityHitResult && hit.entity === target) {
                val dist = hit.location.distanceTo(target.getEyePosition(partialTick))
                if (dist < bestDist) {
                    bestDist = dist
                    best = Rotation(yaw, testPitch)
                }
            }

            testPitch -= 1.0f
        }

        return best
    }

}
