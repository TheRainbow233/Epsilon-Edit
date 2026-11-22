package com.github.epsilon.managers.network

import com.github.epsilon.Constants
import com.github.epsilon.assets.i18n.EpsilonTranslations
import com.github.epsilon.events.api.AlwaysListening
import com.github.epsilon.events.api.Cancellable
import com.github.epsilon.events.api.EventPriority
import com.github.epsilon.events.api.handler
import com.github.epsilon.events.impl.BlinkPacketEvent
import com.github.epsilon.events.impl.BlinkPacketEvent.Action
import com.github.epsilon.events.impl.BlinkPacketEvent.TransferOrigin
import com.github.epsilon.events.impl.GameLeftEvent
import com.github.epsilon.events.impl.LevelUpdateEvent
import com.github.epsilon.events.impl.PacketEvent
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.kernel.MinecraftWrapper.mc
import com.github.epsilon.kernel.network.NetworkUtils
import com.github.epsilon.managers.NotificationManager
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.handshake.ClientIntentionPacket
import net.minecraft.network.protocol.login.ServerboundHelloPacket
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer

/**
 * 集中式数据包延迟基础设施。
 *
 * 在 [PacketEvent] 的 `LOWEST` 优先级发布 [BlinkPacketEvent] 让模块投票：
 * - [Action.QUEUE]：取消原事件并把数据包放入共享队列
 * - [Action.PASS]：放行当前包但保留队列
 * - [Action.FLUSH]：清空该方向队列后放行
 *
 * 除了模块投票，本类还持有协议层的硬性规则，任何模块都无法延迟：
 * 握手 / 状态查询、聊天与命令、传送、重生、登录、断连、死亡与自身受伤音效。
 * 其中传送 / 重生 / 登录 / 断连 / 死亡会先回放对应方向的队列，避免旧位置的包落到新世界或死亡之后。
 *
 * 入站与出站使用各自的方向标记，回放时按方向分流；模块只读取 [packetQueue] 做位置追踪，
 * 不得直接增删队列元素。
 */
object ServerboundPacketManager : AlwaysListening {

    /** 共享数据包队列；模块可只读遍历用于位置追踪 / ESP。 */
    val packetQueue: ConcurrentLinkedQueue<PacketSnapshot> = ConcurrentLinkedQueue()

    init {
        handler<PacketEvent.Send>(EventPriority.LOWEST, alwaysListening = true) { event ->
            if (event.isCancelled) return@handler
            process(event.packet, TransferOrigin.OUTGOING, event)
        }
        handler<PacketEvent.Receive>(EventPriority.LOWEST, alwaysListening = true) { event ->
            if (event.isCancelled) return@handler
            process(event.packet, TransferOrigin.INCOMING, event)
        }
        handler<PlayerTickEvent.Pre>(alwaysListening = true) {
            // 心跳负责兜底回放：任何异常都不能让事件总线退订它，否则队列会永久滞留。
            try {
                heartbeat()
            } catch (e: Exception) {
                Constants.LOGGER.error("ServerboundPacketManager 心跳执行失败", e)
            }
        }
        handler<LevelUpdateEvent>(alwaysListening = true) {
            flush(TransferOrigin.OUTGOING)
        }
        handler<GameLeftEvent>(alwaysListening = true) {
            packetQueue.clear()
        }
    }

    /** 队列中仍有数据包时返回 true。 */
    fun isLagging(): Boolean = packetQueue.isNotEmpty()

    // -- 投票入口 --

    private fun process(packet: Packet<*>, origin: TransferOrigin, event: Cancellable) {
        val voteEvent = BlinkPacketEvent(packet, origin)
        voteEvent.post()
        when (voteEvent.action) {
            Action.FLUSH -> {
                flush(origin)
                return
            }

            Action.PASS -> return
            Action.QUEUE -> Unit
        }

        // 以下数据包不参与延迟。
        when {
            // 握手 / 登录 / 状态查询：连接建立阶段不能压包
            packet is ClientIntentionPacket || packet is ServerboundHelloPacket ||
                    packet is ServerboundStatusRequestPacket || packet is ServerboundPingRequestPacket -> return

            // 聊天与命令必须即时送达
            packet is ServerboundChatPacket || packet is ServerboundChatCommandPacket ||
                    packet is ClientboundSystemChatPacket || packet is ClientboundPlayerChatPacket -> return

            // 传送 / 重生 / 登录 / 断连：先回放该方向，再让包通过，避免旧队列落到新世界
            packet is ClientboundPlayerPositionPacket || packet is ClientboundRespawnPacket ||
                    packet is ClientboundLoginPacket || packet is ClientboundDisconnectPacket -> {
                flush(origin)
                return
            }

            // 自身受伤音效只影响声音，直接放行
            packet is ClientboundSoundPacket && packet.sound.value() === SoundEvents.PLAYER_HURT -> return

            // 死亡：回放该方向后放行，避免死后仍发送旧位置
            packet is ClientboundSetHealthPacket && packet.health <= 0.0f -> {
                flush(origin)
                return
            }
        }

        event.cancel()
        packetQueue.add(PacketSnapshot(packet, origin, System.currentTimeMillis()))
    }

    /** 周期心跳：分别发布空包事件，只有默认 FLUSH 才清空对应方向。 */
    private fun heartbeat() {
        val connection = mc.connection
        if (connection == null || !connection.connection.isConnected) {
            packetQueue.clear()
            return
        }
        val outgoingVote = BlinkPacketEvent(null, TransferOrigin.OUTGOING)
        outgoingVote.post()
        if (outgoingVote.action == Action.FLUSH) {
            flush(TransferOrigin.OUTGOING)
        }
        val incomingVote = BlinkPacketEvent(null, TransferOrigin.INCOMING)
        incomingVote.post()
        if (incomingVote.action == Action.FLUSH) {
            flush(TransferOrigin.INCOMING)
        }
    }

    // -- 发送 / 清理 API --

    /** 发送指定方向的全部队列数据包。 */
    fun flush(origin: TransferOrigin) = flush { it.origin == origin }

    /** 发送满足条件的数据包。 */
    fun flush(predicate: (PacketSnapshot) -> Boolean) {
        val iterator = packetQueue.iterator()
        while (iterator.hasNext()) {
            val snapshot = iterator.next()
            if (!predicate(snapshot)) continue
            iterator.remove()
            sendSnapshot(snapshot)
        }
    }

    /**
     * 发送直到 [count] 个携带位置的移动包（含此前排队在其中的非移动包）。
     */
    fun flush(count: Int) {
        if (count <= 0) return
        var sent = 0
        val iterator = packetQueue.iterator()
        while (iterator.hasNext()) {
            val snapshot = iterator.next()
            iterator.remove()
            sendSnapshot(snapshot)
            val packet = snapshot.packet
            if (packet is ServerboundMovePlayerPacket && packet.hasPosition()) {
                sent++
            }
            if (sent >= count) break
        }
    }

    /** 丢弃满足条件的数据包而不发送（换世界 / 断线清理）。 */
    fun discard(predicate: (PacketSnapshot) -> Boolean) {
        packetQueue.removeIf(predicate)
    }

    /** 丢弃某个方向的全部数据包而不发送。 */
    fun discard(origin: TransferOrigin) = discard { it.origin == origin }

    /**
     * 丢弃队列中的移动包（把玩家拉回第一次 blink 的位置），其余数据包照常发送。
     */
    fun cancel() {
        val player = mc.player ?: return
        var x = player.x
        var y = player.y
        var z = player.z
        var restored = false
        for (snapshot in packetQueue) {
            val packet = snapshot.packet
            if (packet is ServerboundMovePlayerPacket && packet.hasPosition()) {
                x = packet.getX(x)
                y = packet.getY(y)
                z = packet.getZ(z)
                if (!restored) {
                    player.setPos(Vec3(x, y, z))
                    restored = true
                }
            }
        }
        packetQueue.removeIf { snapshot ->
            if (snapshot.packet is ServerboundMovePlayerPacket) {
                true
            } else {
                sendSnapshot(snapshot)
                true
            }
        }
    }

    /** 队列中最老的包是否已等待至少 [delay] 毫秒。 */
    fun isAboveTime(delay: Long): Boolean {
        val first = packetQueue.peek() ?: return false
        return System.currentTimeMillis() - first.timestamp >= delay
    }

    /** 就地改写队列中指定类型的数据包。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Packet<*>> rewrite(type: Class<T>, action: Consumer<T>) {
        for (snapshot in packetQueue) {
            if (type.isInstance(snapshot.packet)) {
                action.accept(snapshot.packet as T)
            }
        }
    }

    // -- 内部实现 --

    private fun sendSnapshot(snapshot: PacketSnapshot) {
        if (snapshot.origin == TransferOrigin.OUTGOING) {
            try {
                NetworkUtils.sendPacketNoEvent(snapshot.packet)
            } catch (e: Exception) {
                // sendPacketNoEvent 先登记 bypass 再发送，失败时要回收，避免集合无限增长。
                NetworkUtils.bypassedPackets.remove(snapshot.packet)
                reportFailure("Outbound", e)
            }
            return
        }

        // 入站：直接交给监听器回放，绕过 Connection，避免再次触发投票。
        val connection = mc.connection ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            (snapshot.packet as Packet<ClientGamePacketListener>).handle(connection)
        } catch (e: Exception) {
            reportFailure("Inbound", e)
        }
    }

    private fun reportFailure(direction: String, exception: Exception) {
        Constants.LOGGER.error("ServerboundPacketManager 回放 {} 数据包失败", direction, exception)
        NotificationManager.error(
            "$direction Packet",
            EpsilonTranslations.Notifications.SERVERBOUND_PACKET_FLUSH_FAILED.getTranslatedName() + ": " + exception.message
        )
    }

    data class PacketSnapshot(
        val packet: Packet<*>,
        val origin: TransferOrigin,
        val timestamp: Long,
    )

}
