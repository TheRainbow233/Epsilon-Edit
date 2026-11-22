package com.github.epsilon.modules.impl.movement

import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.SendPositionEvent
import com.github.epsilon.kernel.gameplay.movement.MovementUtils.forward
import com.github.epsilon.kernel.gameplay.player.PlayerState.isInWeb
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import net.minecraft.world.phys.Vec3

/**
 * Fast Web：在蜘蛛网中保持移动速度。
 *
 * Vanilla 模式由 Mixin 直接放行移动，Grim 模式在发送位置包时重写水平速度。
 */
object FastWeb : Module("Fast Web", Category.MOVEMENT) {

    private enum class Mode {
        Vanilla,
        Grim,
    }

    private val mode by enumSetting("Mode", Mode.Grim)

    private val onlyOnGround by boolSetting("Only On Ground", false, { mode == Mode.Grim })
    private val motionY by boolSetting("Motion Y", false, { mode == Mode.Grim })

    init {
        safeEventHandler<SendPositionEvent> {
            if (mode == Mode.Vanilla) return@safeEventHandler
            if (!isInWeb()) return@safeEventHandler

            val move = player.input.moveVector
            if (move.x == 0.0f && move.y == 0.0f) return@safeEventHandler

            if (player.onGround() || !onlyOnGround) {
                val forward = forward(0.63)
                player.deltaMovement = Vec3(forward[0], player.deltaMovement.y, forward[1])
            }

            if (motionY) {
                val current = player.deltaMovement
                player.deltaMovement = Vec3(current.x, 0.1, current.z)
            }
        }
    }

    /** Vanilla 模式由 MixinWebBlock 读取。 */
    fun cobweb(): Boolean = isEnabled && mode == Mode.Vanilla

}
