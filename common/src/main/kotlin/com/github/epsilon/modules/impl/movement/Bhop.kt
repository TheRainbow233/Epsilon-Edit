package com.github.epsilon.modules.impl.movement

import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.MoveEvent
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.kernel.MinecraftWrapper.mc
import com.github.epsilon.kernel.jvm.NonNullContext
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bhop（8 Tick Strafe 模式）。
 *
 * 用一个 8 tick 的跳跃弧线维持水平速度：起跳 tick 给一个 0.48475 系数的水平速度，
 * 之后在固定的 motionY 指纹上补高/压低弧线并在下落段二段 strafe，循环跳。
 */
object Bhop : Module("Bhop", Category.MOVEMENT) {

    private val speed by doubleSetting("Speed", 1.0, 0.9..1.5, 0.01)
    private val offset by doubleSetting("Offset", 0.0, -2.0..2.0, 0.05)
    private val strafeDecay by doubleSetting("Strafe Decay", 0.03, 0.0..0.5, 0.01)
    private val autoJump by boolSetting("Auto Jump", false)
    private val disableWhileSneaking by boolSetting("Disable While Sneaking", true)

    private var falling = false
    private var jumps = 0
    private var pendingStrafe: Double? = null

    init {
        onEnable {
            falling = false
            jumps = 0
            pendingStrafe = null
        }

        onDisable {
            falling = false
            jumps = 0
            pendingStrafe = null
        }

        safeEventHandler<PlayerTickEvent.Pre> {
            pendingStrafe = null
            if (!canBhop()) return@safeEventHandler

            val moving = player.isMoving
            if (!moving && !autoJump) return@safeEventHandler

            if (player.onGround()) {
                handleGround(moving)
            } else {
                handleAir(moving)
            }
        }

        safeEventHandler<MoveEvent> { event ->
            val strafeSpeed = pendingStrafe ?: return@safeEventHandler
            pendingStrafe = null

            val move = player.input.moveVector
            if (move.x == 0.0f && move.y == 0.0f) return@safeEventHandler

            val yaw = Math.toRadians(player.yRot.toDouble())
            val sin = sin(yaw)
            val cos = cos(yaw)
            val length = hypot(move.x.toDouble(), move.y.toDouble())

            event.x = (move.x * cos - move.y * sin) / length * strafeSpeed
            event.z = (move.y * cos + move.x * sin) / length * strafeSpeed
            event.cancel()
        }
    }

    /** 从“静止在地面”的竖直速度起跳，保证后面的 motionY 指纹序列对得上。 */
    private fun NonNullContext.handleGround(moving: Boolean) {
        falling = false

        if (player.deltaMovement.y > 1.0E-4) {
            return
        }

        player.jumpFromGround()

        if (!moving) return

        jumps++
        var d = 0.48475 * speed + offset / 100.0 - Math.random() * 1.0E-4
        if (player.input.moveVector.y <= 0.0f) {
            d += 0.03
        }
        if (jumps == 1) {
            d *= 0.95
        }
        pendingStrafe = d
    }

    private fun NonNullContext.handleAir(moving: Boolean) {
        if (!moving) return
        if (player.horizontalCollision) return

        falling = if (Scaffold.isEnabled) {
            true
        } else {
            val height = heightAboveGround()
            falling || height > 1.25 || height == -1.0
        }
        if (falling) return

        val d = player.deltaMovement.horizontal().length()
        when (motionYDecimals()) {
            3332 -> {
                player.deltaMovement = player.deltaMovement.add(0.0, 0.0568, 0.0)
                pendingStrafe = d
            }

            2193 -> {
                player.deltaMovement = player.deltaMovement.add(0.0, -0.13, 0.0)
            }

            91 -> {
                val idOffset = abs(player.id).let { if (it > 999) it + 213 else it * 99 }
                player.deltaMovement = player.deltaMovement.add(0.0, -(0.2 + idOffset * 1.0E-9), 0.0)
            }

            -3385 -> {
                player.deltaMovement = player.deltaMovement.add(0.0, 0.08, 0.0)
                pendingStrafe = d * (1.0 - strafeDecay)
            }

            -4035, -3318 -> {
                pendingStrafe = d
            }
        }
    }

    private fun NonNullContext.canBhop(): Boolean {
        if (player.isSpectator || player.abilities.flying || player.isFallFlying) return false
        if (player.isInWater || player.isInLava || player.isPassenger || player.onClimbable()) return false
        if (disableWhileSneaking && player.isShiftKeyDown()) return false
        if (Scaffold.isEnabled) return false
        if (mc.gui.screen() != null) return false
        return true
    }

    /** 从脚下向下 100 格拉射线，返回离地高度；没有地面返回 -1。 */
    private fun NonNullContext.heightAboveGround(): Double {
        val from = player.position()
        val to = from.add(0.0, -100.0, 0.0)
        val hit = level.clip(
            ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
        )
        if (hit.type != HitResult.Type.BLOCK) return -1.0
        return player.y - hit.location.y
    }

    private fun NonNullContext.motionYDecimals(): Int = (player.deltaMovement.y % 10.0 * 10000.0).roundToInt()

}
