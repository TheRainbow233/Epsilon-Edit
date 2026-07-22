package com.github.epsilon.gui.panel.component.setting;

import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.gui.lib.UiRect;
import com.github.epsilon.gui.lib.UiTree;
import com.github.epsilon.gui.panel.component.SettingRow;
import com.github.epsilon.gui.theme.MD3Theme;
import com.github.epsilon.managers.Managers;
import com.github.epsilon.managers.impl.sound.SoundKey;
import com.github.epsilon.settings.impl.MultiEnumSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import java.awt.*;

public class MultiEnumSettingRow extends SettingRow<MultiEnumSetting<?>> {

    private static final Color CHECKBOX_FILL = MD3Theme.PRIMARY;
    private static final Color CHECKBOX_OUTLINE = MD3Theme.OUTLINE;
    private static final Color CHECKMARK_COLOR = MD3Theme.ON_PRIMARY;

    private static final float HEADER_HEIGHT = 20f;
    private static final float ROW_HEIGHT = 17f;
    private static final float BOX_SIZE = 10f;
    private static final float BOX_RADIUS = 3f;
    private static final float BOX_TEXT_GAP = 5f;
    private static final float ITEM_GAP = 12f;
    private static final float INDENT = 14f;
    private static final float ROW_SCALE = 0.54f;
    private static final float LABEL_SCALE = 0.68f;

    // Layout computed in buildUi and reused in mouseClicked
    private transient int rows;

    public MultiEnumSettingRow(MultiEnumSetting<?> setting) {
        super(setting);
    }

    @Override
    public float getHeight() {
        return HEADER_HEIGHT + ROW_HEIGHT * computeRows(MultiEnumSetting.getRawConstants(setting).length);
    }

    private int computeRows(int itemCount) {
        // Estimate: if bounds width is unknown, assume 3 items per row minimum
        return Math.max(1, (itemCount + 2) / 3);
    }

    @Override
    public void buildUi(UiTree.Scope scope, GuiGraphicsExtractor guiGraphics, TextRenderer textRenderer,
                        UiRect bounds, float hoverProgress, int mouseX, int mouseY, float partialTick) {

        scope.roundRect(0.0f, 0.0f, bounds.width(), bounds.height(), MD3Theme.CARD_RADIUS,
                MD3Theme.rowSurface(hoverProgress));
        scope.text(setting.getDisplayName(), MD3Theme.ROW_CONTENT_INSET, 5f, LABEL_SCALE,
                MD3Theme.TEXT_PRIMARY);

        Enum<?>[] constants = MultiEnumSetting.getRawConstants(setting);

        float startX = MD3Theme.ROW_CONTENT_INSET + INDENT;
        float availableWidth = bounds.width() - startX - MD3Theme.ROW_CONTENT_INSET;

        // Measure all items
        float[] itemWidths = new float[constants.length];
        for (int i = 0; i < constants.length; i++) {
            float textW = textRenderer.getWidth(setting.getTranslatedName(constants[i]), ROW_SCALE);
            itemWidths[i] = BOX_SIZE + BOX_TEXT_GAP + textW;
        }

        // Flow layout
        float cursorX = startX;
        float cursorY = HEADER_HEIGHT;
        float maxRowWidth = 0f;
        int placedInRow = 0;

        for (int i = 0; i < constants.length; i++) {
            float itemW = itemWidths[i];

            // Wrap to next row if this item doesn't fit
            if (placedInRow > 0 && cursorX + itemW > startX + availableWidth) {
                maxRowWidth = Math.max(maxRowWidth, cursorX);
                cursorX = startX;
                cursorY += ROW_HEIGHT;
                placedInRow = 0;
            }

            Enum<?> value = constants[i];
            boolean selected = MultiEnumSetting.isSelectedRaw(setting, value);
            String display = setting.getTranslatedName(value);

            float boxX = cursorX;
            float boxY = cursorY + (ROW_HEIGHT - BOX_SIZE) / 2f;
            float textX = boxX + BOX_SIZE + BOX_TEXT_GAP;
            float textY = cursorY + (ROW_HEIGHT - textRenderer.getHeight(ROW_SCALE)) / 2f;

            scope.roundRect(boxX, boxY, BOX_SIZE, BOX_SIZE, BOX_RADIUS,
                    selected ? CHECKBOX_FILL : CHECKBOX_OUTLINE);

            scope.text(display, textX, textY, ROW_SCALE,
                    selected ? MD3Theme.TEXT_PRIMARY : MD3Theme.TEXT_MUTED);

            cursorX += itemW + ITEM_GAP;
            placedInRow++;
        }

        rows = (int) Math.ceil((cursorY - HEADER_HEIGHT) / ROW_HEIGHT) + 1;
    }

    @Override
    public boolean mouseClicked(UiRect bounds, MouseButtonEvent event, boolean isDoubleClick) {
        if (!bounds.contains(event.x(), event.y()) || event.button() != 0) return false;

        TextRenderer textRenderer = FALLBACK_TEXT_METRICS;
        Enum<?>[] constants = MultiEnumSetting.getRawConstants(setting);

        float startX = bounds.x() + MD3Theme.ROW_CONTENT_INSET + INDENT;
        float availableWidth = bounds.width() - MD3Theme.ROW_CONTENT_INSET - INDENT - MD3Theme.ROW_CONTENT_INSET;

        // Measure all items
        float[] itemWidths = new float[constants.length];
        for (int i = 0; i < constants.length; i++) {
            float textW = textRenderer.getWidth(setting.getTranslatedName(constants[i]), ROW_SCALE);
            itemWidths[i] = BOX_SIZE + BOX_TEXT_GAP + textW;
        }

        // Replay flow layout to find the clicked item
        float cursorX = startX;
        float cursorY = bounds.y() + HEADER_HEIGHT;
        int placedInRow = 0;

        for (int i = 0; i < constants.length; i++) {
            float itemW = itemWidths[i];

            if (placedInRow > 0 && cursorX + itemW > startX + availableWidth) {
                cursorX = startX;
                cursorY += ROW_HEIGHT;
                placedInRow = 0;
            }

            // Hitbox: from current cursor to end of this item, full row height
            UiRect hitbox = new UiRect(cursorX, cursorY, itemW, ROW_HEIGHT);
            if (hitbox.contains(event.x(), event.y())) {
                MultiEnumSetting.toggleRaw(setting, constants[i]);
                boolean now = MultiEnumSetting.isSelectedRaw(setting, constants[i]);
                Managers.SOUND.playInUi(now ? SoundKey.SETTINGS_OPEN : SoundKey.SETTINGS_CLOSE);
                return true;
            }

            cursorX += itemW + ITEM_GAP;
            placedInRow++;
        }

        return false;
    }

}
