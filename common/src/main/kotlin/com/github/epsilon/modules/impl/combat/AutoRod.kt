package com.github.epsilon.modules.impl.combat

import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.kernel.jvm.NonNullContext
import com.github.epsilon.kernel.jvm.runSafe
import com.github.epsilon.managers.RotationManager
import com.github.epsilon.managers.target.TargetManager
import com.github.epsilon.managers.target.TargetRequest
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import com.github.epsilon.utils.player.InvUtils.findInHotbar
import com.github.epsilon.utils.player.InvUtils.swap
import com.github.epsilon.utils.player.InvUtils.swapBack
import com.github.epsilon.utils.player.SwingMode
import com.github.epsilon.utils.rotation.Priority
import com.github.epsilon.utils.rotation.RotationUtils.getEyeDistanceToEntity
import com.github.epsilon.utils.rotation.RotationUtils.getRotationsToEntity
import java.util.Random
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.phys.Vec3

/**
 * Auto Rod：自动对附近目标投掷鱼竿，用于连击中的击退控制。
 *
 * 参考 LiquidBounce 的 ModuleAutoRod。
 */
object AutoRod : Module("Auto Rod", Category.COMBAT) {

    private enum class State {
        IDLE,
        CAST,
        WAITING,
        PULL,
        COOLDOWN,
    }

    private val range by doubleSetting("Range", 5.0, 2.0..10.0, 0.5)
    private val hitTimeout by intSetting("Hit Timeout", 30, 5..200, 5)
    private val pullOnOutOfRange by boolSetting("Pull On Out Of Range", true)
    private val cooldownMin by intSetting("Cooldown Min", 4, 1..50, 1)
    private val cooldownMax by intSetting("Cooldown Max", 8, 1..50, 1)
    private val chance by intSetting("Chance", 100, 0..100, 1)
    private val swingHand by boolSetting("Swing Hand", true)

    private var state = State.IDLE
    private var tickCounter = 0
    private var cooldownTicks = 0
    private var target: LivingEntity? = null
    private val random = Random()

    init {
        onEnable {
            resetState()
        }

        onDisable {
            // 收回仍在场上的浮标
            runSafe {
                if (target != null && hasActiveBobber()) {
                    pullRod()
                }
            }
            resetState()
        }

        safeEventHandler<PlayerTickEvent.Pre> {
            when (state) {
                State.IDLE -> handleIdle()
                State.CAST -> handleCast()
                State.WAITING -> handleWaiting()
                State.PULL -> handlePull()
                State.COOLDOWN -> handleCooldown()
            }
        }
    }

    private fun resetState() {
        state = State.IDLE
        tickCounter = 0
        cooldownTicks = 0
        target = null
    }

    private fun NonNullContext.handleIdle() {
        if (random.nextInt(100) >= chance) return

        val candidate = TargetManager.acquirePrimary(
            TargetRequest.of(range, 360.0f, true, true, true, true, true, true, true, true, 1)
        ) ?: return
        if (!candidate.isAlive || candidate.isDeadOrDying) return

        target = candidate

        // 已有浮标在场时不重复投掷
        if (hasActiveBobber()) return
        if (!findInHotbar { it.item is FishingRodItem }.found()) return
        if (!player.hasLineOfSight(candidate)) return

        state = State.CAST
        tickCounter = 0
    }

    private fun NonNullContext.handleCast() {
        val current = target
        if (current == null || !current.isAlive) {
            state = State.IDLE
            return
        }

        RotationManager.setRotations(getRotationsToEntity(current), 10.0, Priority.High)

        val rod = findInHotbar { it.item is FishingRodItem }
        if (!rod.found()) {
            state = State.IDLE
            return
        }

        val slot = rod.slot
        val hand = rod.getHand()
        if (slot != 40 && slot != player.inventory.selectedSlot) {
            swap(slot, true)
        }

        gameMode.useItem(player, hand)
        if (swingHand) {
            SwingMode.of(swingHand).interactSwing(hand)
        }

        state = State.WAITING
        tickCounter = 0
    }

    private fun NonNullContext.handleWaiting() {
        tickCounter++

        val current = target
        if (current == null || !current.isAlive) {
            state = State.IDLE
            return
        }

        val bobber = getBobber()
        if (bobber == null) {
            // 浮标消失（被服务端收回或撞地形）
            state = State.COOLDOWN
            cooldownTicks = getRandomCooldown()
            return
        }

        // 已勾中实体
        if (bobber.hookedIn != null) {
            state = State.PULL
            return
        }

        // 浮标停止移动（撞地形）
        val movement = bobber.knownMovement
        if (movement != null && movement == Vec3.ZERO) {
            state = State.PULL
            return
        }

        // 目标离开范围
        if (pullOnOutOfRange && getEyeDistanceToEntity(current) > range) {
            state = State.PULL
            return
        }

        if (tickCounter >= hitTimeout) {
            state = State.PULL
        }
    }

    private fun NonNullContext.handlePull() {
        pullRod()
        state = State.COOLDOWN
        cooldownTicks = getRandomCooldown()
    }

    private fun NonNullContext.handleCooldown() {
        tickCounter++
        if (tickCounter >= cooldownTicks) {
            swapBack()
            state = State.IDLE
            tickCounter = 0
        }
    }

    private fun NonNullContext.hasActiveBobber(): Boolean = getBobber() != null

    private fun NonNullContext.getBobber(): FishingHook? {
        for (entity in level.entitiesForRendering()) {
            if (entity is FishingHook && entity.playerOwner === player) {
                return entity
            }
        }
        return null
    }

    private fun NonNullContext.pullRod() {
        val rod = findInHotbar { it.item is FishingRodItem }
        if (!rod.found()) return

        val slot = rod.slot
        val hand = rod.getHand()
        if (slot != 40 && slot != player.inventory.selectedSlot) {
            swap(slot, true)
        }

        gameMode.useItem(player, hand)
        if (swingHand) {
            SwingMode.of(swingHand).interactSwing(hand)
        }
    }

    private fun getRandomCooldown(): Int {
        val min = minOf(cooldownMin, cooldownMax)
        val max = maxOf(cooldownMin, cooldownMax)
        return min + random.nextInt(maxOf(1, max - min + 1))
    }

}
