package com.github.epsilon.kernel.gameplay.combat

import com.github.epsilon.kernel.gameplay.player.SwingMode
import com.github.epsilon.kernel.jvm.NonNullContext
import com.github.epsilon.kernel.jvm.hooks.ProtocolCompat
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity

object AttackUtils {

    /**
     * @param swingMode 挥手方式；传 null 表示只发攻击包（例如 MultiAura 自己控制一次挥手）。
     * @param swingFirst 是否先挥手再攻击。默认按目标协议判断，1.8 为 true。
     */
    @JvmStatic
    @JvmOverloads
    fun NonNullContext.attackEntity(
        entity: Entity,
        swingMode: SwingMode? = SwingMode.Both,
        swingFirst: Boolean = ProtocolCompat.isOlderThanOrEqual1_8,
    ): Boolean {
        if (swingFirst) {
            swingMode?.attackSwing(InteractionHand.MAIN_HAND)
        }

        gameMode.attack(player, entity)

        if (!swingFirst) {
            swingMode?.attackSwing(InteractionHand.MAIN_HAND)
        }
        return true
    }

    /** 同一 tick 攻击多个目标时只挥一次手，顺序同样按目标协议处理。 */
    @JvmStatic
    @JvmOverloads
    fun NonNullContext.attackEntities(
        entities: Iterable<Entity>,
        swingMode: SwingMode? = SwingMode.Both,
        swingFirst: Boolean = ProtocolCompat.isOlderThanOrEqual1_8,
    ) {
        if (swingFirst) {
            swingMode?.attackSwing(InteractionHand.MAIN_HAND)
        }

        for (entity in entities) {
            gameMode.attack(player, entity)
        }

        if (!swingFirst) {
            swingMode?.attackSwing(InteractionHand.MAIN_HAND)
        }
    }
}
