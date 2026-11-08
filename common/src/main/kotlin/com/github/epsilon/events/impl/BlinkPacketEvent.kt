package com.github.epsilon.events.impl

import com.github.epsilon.events.api.EventBus
import com.github.epsilon.events.api.IEvent
import com.github.epsilon.events.api.IPosting
import net.minecraft.network.protocol.Packet

/**
 * 数据包延迟投票事件。
 *
 * 由 [com.github.epsilon.managers.network.ServerboundPacketManager] 在拦截到数据包时发布，
 * 模块通过 [vote] 表达处理意见；多个模块同时投票时取优先级最高者。
 *
 * 周期心跳会发布 [packet] 为 `null` 的事件，让模块按时间窗口决定是否继续压住队列。
 */
class BlinkPacketEvent(
    /** 当前被处理的数据包；周期心跳时为 `null`。 */
    val packet: Packet<*>?,
    /** 数据包方向：入站或出站。 */
    val origin: TransferOrigin,
) : IEvent<BlinkPacketEvent>, IPosting by Companion {

    var action: Action = Action.FLUSH
        private set

    /** 投票；低优先级动作不会覆盖已设置的高优先级动作。 */
    fun vote(action: Action) {
        if (action.priority <= this.action.priority) return
        this.action = action
    }

    enum class TransferOrigin {
        INCOMING,
        OUTGOING,
    }

    enum class Action(val priority: Int) {
        /** 清空该方向的队列并放行当前包（默认）。 */
        FLUSH(0),

        /** 放行当前包，但保留队列。 */
        PASS(1),

        /** 取消当前包并加入队列。 */
        QUEUE(2),
    }

    companion object : EventBus()

}
