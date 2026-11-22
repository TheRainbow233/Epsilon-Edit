package com.github.epsilon.gui.panel.component.setting

import com.github.epsilon.gui.lib.*
import com.github.epsilon.gui.panel.component.PanelElements
import com.github.epsilon.gui.panel.component.RowSurface
import com.github.epsilon.gui.panel.component.SettingRow
import com.github.epsilon.gui.theme.MD3Theme
import com.github.epsilon.kernel.render.animation.Easing
import com.github.epsilon.kernel.state.getValue
import com.github.epsilon.kernel.state.mutableStateOf
import com.github.epsilon.kernel.state.setValue
import com.github.epsilon.settings.impl.MultiEnumSetting
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.MouseButtonEvent
import com.github.epsilon.gui.lib.state.LuminAnimatable as Animation

private const val CHIP_TEXT_SCALE = 0.60f
private const val CHIP_HORIZONTAL_PADDING = 8.0f
private const val CHIP_TRAILING_SLOT = 10.0f
private const val CHIP_MAX_WIDTH = 120.0f
private const val CHEVRON_SIZE = 3.0f
private const val CHEVRON_RIGHT_INSET = 7.5f

class MultiEnumSettingRow(private val multiEnumSetting: MultiEnumSetting<*>) :
    SettingRow<MultiEnumSetting<*>>(multiEnumSetting) {

    internal val dropdownAnimation = Animation(Easing.EASE_OUT_CUBIC, 180L)

    internal var dropdownOpen by mutableStateOf(false)

    init {
        dropdownAnimation.startValue = 0.0f
    }

    fun setDropdownOpen(open: Boolean) {
        this.dropdownOpen = open
    }

    /**
     * 多选摘要 chip 的绝对矩形，供弹窗定位使用。
     */
    fun getChipBounds(metrics: UiTextMetrics, bounds: UiRect): UiRect = PanelElements.measureAssistChipBounds(
        metrics, bounds, multiEnumSetting.getTranslatedSummary(), CHIP_TEXT_SCALE, CHIP_HORIZONTAL_PADDING,
        CHIP_TRAILING_SLOT, CHIP_MAX_WIDTH
    )

    override fun mouseClicked(bounds: UiRect, event: MouseButtonEvent, isDoubleClick: Boolean): Boolean =
        bounds.contains(event.x, event.y) && event.button() == InputConstants.MOUSE_BUTTON_LEFT

}

/**
 * 绘制一行多选枚举设置；作用域原点即行左上角。
 */
fun UiScope.MultiEnumSettingRow(row: MultiEnumSettingRow, hoverProgress: Float) {
    val setting = row.getSetting()
    val summary = setting.getTranslatedSummary()
    val labelScale = 0.68f
    val labelY = (height - metrics.textHeight(labelScale)) / 2.0f

    RowSurface(hoverProgress)
    Text(setting.displayName, MD3Theme.ROW_CONTENT_INSET, labelY, labelScale, MD3Theme.TEXT_PRIMARY)

    val chipBounds = PanelElements.measureLocalAssistChipBounds(
        metrics, width, height, summary,
        CHIP_TEXT_SCALE, CHIP_HORIZONTAL_PADDING, CHIP_TRAILING_SLOT, CHIP_MAX_WIDTH
    )
    Chip(
        chipBounds, summary, CHIP_TEXT_SCALE,
        MD3Theme.SECONDARY_CONTAINER, MD3Theme.ON_SECONDARY_CONTAINER, null, 0.58f, null
    )

    val chevronProgress = animate(row.dropdownAnimation, row.dropdownOpen)
    val chevronCenterX = chipBounds.right() - CHEVRON_RIGHT_INSET
    val chevronCenterY = chipBounds.y + chipBounds.height / 2.0f
    Triangle(
        chevronCenterX, chevronCenterY, CHEVRON_SIZE, chevronProgress,
        MD3Theme.ON_SECONDARY_CONTAINER
    )
}
