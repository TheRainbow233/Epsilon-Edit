package com.github.epsilon.gui.panel.popup

import com.github.epsilon.gui.lib.*
import com.github.epsilon.gui.lib.render.UiContentBuffer
import com.github.epsilon.gui.lib.render.UiRenderBatch
import com.github.epsilon.gui.lib.state.LuminAnimatable
import com.github.epsilon.gui.lib.state.LuminView
import com.github.epsilon.gui.theme.EpsilonUiTheme
import com.github.epsilon.gui.theme.MD3Theme
import com.github.epsilon.kernel.state.getValue
import com.github.epsilon.kernel.state.mutableStateOf
import com.github.epsilon.kernel.state.setValue
import com.github.epsilon.settings.impl.MultiEnumSetting
import com.github.epsilon.kernel.render.animation.Easing
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * 多选枚举设置的面板弹窗：点击整行切换选中状态，选中后保持打开，便于连续调整。
 */
class MultiEnumSelectPopup(
    bounds: UiRect,
    private val setting: MultiEnumSetting<*>,
) : PanelPopupHost.Popup {

    private val bounds: UiRect
    private val scrollable: Boolean
    private val maxScroll: Float
    private var scroll by mutableStateOf(0.0f)

    private val contentBuffer = UiContentBuffer(EpsilonUiTheme)
    private val openAnimation = LuminAnimatable(0.0f, Easing.EASE_OUT_CUBIC, 140L)

    init {
        val optionCount = setting.modes.size
        this.scrollable = optionCount > MAX_VISIBLE_ITEMS
        if (scrollable) {
            val cappedHeight = MAX_VISIBLE_ITEMS * ITEM_HEIGHT + CONTENT_PADDING * 2
            this.bounds = UiRect(bounds.x, bounds.y, bounds.width, cappedHeight)
        } else {
            this.bounds = bounds
        }

        val fullContentHeight = optionCount * ITEM_HEIGHT
        val viewportHeight = this.bounds.height - CONTENT_PADDING * 2
        this.maxScroll = maxOf(0.0f, fullContentHeight - viewportHeight)
    }

    fun getSetting(): MultiEnumSetting<*> {
        return setting
    }

    override fun getBounds(): UiRect {
        return bounds
    }

    override val inputView = LuminView()

    override fun extractGui(
        guiGraphics: GuiGraphicsExtractor, renderBatch: UiRenderBatch,
        mouseX: Int, mouseY: Int, partialTick: Float,
    ) {
        inputView.render(mouseX, mouseY) { renderContent(guiGraphics, renderBatch, mouseX, mouseY, partialTick) }
    }

    private fun renderContent(
        guiGraphics: GuiGraphicsExtractor, renderBatch: UiRenderBatch,
        mouseX: Int, mouseY: Int, partialTick: Float,
    ) {
        contentBuffer.clear()
        val popupY = bounds.y - (1.0f - openAnimation.value) * 6.0f
        openAnimation.target = 1.0f

        val viewportHeight = bounds.height - CONTENT_PADDING * 2
        val fullContentHeight = setting.modes.size * ITEM_HEIGHT
        val itemAreaWidth = bounds.width - CONTENT_PADDING * 2 - (if (scrollable) 6.0f else 0.0f)
        val viewportBounds = UiRect(
            bounds.x + CONTENT_PADDING, popupY + CONTENT_PADDING,
            bounds.width - CONTENT_PADDING * 2, viewportHeight
        )
        val popupBounds = UiRect(bounds.x, popupY, bounds.width, bounds.height)

        renderBatch.LuminUIScene(popupBounds, key = this) {
            InputRegion(
                key = this@MultiEnumSelectPopup to "input", onScroll = ::mouseScrolled,
                onBlur = {}, onCancel = {})

            val progress = openAnimation.value
            PopupCard(
                size,
                MD3Theme.CARD_RADIUS.toFloat(),
                MD3Theme.POPUP_SHADOW_BLUR,
                MD3Theme.withAlpha(MD3Theme.SHADOW, (MD3Theme.POPUP_SHADOW_ALPHA * progress).toInt()),
                MD3Theme.withAlpha(MD3Theme.SURFACE_CONTAINER_LOW, 255)
            )

            val localViewportBounds = viewportBounds.relativeTo(popupBounds)
            Viewport(contentBuffer, localViewportBounds, scroll, maxScroll, fullContentHeight) {
                val modes = setting.modes
                val itemStartY = popupY + CONTENT_PADDING - scroll
                for (index in modes.indices) {
                    val itemY = itemStartY + index * ITEM_HEIGHT
                    val itemBounds = UiRect(bounds.x + CONTENT_PADDING, itemY, itemAreaWidth, ITEM_INNER_HEIGHT)
                    val visible = itemY + ITEM_INNER_HEIGHT > viewportBounds.y && itemY < viewportBounds.bottom()
                    val hovered = visible && itemBounds.contains(mouseX.toDouble(), mouseY.toDouble()) &&
                            mouseY >= viewportBounds.y && mouseY <= viewportBounds.bottom()
                    val mode = modes[index]
                    val selected = setting.containsUnchecked(mode)

                    LuminUIItem(mode) {
                        val localItemBounds = UiRect(0.0f, index * ITEM_HEIGHT, itemAreaWidth, ITEM_INNER_HEIGHT)
                        Interaction(mode, localItemBounds) {
                            onClick {
                                setting.toggleUnchecked(mode)
                                true
                            }
                        }

                        val hoverBackground = MD3Theme.lerp(
                            MD3Theme.SURFACE_CONTAINER_HIGH, MD3Theme.SURFACE_CONTAINER_HIGHEST, 0.55f
                        )
                        val background: Color = if (selected) {
                            MD3Theme.SECONDARY_CONTAINER
                        } else if (hovered) {
                            hoverBackground
                        } else {
                            MD3Theme.withAlpha(MD3Theme.SURFACE_CONTAINER_HIGHEST, 0)
                        }
                        val textColor: Color = if (selected) {
                            MD3Theme.ON_SECONDARY_CONTAINER
                        } else if (hovered) {
                            MD3Theme.withAlpha(MD3Theme.TEXT_PRIMARY, 255)
                        } else {
                            MD3Theme.TEXT_SECONDARY
                        }

                        RoundRect(
                            localItemBounds.x, localItemBounds.y, localItemBounds.width, localItemBounds.height,
                            8.0f, background
                        )

                        val textScale = 0.62f
                        val textHeight = metrics.textHeight(textScale)
                        val textY = localItemBounds.y + (localItemBounds.height - textHeight) / 2.0f
                        Text(
                            setting.getTranslatedValueUnchecked(mode),
                            localItemBounds.x + 10.0f, textY, textScale, textColor
                        )
                    }
                }
            }
        }
    }

    override fun flush(renderBatch: UiRenderBatch) {
        contentBuffer.flushAndClear()
    }

    private fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!scrollable || maxScroll <= 0.0f) {
            return false
        }
        val nextScroll = scroll - scrollY.toFloat() * 20.0f
        scroll = nextScroll.coerceIn(0.0f, maxScroll)
        return true
    }

    /** 多选需要连续点击，弹窗不因单次选择关闭。 */
    override fun shouldCloseAfterClick(): Boolean = false

    override fun close() {
        inputView.close()
        contentBuffer.close()
    }

    companion object {

        const val MAX_VISIBLE_ITEMS = 5
        private const val ITEM_HEIGHT = 24.0f
        private const val ITEM_INNER_HEIGHT = 22.0f
        private const val CONTENT_PADDING = 6.0f

    }

}
