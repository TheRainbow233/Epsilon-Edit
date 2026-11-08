package com.github.epsilon.modules.impl.combat

import com.github.epsilon.events.api.EventPriority
import com.github.epsilon.events.api.handler
import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.*
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler
import com.github.epsilon.kernel.jvm.*
import com.github.epsilon.managers.FriendManager
import com.github.epsilon.managers.network.ServerboundPacketManager
import com.github.epsilon.managers.target.TargetManager
import com.github.epsilon.managers.target.TargetRequest
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import com.github.epsilon.modules.impl.movement.Velocity
import com.github.epsilon.settings.impl.EnumSetting
import java.awt.Color
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Predicate
import kotlin.math.roundToInt
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.game.*
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Backtrack 战斗模块。
 *
 * 入站延迟统一走 [ServerboundPacketManager] 的共享队列（与 Blink / Fake Lag 同一套投票协议）：
 * 这里只负责“是否压住目标的位置包”与“按包龄放行多少”，队列所有权和回放都在 Manager 里。
 * 与 Velocity 的互斥通过 `Velocity.ownsIncomingDelayQueue()` / `blocksBacktrack()` 判断。
 */
object Backtrack : Module("Backtrack", Category.COMBAT) {

    private enum class TargetMode {
        Attack,
        Range,
    }

    private val minRange by doubleSetting("Min Range", 2.5, 0.0..10.0, 0.1)
    private val maxRange by doubleSetting("Max Range", 5.0, 0.0..10.0, 0.1)
    private val minDelay by intSetting("Min Delay", 100, 0..1000, 10)
    private val maxDelay by intSetting("Max Delay", 150, 0..1000, 10)
    private val nextBacktrackMin by intSetting("Next Backtrack Min", 0, 0..2000, 10)
    private val nextBacktrackMax by intSetting("Next Backtrack Max", 10, 0..2000, 10)
    private val trackingBuffer by intSetting("Tracking Buffer", 500, 0..2000, 10)
    private val chance by intSetting("Chance", 50, 0..100, 1)
    private val pauseOnHurtTime by boolSetting("Pause On Hurt Time", false)
    private val hurtTime by intSetting("Hurt Time", 3, 0..10, 1, { pauseOnHurtTime })
    private val targetModeSetting: EnumSetting<TargetMode> = enumSetting("Target Mode", TargetMode.Attack)
    private val targetMode by targetModeSetting
    private val lastAttackTime by intSetting("Last Attack Time", 1000, 0..5000, 50)
    private val players by boolSetting("Players", true, { targetMode == TargetMode.Range })
    private val mobs by boolSetting("Mobs", false, { targetMode == TargetMode.Range })
    private val animals by boolSetting("Animals", false, { targetMode == TargetMode.Range })
    private val villagers by boolSetting("Villagers", false, { targetMode == TargetMode.Range })
    private val ambient by boolSetting("Ambient", false, { targetMode == TargetMode.Range })
    private val water by boolSetting("Water", false, { targetMode == TargetMode.Range })
    private val others by boolSetting("Others", false, { targetMode == TargetMode.Range })
    private val invisible by boolSetting("Invisible", true, { targetMode == TargetMode.Range })
    private val esp by boolSetting("ESP", true)
    private val sideColor by colorSetting("Side Color", Color(255, 255, 255, 35), true, { esp })
    private val outlineColor by colorSetting("Outline Color", Color(255, 255, 255, 190), true, { esp })

    @Volatile
    private var target: LivingEntity? = null

    @Volatile
    private var trackedPosition: Vec3 = Vec3.ZERO

    @Volatile
    private var backtracking = false

    @Volatile
    private var chancePassed = false

    @Volatile
    private var lastAttackAt = Long.MIN_VALUE

    @Volatile
    private var lastInRangeAt = Long.MIN_VALUE

    @Volatile
    private var nextAllowedAt = 0L

    private var currentDelay = 0

    init {
        onEnable {
            resetState(true)
            chancePassed = rollChance()
            currentDelay = randomBetween(minDelay, maxDelay)
        }

        onDisable {
            ServerboundPacketManager.flush(BlinkPacketEvent.TransferOrigin.INCOMING)
            resetState(false)
        }

        safeEventHandler<AttackEntityEvent> { event ->
            val now = System.currentTimeMillis()
            lastAttackAt = now
            chancePassed = rollChance()

            if (targetMode == TargetMode.Attack) {
                val living = event.entity as? LivingEntity
                if (living != null) {
                    processTarget(living, now)
                }
            }
        }

        safeEventHandler<ClientTickEvent.Pre>(EventPriority.HIGH) {
            val now = System.currentTimeMillis()
            if (targetMode == TargetMode.Range) {
                val rangeTarget = findRangeTarget()
                if (rangeTarget == null) {
                    clearAndReplay(true)
                } else {
                    processTarget(rangeTarget, now)
                }
            }

            if (Velocity.ownsIncomingDelayQueue()) {
                backtracking = false
                ServerboundPacketManager.flush(BlinkPacketEvent.TransferOrigin.INCOMING)
                return@safeEventHandler
            }

            backtracking = target?.let { shouldBacktrack(it, now) } ?: false
            if (backtracking) {
                // 按包龄释放：早于 now - currentDelay 的入站包回放给客户端
                val cutoff = now - currentDelay
                ServerboundPacketManager.flush { snapshot ->
                    snapshot.origin == BlinkPacketEvent.TransferOrigin.INCOMING && snapshot.timestamp <= cutoff
                }
            } else if (hasQueuedIncoming()) {
                clearAndReplay(true)
            }

            if (!hasQueuedIncoming()) {
                currentDelay = randomBetween(minDelay, maxDelay)
            }
        }

        safeEventHandler<BlinkPacketEvent> { event ->
            onBlinkPacket(event)
        }

        handler<Render3DEvent> {
            val currentTarget = target ?: return@handler
            if (!esp || !currentTarget.isAlive) return@handler
            val box = currentTarget.boundingBox.move(trackedPosition.subtract(currentTarget.position()))
            Render3DScheduler.addFilledBox(box, sideColor)
            Render3DScheduler.addOutlineBox(box, outlineColor, 1.5f)
        }

        handler<GameLeftEvent> {
            discardIncoming()
            resetState(false)
        }

        handler<LevelUpdateEvent> {
            discardIncoming()
            resetState(false)
        }
    }

    override fun getInfo(): String? {
        return if (backtracking && hasQueuedIncoming()) "$currentDelay ms" else targetModeSetting.translatedValue
    }

    fun isLagging(): Boolean = isEnabled && hasQueuedIncoming()

    // -- 目标选择 --

    private fun NonNullContext.processTarget(enemy: LivingEntity, now: Long) {
        if (!shouldBacktrack(enemy, now)) return

        if (enemy !== target) {
            ServerboundPacketManager.flush(BlinkPacketEvent.TransferOrigin.INCOMING)
            target = enemy
            trackedPosition = enemy.positionCodec.base
        }
        backtracking = true
    }

    private fun NonNullContext.shouldBacktrack(candidate: LivingEntity, now: Long): Boolean {
        if (!isValidTarget(candidate)) return false

        val distance = candidate.boundingBox.distanceToSqr(player.eyePosition)
        val min = minOf(minRange, maxRange)
        val max = maxOf(minRange, maxRange)
        val inRange = distance >= min * min && distance <= max * max
        if (inRange) {
            lastInRangeAt = now
        }

        val withinTrackingBuffer = lastInRangeAt != Long.MIN_VALUE && now - lastInRangeAt <= trackingBuffer
        val attackedRecently = lastAttackAt != Long.MIN_VALUE && now - lastAttackAt <= lastAttackTime

        return (inRange || withinTrackingBuffer) &&
                player.tickCount > 10 &&
                chancePassed &&
                now >= nextAllowedAt &&
                attackedRecently &&
                !Velocity.blocksBacktrack() &&
                (!pauseOnHurtTime || candidate.hurtTime < hurtTime)
    }

    private fun NonNullContext.isValidTarget(candidate: LivingEntity): Boolean {
        if (candidate === player || !candidate.isAlive || candidate.isDeadOrDying) return false
        if (candidate.level() !== level || AntiBot.isBot(candidate)) return false
        if (TargetManager.isSameTeam(candidate)) return false
        return candidate !is Player || !FriendManager.isFriend(candidate)
    }

    private fun NonNullContext.findRangeTarget(): LivingEntity? {
        val min = minOf(minRange, maxRange)
        val max = maxOf(minRange, maxRange)
        val minSquared = min * min
        val request = TargetRequest(
            range = max,
            fov = 360.0f,
            player = players,
            mob = mobs,
            animal = animals,
            villager = villagers,
            ambient = ambient,
            water = water,
            others = others,
            invisible = invisible,
            allowFriends = false,
            extraFilter = Predicate { entity -> entity.boundingBox.distanceToSqr(player.eyePosition) >= minSquared },
            maxTargets = 1,
        )
        return TargetManager.acquirePrimary(request)
    }

    // -- 入站投票 --

    private fun NonNullContext.onBlinkPacket(event: BlinkPacketEvent) {
        if (event.origin != BlinkPacketEvent.TransferOrigin.INCOMING) return
        if (Velocity.ownsIncomingDelayQueue()) return

        val packet = event.packet
        val shouldCancel = shouldCancelPackets()
        val hasQueued = hasQueuedIncoming()

        // 周期空包：窗口内保护队列，窗口到期让默认 FLUSH 生效
        if (packet == null) {
            if (shouldCancel || hasQueued) {
                event.vote(BlinkPacketEvent.Action.PASS)
            }
            return
        }

        if (!hasQueued && !shouldCancel) return

        if (packet is ClientboundDisconnectPacket) {
            clearAndReplay(false)
            return
        }

        if (packet is ClientboundPlayerPositionPacket ||
            (packet is ClientboundSetHealthPacket && packet.health <= 0.0f)
        ) {
            clearAndReplay(true)
            return
        }

        if (isPassThrough(packet)) {
            event.vote(BlinkPacketEvent.Action.PASS)
            return
        }

        val position = trackTargetPosition(packet)
        if (position != null && isServerPositionCloser(position)) {
            // 服务端真实位置更近：立刻回放，否则打不到人
            event.vote(BlinkPacketEvent.Action.FLUSH)
            return
        }

        event.vote(BlinkPacketEvent.Action.QUEUE)
    }

    private fun NonNullContext.shouldCancelPackets(): Boolean {
        val currentTarget = target ?: return false
        return currentTarget.isAlive && shouldBacktrack(currentTarget, System.currentTimeMillis())
    }

    private fun hasQueuedIncoming(): Boolean {
        for (snapshot in ServerboundPacketManager.packetQueue) {
            if (snapshot.origin == BlinkPacketEvent.TransferOrigin.INCOMING) return true
        }
        return false
    }

    private fun isPassThrough(packet: Packet<*>): Boolean {
        if (packet is ClientboundSystemChatPacket || packet is ClientboundPlayerChatPacket) return true
        return packet is ClientboundSoundPacket && packet.sound.value() === SoundEvents.PLAYER_HURT
    }

    // -- 位置追踪 --

    private fun trackTargetPosition(packet: Packet<*>): Vec3? {
        val currentTarget = target ?: return null
        var position: Vec3? = trackSinglePacket(packet)

        if (packet is BundlePacket<*>) {
            for (subPacket in packet.subPackets()) {
                val subPosition = trackSinglePacket(subPacket)
                if (subPosition != null) position = subPosition
            }
        }
        return position
    }

    private fun trackSinglePacket(packet: Packet<*>): Vec3? {
        val currentTarget = target ?: return null
        val position: Vec3 = when {
            packet is ClientboundMoveEntityPacket && packet.getEntity(mc.level ?: return null) === currentTarget ->
                decodeMovementDelta(trackedPosition, packet.positionDelta)

            packet is ClientboundTeleportEntityPacket && packet.id() == currentTarget.id ->
                packet.change().position()

            packet is ClientboundEntityPositionSyncPacket && packet.id() == currentTarget.id ->
                packet.position().endPosition()

            else -> return null
        }

        trackedPosition = position
        return position
    }

    private fun NonNullContext.isServerPositionCloser(serverPosition: Vec3): Boolean {
        val currentTarget = target ?: return false
        val currentBox = currentTarget.boundingBox
        val serverBox = currentBox.move(serverPosition.subtract(currentTarget.position()))
        return serverBox.distanceToSqr(player.eyePosition) < currentBox.distanceToSqr(player.eyePosition)
    }

    // -- 状态管理 --

    private fun clearAndReplay(applyCooldown: Boolean) {
        ServerboundPacketManager.flush(BlinkPacketEvent.TransferOrigin.INCOMING)
        clearTarget(applyCooldown)
    }

    private fun discardIncoming() {
        ServerboundPacketManager.discard(BlinkPacketEvent.TransferOrigin.INCOMING)
    }

    private fun resetState(resetTimers: Boolean) {
        target = null
        trackedPosition = Vec3.ZERO
        backtracking = false
        lastInRangeAt = Long.MIN_VALUE
        if (resetTimers) {
            lastAttackAt = Long.MIN_VALUE
            nextAllowedAt = 0L
        }
    }

    private fun clearTarget(applyCooldown: Boolean) {
        if (target != null && applyCooldown) {
            nextAllowedAt = System.currentTimeMillis() + randomBetween(nextBacktrackMin, nextBacktrackMax)
        }
        target = null
        trackedPosition = Vec3.ZERO
        backtracking = false
        lastInRangeAt = Long.MIN_VALUE
    }

    private fun rollChance(): Boolean = ThreadLocalRandom.current().nextInt(100) < chance

    private fun randomBetween(first: Int, second: Int): Int {
        val min = minOf(first, second)
        val max = maxOf(first, second)
        return if (min == max) min else ThreadLocalRandom.current().nextInt(min, max + 1)
    }

    private fun decodeRelative(base: Vec3, x: Long, y: Long, z: Long): Vec3 {
        val decodedX = if (x == 0L) base.x else ((base.x * 4096.0).roundToInt() + x) / 4096.0
        val decodedY = if (y == 0L) base.y else ((base.y * 4096.0).roundToInt() + y) / 4096.0
        val decodedZ = if (z == 0L) base.z else ((base.z * 4096.0).roundToInt() + z) / 4096.0
        return Vec3(decodedX, decodedY, decodedZ)
    }

    private fun decodeMovementDelta(base: Vec3, delta: VecDelta): Vec3 {
        var position = base
        if (delta is VecDelta.Linear) {
            return decodeRelative(position, delta.xa().toLong(), delta.ya().toLong(), delta.za().toLong())
        }
        if (delta is VecDelta.Stepped) {
            for (step in delta.steps()) {
                position = decodeRelative(position, step.xa().toLong(), step.ya().toLong(), step.za().toLong())
            }
        }
        return position
    }

}
