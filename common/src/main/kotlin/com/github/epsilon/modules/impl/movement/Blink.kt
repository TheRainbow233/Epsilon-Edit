package com.github.epsilon.modules.impl.movement

import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.BlinkPacketEvent
import com.github.epsilon.events.impl.BlinkPacketEvent.Action
import com.github.epsilon.events.impl.BlinkPacketEvent.TransferOrigin
import com.github.epsilon.events.impl.Render3DEvent
import com.github.epsilon.events.impl.SendPositionEvent
import com.github.epsilon.kernel.jvm.NonNullContext
import com.github.epsilon.kernel.jvm.runSafe
import com.github.epsilon.managers.network.ServerboundPacketManager
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import com.mojang.authlib.GameProfile
import net.minecraft.client.gui.screens.RecoverWorldDataScreen
import net.minecraft.client.player.RemotePlayer
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.Entity
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Blink 移动模块。
 *
 * 出站包通过 [BlinkPacketEvent] 投票压入共享队列；释放节奏由空包心跳控制，
 * 服务端位置由队列中的移动包累积得到，供 Fake Player 渲染使用。
 */
object Blink : Module("Blink", Category.MOVEMENT) {

    private val tick by doubleSetting("Tick", 30.0, 5.0..200.0, 1.0)
    private val slowRelease by boolSetting("Slow Release", true)
    private val slowMove by boolSetting("Slow Move", false)
    private val slowMoveTick by doubleSetting("Slow Move Tick", 5.0, 2.0..5.0, 1.0, { slowMove })
    private val fakePlayer by boolSetting("Fake Player", false)

    private var localPlayer: RemotePlayer? = null

    // 服务端位置（由队列中的移动包累积得到）
    private var serverX = 0.0
    private var serverY = 0.0
    private var serverZ = 0.0
    private var prevServerX = 0.0
    private var prevServerY = 0.0
    private var prevServerZ = 0.0
    private var serverYRot = 0.0f
    private var serverXRot = 0.0f
    private var serverYHeadRot = 0.0f
    private var prevServerYRot = 0.0f
    private var prevServerXRot = 0.0f
    private var prevServerYHeadRot = 0.0f
    private var lastLerpX = 0.0
    private var lastLerpY = 0.0
    private var lastLerpZ = 0.0

    init {
        onEnable {
            runSafe {
                resetServerPosition()
                if (fakePlayer) {
                    val fake = RemotePlayer(
                        level,
                        GameProfile(UUID.nameUUIDFromBytes("".toByteArray(StandardCharsets.UTF_8)), "")
                    )
                    fake.id = -1337
                    fake.copyPosition(player)
                    fake.yRot = player.yRot
                    fake.xRot = player.xRot
                    fake.yHeadRot = player.yHeadRot
                    fake.health = player.health
                    fake.absorptionAmount = player.absorptionAmount
                    level.addEntity(fake)
                    localPlayer = fake
                }
            }
        }

        onDisable {
            runSafe {
                if (fakePlayer) {
                    localPlayer?.let { level.removeEntity(it.id, Entity.RemovalReason.DISCARDED) }
                }
            }
            localPlayer = null
            ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
        }

        safeEventHandler<BlinkPacketEvent> { event ->
            onBlinkPacket(event)
        }

        safeEventHandler<Render3DEvent> {
            val fake = localPlayer
            if (fake != null && fakePlayer) {
                updateServerPosition()
                val partialTick = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
                val lerpX = prevServerX + (serverX - prevServerX) * partialTick
                val lerpY = prevServerY + (serverY - prevServerY) * partialTick
                val lerpZ = prevServerZ + (serverZ - prevServerZ) * partialTick

                fake.xOld = lastLerpX
                fake.yOld = lastLerpY
                fake.zOld = lastLerpZ
                fake.setPos(lerpX, lerpY, lerpZ)
                lastLerpX = lerpX
                lastLerpY = lerpY
                lastLerpZ = lerpZ

                val lerpYRot = prevServerYRot + (serverYRot - prevServerYRot) * partialTick
                val lerpXRot = prevServerXRot + (serverXRot - prevServerXRot) * partialTick
                val lerpYHeadRot = prevServerYHeadRot + (serverYHeadRot - prevServerYHeadRot) * partialTick
                fake.yRot = lerpYRot
                fake.xRot = lerpXRot
                fake.yHeadRot = lerpYHeadRot
                fake.yBodyRot = lerpYRot
            }
        }

        safeEventHandler<SendPositionEvent> {
            if (mc.gui.screen() is RecoverWorldDataScreen) {
                isEnabled = false
            }
        }
    }

    private fun NonNullContext.onBlinkPacket(event: BlinkPacketEvent) {
        if (event.origin != TransferOrigin.OUTGOING) return

        // 周期空包：由模块决定释放多少，避免 Manager 默认 FLUSH 清空整个队列
        if (event.packet == null) {
            val queued = queuedMovePackets()

            if (queued > tick) {
                if (slowRelease) {
                    releaseTick()
                } else {
                    releaseAll()
                }
                event.vote(Action.PASS)
                return
            }

            if (slowMove && queued > 0 && player.tickCount % slowMoveTick.toInt() == 0) {
                releaseTick()
            }

            // 队列里只剩非移动包且等待过久时兜底清空，避免无移动时长期占用
            if (queued == 0 && ServerboundPacketManager.isAboveTime(2000L)) {
                return // 保持 FLUSH
            }

            event.vote(Action.PASS)
            return
        }

        event.vote(Action.QUEUE)
    }

    private fun NonNullContext.resetServerPosition() {
        serverX = player.x
        prevServerX = serverX
        serverY = player.y
        prevServerY = serverY
        serverZ = player.z
        prevServerZ = serverZ
        serverYRot = player.yRot
        prevServerYRot = serverYRot
        serverXRot = player.xRot
        prevServerXRot = serverXRot
        serverYHeadRot = player.yHeadRot
        prevServerYHeadRot = serverYHeadRot
    }

    /** 通过累积队列中所有移动包，重算服务端看到的位置与朝向。 */
    private fun NonNullContext.updateServerPosition() {
        prevServerX = serverX
        prevServerY = serverY
        prevServerZ = serverZ
        prevServerYRot = serverYRot
        prevServerXRot = serverXRot
        prevServerYHeadRot = serverYHeadRot

        var x = player.x
        var y = player.y
        var z = player.z
        var yRot = player.yRot
        var xRot = player.xRot
        var yHeadRot = player.yHeadRot

        for (snapshot in ServerboundPacketManager.packetQueue) {
            val move = snapshot.packet as? ServerboundMovePlayerPacket ?: continue
            x = move.getX(x)
            y = move.getY(y)
            z = move.getZ(z)
            yRot = move.getYRot(yRot)
            xRot = move.getXRot(xRot)
            if (move.hasRotation()) {
                yHeadRot = move.getYRot(yHeadRot)
            }
        }

        serverX = x
        serverY = y
        serverZ = z
        serverYRot = yRot
        serverXRot = xRot
        serverYHeadRot = yHeadRot
    }

    private fun queuedMovePackets(): Int {
        var count = 0
        for (snapshot in ServerboundPacketManager.packetQueue) {
            if (snapshot.packet is ServerboundMovePlayerPacket) count++
        }
        return count
    }

    private fun NonNullContext.releaseTick() {
        updateServerPosition()
        ServerboundPacketManager.flush(1)
    }

    private fun releaseAll() {
        ServerboundPacketManager.flush(TransferOrigin.OUTGOING)
    }

}
