package com.github.epsilon.modules.impl.player

import com.github.epsilon.assets.i18n.EpsilonTranslations
import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.BlinkPacketEvent
import com.github.epsilon.events.impl.BlinkPacketEvent.Action
import com.github.epsilon.events.impl.BlinkPacketEvent.TransferOrigin
import com.github.epsilon.events.impl.PacketEvent
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.kernel.gameplay.movement.FallingPlayer
import com.github.epsilon.kernel.jvm.NonNullContext
import com.github.epsilon.kernel.jvm.runSafe
import com.github.epsilon.kernel.jvm.runSafeOrFalse
import com.github.epsilon.kernel.network.NetworkUtils
import com.github.epsilon.kernel.time.TimerUtils
import com.github.epsilon.managers.NotificationManager
import com.github.epsilon.managers.network.ServerboundPacketManager
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import com.github.epsilon.modules.impl.movement.Flight
import com.github.epsilon.modules.impl.movement.Scaffold
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.phys.Vec3

/**
 * Anti Void，两种模式：
 *
 * **Blink**（参考 LiquidBounce AntiVoidBlinkMode）：
 * 1. 在空中、脚下到虚空线之间没有任何方块时，用 [FallingPlayer] 沿当前动量向前模拟
 *    [predictTicks] tick；如果模拟路径不会撞到任何方块，说明会直接掉进虚空；
 * 2. 通过 [BlinkPacketEvent] 压住出站移动包，服务端停留在最后看到的安全位置；
 * 3. 队列里已经有移动包后，取消 Blink（丢弃移动包）并把客户端拉回最近一次站在地面上的位置。
 *
 * **Callback**：
 * 1. 脚下到虚空线之间没有方块时扣住出站移动包；
 * 2. 超过 [pullbackTime] 后向服务端发一个回到最后地面位置的 Pos 包；
 * 3. 收到服务端位置同步或重新踩到地面时丢弃旧移动包。
 */
object AntiVoid : Module("Anti Void", Category.PLAYER) {

    private enum class Mode {
        Blink,
        Callback,
    }

    private val mode by enumSetting("Mode", Mode.Blink)

    /** 虚空判定线：该 Y 以下视为虚空。 */
    private val voidLevel by intSetting("Void Level", 0, -256..320, 1)

    /** Blink 模式向前模拟的 tick 数。 */
    private val predictTicks by intSetting("Predict Ticks", 20, 1..40, 1, { mode == Mode.Blink })

    /** Callback 模式：在虚空里扣包多久后向服务端回传最后地面位置。 */
    private val pullbackTime by intSetting("Pullback Time", 1500, 1000..2000, 100, { mode == Mode.Callback })

    private var likelyFalling = false
    private var rescuePosition: Vec3? = null

    // Callback mode state
    private var lastGround: Vec3? = null
    private var callbackLagging = false
    private val callbackTimer = TimerUtils()

    private val isExempt: Boolean
        get() {
            val player = mc.player ?: return true
            return player.isDeadOrDying ||
                    player.isSpectator ||
                    player.isFallFlying ||
                    // 注意：不能用 abilities.mayfly —— 创造模式即使没在飞它也是 true，
                    // 会让 AntiVoid 在整个创造模式里静默失效。只豁免真正处于飞行状态。
                    player.abilities.flying ||
                    player.isInWater ||
                    player.isInLava ||
                    Scaffold.isEnabled ||
                    Flight.isEnabled
        }

    init {
        onEnable { resetState() }
        onDisable { resetState() }

        safeEventHandler<PlayerTickEvent.Pre> {
            if (player.tickCount <= 20) {
                return@safeEventHandler
            }

            if (mode == Mode.Callback) {
                handleCallback()
                return@safeEventHandler
            }

            likelyFalling = runSafeOrFalse { isPredictingFall() }

            if (!likelyFalling) {
                // 只记录“站在地面”的位置，避免把半空中的跳跃点当成安全救援点。
                if (player.onGround()) {
                    rescuePosition = player.position()
                }
                return@safeEventHandler
            }

            if (isExempt) {
                return@safeEventHandler
            }

            val rescue = rescuePosition ?: return@safeEventHandler

            // 当前还站在有方块的地方（只是预测会掉下去）时先只扣包，不救援。
            if (hasGroundBelowToVoidLine()) {
                return@safeEventHandler
            }

            // 预测会掉进虚空：先让 Blink 队列攒一个移动包，再取消并拉回安全点。
            if (!ServerboundPacketManager.isLagging()) {
                return@safeEventHandler
            }

            runSafe {
                ServerboundPacketManager.cancel()
                player.setPos(rescue.x, rescue.y, rescue.z)
                player.deltaMovement = Vec3.ZERO
                player.fallDistance = 0.0
                rescuePosition = null
                NotificationManager.warning(
                    translatedName,
                    EpsilonTranslations.Notifications.ANTIVOID_RESCUE.getTranslatedName(),
                    this@AntiVoid.hashCode()
                )
            }
        }

        // 服务端把玩家拉回（S08）：旧移动包全部作废。
        safeEventHandler<PacketEvent.Receive> { event ->
            if (mode == Mode.Callback && event.packet is ClientboundPlayerPositionPacket) {
                ServerboundPacketManager.discard(TransferOrigin.OUTGOING)
                callbackLagging = false
                callbackTimer.reset()
            }
        }

        // 预测到会掉进虚空时压住出站包（包括心跳空包），服务端停在最后位置。
        safeEventHandler<BlinkPacketEvent> { event ->
            if (event.origin != TransferOrigin.OUTGOING) {
                return@safeEventHandler
            }

            if (mode == Mode.Callback) {
                if (!callbackLagging) {
                    return@safeEventHandler
                }
                // 只压移动包；其它包放行但保留队列（PASS），避免默认 FLUSH 把移动包一起发出去。
                if (event.packet is ServerboundMovePlayerPacket) {
                    event.vote(Action.QUEUE)
                } else {
                    event.vote(Action.PASS)
                }
                return@safeEventHandler
            }

            if (requiresLag()) {
                event.vote(Action.QUEUE)
            }
        }
    }

    private fun requiresLag(): Boolean =
        mc.player != null && likelyFalling && rescuePosition != null && !isExempt

    /**
     * Callback 模式：
     * - 脚下到虚空线之间没有方块时，扣住出站移动包；
     * - 超过 [pullbackTime] 后直接向服务端发一个“最后地面位置”的 Pos 包（绕过事件，不重复入队）；
     * - 回到有地面的位置后丢弃扣下的包。
     */
    private fun NonNullContext.handleCallback() {
        if (isExempt) {
            return
        }

        if (hasGroundBelowToVoidLine()) {
            lastGround = player.position()
            if (callbackLagging) {
                // 不要补发掉进虚空时的旧移动包，否则服务端会被拉回半空；直接丢弃。
                ServerboundPacketManager.discard(TransferOrigin.OUTGOING)
                callbackLagging = false
            }
            callbackTimer.reset()
            return
        }

        callbackLagging = true
        if (!callbackTimer.passedMillise(pullbackTime.toLong())) {
            return
        }

        val pos = lastGround ?: player.position()
        runSafe {
            NetworkUtils.sendPacketNoEvent(
                ServerboundMovePlayerPacket.Pos(
                    Vec3(pos.x, pos.y - 1.0, pos.z),
                    true,
                    player.horizontalCollision
                )
            )
        }
        // 回传后重新计时，避免每个 tick 重复发同一个位置包。
        callbackTimer.reset()
    }

    /**
     * 是否即将掉进虚空（对照 LB 的 isPredictingFall）：
     * 用当前输入和动量模拟未来 [predictTicks] tick，任意一个“正在下降”的模拟位置，
     * 从其包围盒向下到虚空线都没有方块 → 判定会掉进虚空。
     */
    private fun NonNullContext.isPredictingFall(): Boolean {
        val sim = FallingPlayer(
            player.x, player.y, player.z,
            player.deltaMovement.x, player.deltaMovement.y, player.deltaMovement.z,
            player.yRot,
            player.input.moveVector.x, player.input.moveVector.y,
            player.speed
        )
        val startY = player.y

        for (tick in 1..predictTicks) {
            with(sim) { calculate(1) }
            // 只检查正在下降的模拟点；上升阶段本来就踩不到虚空。
            if (sim.y >= startY) {
                continue
            }

            val minY = minOf(voidLevel.toDouble(), sim.y - 0.01)
            val box = player.boundingBox
                .move(sim.x - player.x, sim.y - player.y, sim.z - player.z)
                .setMinY(minY)

            if (!level.getBlockCollisions(player, box).any()) {
                return true
            }
        }
        return false
    }

    /** 当前位置到虚空线之间是否还有方块。 */
    private fun NonNullContext.hasGroundBelowToVoidLine(): Boolean {
        val minY = minOf(voidLevel.toDouble(), player.y - 0.01)
        val box = player.boundingBox.setMinY(minY)
        return level.getBlockCollisions(player, box).any()
    }

    private fun resetState() {
        likelyFalling = false
        rescuePosition = null
        lastGround = null
        callbackLagging = false
        callbackTimer.reset()
    }

}
