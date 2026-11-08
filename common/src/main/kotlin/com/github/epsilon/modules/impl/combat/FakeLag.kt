package com.github.epsilon.modules.impl.combat

import com.github.epsilon.events.api.handler
import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.BlinkPacketEvent
import com.github.epsilon.events.impl.BlinkPacketEvent.Action
import com.github.epsilon.events.impl.BlinkPacketEvent.TransferOrigin
import com.github.epsilon.events.impl.GameLeftEvent
import com.github.epsilon.events.impl.PacketEvent
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.events.impl.RespawnEvent
import com.github.epsilon.kernel.jvm.*
import com.github.epsilon.managers.network.ServerboundPacketManager
import com.github.epsilon.managers.target.TargetManager
import com.github.epsilon.managers.target.TargetRequest
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import java.util.EnumSet
import java.util.Random
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Fake Lag 战斗模块。
 *
 * 通过 [BlinkPacketEvent] 投票延迟出站包来模拟网络延迟，让敌人难以命中真实位置。
 */
object FakeLag : Module("Fake Lag", Category.COMBAT) {

    private enum class Mode {
        Constant,
        Dynamic,
    }

    private enum class FlushOn {
        EntityInteract,
        BlockInteract,
        Action,
    }

    private val mode by enumSetting("Mode", Mode.Dynamic)

    private val range by doubleSetting("Range", 5.0, 1.0..10.0, 0.5)

    private val delayMin by intSetting("Delay Min", 300, 0..1000, 50)
    private val delayMax by intSetting("Delay Max", 600, 0..1000, 50)

    private val recoilTime by intSetting("Recoil Time", 250, 0..1000, 50)

    private val flushOn by multiEnumSetting("Flush On", EnumSet.allOf(FlushOn::class.java))

    private val pauseOnUse by boolSetting("Pause On Use", true)

    private var nextDelayMs = 0L
    private var lastFlushTime = 0L
    private var hasEnemyNearby = false
    private var serverPosition: Vec3 = Vec3.ZERO
    private val random = Random()

    init {
        onEnable {
            resetState()
        }

        onDisable {
            ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
            resetState()
        }

        safeEventHandler<PlayerTickEvent.Pre> {
            // 安全兜底：死亡、水中或在 GUI 中一律不延迟
            if (player.isDeadOrDying || player.isInWater || mc.gui.screen() != null) {
                ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
                return@safeEventHandler
            }
            updateEnemyCheck()
        }

        safeEventHandler<BlinkPacketEvent> { event ->
            onBlinkPacket(event)
        }

        safeEventHandler<PacketEvent.Receive> { event ->
            onPacketReceive(event.packet)
        }

        handler<GameLeftEvent> {
            ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
            resetState()
        }

        handler<RespawnEvent> {
            ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
            resetState()
        }
    }

    private fun resetState() {
        nextDelayMs = getRandomDelay()
        lastFlushTime = 0L
        serverPosition = Vec3.ZERO
        hasEnemyNearby = false
    }

    private fun NonNullContext.onBlinkPacket(event: BlinkPacketEvent) {
        if (event.origin != TransferOrigin.OUTGOING) return

        // 安全兜底：死亡 / 水中 / GUI → 交由 Manager flush
        if (player.isDeadOrDying || player.isInWater || mc.gui.screen() != null) {
            return // 动作保持 FLUSH
        }

        val packet = event.packet

        // 周期性空包：检查基于时间的窗口是否到期
        if (packet == null) {
            if (ServerboundPacketManager.isAboveTime(nextDelayMs)) {
                nextDelayMs = getRandomDelay()
                return // 动作保持 FLUSH → Manager 清空队列
            }
            // 仍在延迟窗口内——保护队列不被清空
            event.vote(Action.QUEUE)
            return
        }

        // FlushOn：这些数据包触发立即发送并重置 recoil
        if (shouldFlushOn(packet)) {
            lastFlushTime = System.currentTimeMillis()
            return // 动作保持 FLUSH
        }

        // 进食 / 使用物品期间暂停
        if (pauseOnUse && player.isUsingItem) {
            return
        }

        // Recoil 时间检查
        val now = System.currentTimeMillis()
        if (now - lastFlushTime < recoilTime) {
            return
        }

        if (mode == Mode.Dynamic) {
            if (!hasEnemyNearby) {
                return
            }

            if (serverPosition == Vec3.ZERO) {
                serverPosition = getFirstBlinkPosition()
            }

            // 延迟不再有利时立即放行
            if (serverPosition != Vec3.ZERO && shouldStopLagging()) {
                nextDelayMs = getRandomDelay()
                serverPosition = Vec3.ZERO
                return // 动作保持 FLUSH
            }
        }

        event.vote(Action.QUEUE)
    }

    private fun NonNullContext.onPacketReceive(packet: Packet<*>) {
        // 传送 / 位置同步时立即发送
        if (packet is ClientboundPlayerPositionPacket) {
            ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
            return
        }

        // 重生时立即发送
        if (packet is ClientboundRespawnPacket) {
            ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
            return
        }

        // 自身受到击退时立即发送
        if (packet is ClientboundSetEntityMotionPacket &&
            packet.id() == player.id &&
            packet.movement() != Vec3.ZERO
        ) {
            ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
            return
        }

        // 爆炸击退时立即发送
        if (packet is ClientboundExplodePacket) {
            val knockback = packet.playerKnockback()
            if (knockback.isPresent && knockback.get() != Vec3.ZERO) {
                ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
                return
            }
        }

        // 血量变化（通常是受伤）时立即发送
        if (packet is ClientboundSetHealthPacket) {
            ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
        }
    }

    private fun shouldFlushOn(packet: Packet<*>): Boolean {
        if (flushOn.contains(FlushOn.EntityInteract)) {
            if (packet is ServerboundInteractPacket ||
                packet is ServerboundAttackPacket ||
                packet is ServerboundSpectatorActionPacket
            ) {
                return true
            }
        }

        if (flushOn.contains(FlushOn.BlockInteract)) {
            if (packet is ServerboundUseItemOnPacket || packet is ServerboundUseItemPacket) {
                return true
            }
        }

        if (flushOn.contains(FlushOn.Action)) {
            if (packet is ServerboundPlayerActionPacket) {
                return true
            }
        }

        return false
    }

    private fun getFirstBlinkPosition(): Vec3 {
        val first = ServerboundPacketManager.packetQueue.peek() ?: return Vec3.ZERO
        val packet = first.packet
        if (packet is ServerboundMovePlayerPacket && packet.hasPosition()) {
            return serverReferencePosition()
        }
        return Vec3.ZERO
    }

    /** 以当前客户端位置作为服务端位置的近似基准，用于判断继续延迟是否还有利。 */
    private fun serverReferencePosition(): Vec3 = mc.player?.position() ?: Vec3.ZERO

    private fun updateEnemyCheck() {
        val enemy = TargetManager.acquirePrimary(
            TargetRequest.of(range, 360.0f, true, true, true, true, true, true, true, true, 1)
        )
        hasEnemyNearby = enemy != null
    }

    /**
     * 比较服务端位置（敌人看到的位置）与客户端真实位置：如果真实位置离敌人更近，
     * 或服务端碰撞箱已经与敌人相交，说明继续延迟只会让我们更吃亏，应立即放行。
     */
    private fun shouldStopLagging(): Boolean {
        val player = mc.player ?: return true
        if (serverPosition == Vec3.ZERO) return true

        val playerPos = player.position()
        val serverBox: AABB = player.boundingBox.move(
            serverPosition.x - playerPos.x,
            serverPosition.y - playerPos.y,
            serverPosition.z - playerPos.z
        )

        val level = mc.level ?: return true
        for (entity in level.entitiesForRendering()) {
            if (entity === player) continue
            if (entity !is LivingEntity) continue
            if (!entity.isAlive || entity.isDeadOrDying) continue

            val serverDistSq = entity.position().distanceToSqr(serverPosition)
            val clientDistSq = entity.position().distanceToSqr(playerPos)

            // 客户端位置更近 → 停止延迟
            if (clientDistSq < serverDistSq) return true

            // 服务端碰撞箱与敌人相交 → 停止延迟
            if (serverBox.intersects(entity.boundingBox)) return true
        }

        return false
    }

    private fun getRandomDelay(): Long {
        val min = minOf(delayMin, delayMax)
        val max = maxOf(delayMin, delayMax)
        return (min + random.nextInt(maxOf(1, max - min + 1))).toLong()
    }

}
