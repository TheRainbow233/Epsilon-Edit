package com.github.epsilon.kernel.jvm.hooks

import com.github.epsilon.kernel.jvm.hooks.ViaFabricPlusHooks.SupportVersion

/**
 * ViaFabricPlus 目标协议版本适配。
 *
 * 没有安装 ViaFabricPlus 时，[ViaFabricPlusHooks.currentVersion] 会回退到客户端版本（26.3），
 * 因此所有“老版本”判断都是 false，行为与直连现代服务器一致。
 */
object ProtocolCompat {

    private val target: SupportVersion
        get() = ViaFabricPlusHooks.currentVersion

    val isViaFabricPlus: Boolean
        get() = ViaFabricPlusHooks.viaVersion != null

    val isOlderThanOrEqual1_8: Boolean
        get() = target.major <= 8

    val isOlderThanOrEqual1_7_10: Boolean
        get() = target <= SupportVersion(7, 10)

    val isOlderThanOrEqual1_11_1: Boolean
        get() = target <= SupportVersion(11, 1)

    val isOlderThanOrEqual1_12_2: Boolean
        get() = target <= SupportVersion(12, 2)

    val isOlderThanOrEqual1_13_2: Boolean
        get() = target <= SupportVersion(13, 2)

    val isOlderThanOrEqual1_15_2: Boolean
        get() = target <= SupportVersion(15, 2)

    val isOlderThanOrEqual1_21_11: Boolean
        get() = target <= SupportVersion(21, 11)

    val isOlderThan1_21: Boolean
        get() = target < SupportVersion(21, 0)

    val isOlderThan1_21_2: Boolean
        get() = target < SupportVersion(21, 2)

    val isOlderThan26_3: Boolean
        get() = target < SupportVersion(26, 3)

    val isNewerThanOrEqual1_16: Boolean
        get() = target >= SupportVersion(16, 0)

    val isNewerThanOrEqual1_21_5: Boolean
        get() = target >= SupportVersion(21, 5)

    val isNewerThanOrEqual1_21_6: Boolean
        get() = target >= SupportVersion(21, 6)

    val isNewerThanOrEqual1_21_9: Boolean
        get() = target >= SupportVersion(21, 9)

    /** 1.8 或 1.21.5+ 才存在 BLOCKS_ATTACKS 组件。 */
    val isBlocksAttacksExisting: Boolean
        get() = isOlderThanOrEqual1_8 || isNewerThanOrEqual1_21_5
}
