package com.github.epsilon.modules.impl.combat.antibot

import net.minecraft.network.protocol.Packet
import net.minecraft.world.entity.player.Player

/**
 * AntiBot 检测模式基类。
 *
 * 每个模式实现一种假人识别策略；由 [com.github.epsilon.modules.impl.combat.AntiBot]
 * 负责把数据包、tick 与攻击事件转发给当前激活的模式。
 */
abstract class AntiBotMode(private val modeName: String) {

    /** 被禁用或切换世界时清理内部状态。 */
    open fun reset() {}

    /** 处理入站数据包。 */
    open fun onPacketReceive(packet: Packet<*>) {}

    /** 每客户端 tick 调用。 */
    open fun onTick() {}

    /** 本地玩家攻击实体时调用。 */
    open fun onAttack(entityId: Int) {}

    abstract fun isBot(entity: Player): Boolean

    override fun toString(): String = modeName

}
