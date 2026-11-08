package com.github.epsilon.gui.dropdown.widget

import com.github.epsilon.gui.dropdown.DropdownScreen
import com.github.epsilon.gui.dropdown.DropdownTheme
import com.github.epsilon.gui.dropdown.ReisaDropdownCompanion
import com.github.epsilon.gui.lib.*
import com.github.epsilon.gui.theme.MD3Theme
import com.github.epsilon.kernel.state.getValue
import com.github.epsilon.kernel.state.mutableStateOf
import com.github.epsilon.kernel.state.setValue
import com.github.epsilon.managers.sound.SoundKey
import com.github.epsilon.managers.sound.SoundManager
import com.github.epsilon.settings.impl.MultiEnumSetting
import com.github.epsilon.utils.render.animation.Easing
import com.mojang.blaze3d.platform.InputConstants
import com.github.epsilon.gui.lib.state.LuminAnimatable as Animation

/**
 * 多选枚举设置的下拉控件：折叠时显示摘要，展开后每项带开关，可同时选中多个值。
 */
class MultiEnumWidget(setting: MultiEnumSetting<*>) : SettingWidget<MultiEnumSetting<*>>(setting) {

    private val expandAnim: Animation = Animation(Easing.DECELERATE, DropdownTheme.ANIM_EXPAND)
    private val hoverAnim: Animation = Animation(Easing.EASE_OUT_CUBIC, DropdownTheme.ANIM_HOVER)
    private var expanded: Boolean by mutableStateOf(false)

    override fun getHeight(): Float {
        expandAnim.run(if (expanded) 1.0f else 0.0f)
        return getCollapsedHeight() + getExpandedTotalHeight() * expandAnim.value
    }

    override fun UiScope.draw(mouseX: Int, mouseY: Int) {
        val expand = updateExpandProgress()
        val hover = updateHoverProgress(isFieldHovered(mouseX.toDouble(), mouseY.toDouble()))
        val fieldX = getLocalFieldX()
        val fieldY = getLocalFieldY()
        val fieldW = getFieldWidth()

        Text(
            setting.displayName,
            DropdownTheme.SETTING_PADDING_X,
            1.0f,
            DropdownTheme.SETTING_TEXT_SCALE,
            DropdownTheme.settingLabel()
        )

        drawField(fieldX, fieldY, fieldW, hover, expand)

        if (expand > 0.001f) {
            drawOptions(mouseX, mouseY, fieldX, fieldW, expand)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isFieldHovered(mouseX, mouseY)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT) {
                expanded = !expanded
                SoundManager.playInUi(if (expanded) SoundKey.SETTINGS_OPEN else SoundKey.SETTINGS_CLOSE)
                DropdownScreen.react(
                    if (expanded) ReisaDropdownCompanion.Action.PANEL_OPEN
                    else ReisaDropdownCompanion.Action.PANEL_CLOSE
                )
                return true
            }
            return expanded
        }

        if (expanded) {
            for ((index, mode) in setting.modes.withIndex()) {
                if (isOptionHovered(mouseX, mouseY, index)) {
                    setting.toggleUnchecked(mode)
                    DropdownScreen.react(ReisaDropdownCompanion.Action.CONFIRM)
                    return true
                }
            }
            expanded = false
            SoundManager.playInUi(SoundKey.SETTINGS_CLOSE)
            DropdownScreen.react(ReisaDropdownCompanion.Action.PANEL_CLOSE)
        }
        return false
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (expanded && keyCode == InputConstants.KEY_ESCAPE) {
            expanded = false
            SoundManager.playInUi(SoundKey.SETTINGS_CLOSE)
            DropdownScreen.react(ReisaDropdownCompanion.Action.CANCEL)
            return true
        }
        return false
    }

    private fun UiScope.drawField(fieldX: Float, fieldY: Float, fieldW: Float, hover: Float, expand: Float) {
        val textY = fieldY + (FIELD_HEIGHT - metrics.textHeight(FIELD_TEXT_SCALE)) * 0.5f

        RoundRect(fieldX, fieldY, fieldW, FIELD_HEIGHT, FIELD_RADIUS, MD3Theme.filledFieldSurface(expanded, hover))
        Outline(fieldX, fieldY, fieldW, FIELD_HEIGHT, FIELD_RADIUS, 0.7f, MD3Theme.filledFieldIndicator(expanded, hover))
        Text(
            setting.getTranslatedSummary(),
            fieldX + FIELD_TEXT_PADDING_X,
            textY,
            FIELD_TEXT_SCALE,
            MD3Theme.filledFieldContent(expanded)
        )
        Triangle(
            fieldX + fieldW - 10.0f,
            fieldY + FIELD_HEIGHT * 0.5f,
            FIELD_ARROW_SIZE,
            expand,
            DropdownTheme.expandArrow(expand)
        )
    }

    private fun UiScope.drawOptions(mouseX: Int, mouseY: Int, fieldX: Float, fieldW: Float, expand: Float) {
        val listY = getLocalListY()
        val listH = getListHeight()
        val clipH = listH * expand
        val visibleBottom = listY + clipH

        RoundRect(fieldX, listY, fieldW, clipH, FIELD_RADIUS, DropdownTheme.settingSurface())
        Outline(fieldX, listY, fieldW, clipH, FIELD_RADIUS, 0.7f, MD3Theme.withAlpha(MD3Theme.OUTLINE, 96))

        for ((index, mode) in setting.modes.withIndex()) {
            val rowY = listY + LIST_PADDING_Y + index * (ROW_HEIGHT + OPTION_GAP)
            if (rowY >= visibleBottom) continue

            val hovered = isOptionHovered(mouseX.toDouble(), mouseY.toDouble(), index)
            if (hovered) {
                RoundRect(
                    fieldX + 1.5f,
                    rowY,
                    fieldW - 3.0f,
                    ROW_HEIGHT,
                    FIELD_RADIUS - 1.0f,
                    MD3Theme.rowSurface(1.0f)
                )
            }

            val selected = setting.containsUnchecked(mode)
            val textY = rowY + (ROW_HEIGHT - metrics.textHeight(OPTION_TEXT_SCALE)) * 0.5f
            Text(
                setting.getTranslatedValueUnchecked(mode),
                fieldX + FIELD_TEXT_PADDING_X,
                textY,
                OPTION_TEXT_SCALE,
                if (selected) MD3Theme.TEXT_PRIMARY else DropdownTheme.settingLabelMuted()
            )
            Toggle(
                UiRect(
                    fieldX + fieldW - TOGGLE_RIGHT_INSET - TOGGLE_WIDTH,
                    rowY + (ROW_HEIGHT - TOGGLE_HEIGHT) * 0.5f,
                    TOGGLE_WIDTH,
                    TOGGLE_HEIGHT
                ),
                if (selected) 1.0f else 0.0f,
                if (hovered) 1.0f else 0.0f
            )
        }
    }

    private fun updateExpandProgress(): Float {
        expandAnim.run(if (expanded) 1.0f else 0.0f)
        return expandAnim.value
    }

    private fun updateHoverProgress(fieldHovered: Boolean): Float {
        hoverAnim.run(if (fieldHovered || expanded) 1.0f else 0.0f)
        return hoverAnim.value
    }

    private fun isFieldHovered(mouseX: Double, mouseY: Double): Boolean {
        return isHovered(mouseX, mouseY, getFieldX(), getFieldY(), getFieldWidth(), FIELD_HEIGHT)
    }

    private fun isOptionHovered(mouseX: Double, mouseY: Double, index: Int): Boolean {
        return isHovered(mouseX, mouseY, getFieldX(), getOptionY(index), getFieldWidth(), ROW_HEIGHT)
    }

    private fun getCollapsedHeight(): Float {
        return DropdownTheme.SETTING_HEIGHT - 1.0f + FIELD_HEIGHT
    }

    private fun getExpandedTotalHeight(): Float {
        return LIST_GAP_Y + getListHeight()
    }

    private fun getListHeight(): Float {
        val count = setting.modes.size
        if (count <= 0) return 0.0f
        return LIST_PADDING_Y * 2.0f + count * ROW_HEIGHT + maxOf(0, count - 1) * OPTION_GAP
    }

    private fun getFieldX(): Float = absoluteX(DropdownTheme.SETTING_PADDING_X)

    private fun getFieldY(): Float = absoluteY(DropdownTheme.SETTING_HEIGHT - 1.0f)

    private fun getLocalFieldX(): Float = DropdownTheme.SETTING_PADDING_X

    private fun getLocalFieldY(): Float = DropdownTheme.SETTING_HEIGHT - 1.0f

    private fun getFieldWidth(): Float = width - DropdownTheme.SETTING_PADDING_X * 2.0f

    private fun getListY(): Float = getFieldY() + FIELD_HEIGHT + LIST_GAP_Y

    private fun getLocalListY(): Float = getLocalFieldY() + FIELD_HEIGHT + LIST_GAP_Y

    private fun getOptionY(index: Int): Float =
        getListY() + LIST_PADDING_Y + index * (ROW_HEIGHT + OPTION_GAP)

    companion object {
        private const val FIELD_HEIGHT = 14.0f
        private const val FIELD_RADIUS = 5.0f
        private const val FIELD_TEXT_SCALE = 0.6f
        private const val FIELD_TEXT_PADDING_X = 6.0f
        private const val FIELD_ARROW_SIZE = 3.0f
        private const val LIST_GAP_Y = 3.0f
        private const val LIST_PADDING_Y = 2.0f
        private const val ROW_HEIGHT = 14.0f
        private const val OPTION_GAP = 1.0f
        private const val OPTION_TEXT_SCALE = 0.55f
        private const val TOGGLE_WIDTH = 18.0f
        private const val TOGGLE_HEIGHT = 9.0f
        private const val TOGGLE_RIGHT_INSET = 6.0f
    }

}
