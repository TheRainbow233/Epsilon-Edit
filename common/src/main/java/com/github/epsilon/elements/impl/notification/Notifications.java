package com.github.epsilon.elements.impl.notification;

import com.github.epsilon.elements.HudModule;
import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.gui.hudeditor.HudEditorScreen;
import com.github.epsilon.gui.lib.UiTree;
import com.github.epsilon.managers.Managers;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.render.animation.Easing;
import com.google.common.base.Suppliers;
import net.minecraft.client.DeltaTracker;
import net.minecraft.util.Mth;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class Notifications extends HudModule {

    public static final Notifications INSTANCE = new Notifications();

    public enum Style {
        Legacy,
        MD3
    }

    private Notifications() {
        super("Notifications", 3.2f, 3.2f, DEFAULT_BOX_WIDTH, DEFAULT_BOX_HEIGHT);
    }

    private final EnumSetting<Style> style = enumSetting("Style", Style.MD3);

    private final DoubleSetting scale = doubleSetting("Scale", 1.0, 0.5, 2.0, 0.05);
    private final DoubleSetting fontScale = doubleSetting("Font Scale", 0.80, 0.5, 2.0, 0.05);
    private final DoubleSetting subtitleYOffset = doubleSetting("Subtitle Y Offset", 0.4, -10.0, 20.0, 0.1);
    private final IntSetting boxWidth = intSetting("Width", DEFAULT_BOX_WIDTH, 80, 300, 1);
    private final IntSetting boxHeight = intSetting("Height", DEFAULT_BOX_HEIGHT, 24, 80, 1);
    private final IntSetting backgroundAlpha = intSetting("Background Alpha", 145, 0, 255, 1);
    public final IntSetting displayTime = intSetting("Display Time", 2000, 500, 5000, 100);

    private static final int DEFAULT_BOX_WIDTH = 120;
    private static final int DEFAULT_BOX_HEIGHT = 30;
    private static final float ACCENT_BAR_WIDTH = 2.4f;
    private static final float TEXT_PADDING = 6.0f;
    private static final float ENTRY_GAP = 3.0f;
    private static final float LINE_GAP = 1.8f;
    private static final float SUBTITLE_SCALE = 0.92f;

    private final Supplier<TextRenderer> textRendererSupplier = Suppliers.memoize(TextRenderer::create);

    @Override
    public void render(DeltaTracker deltaTracker) {
        Managers.NOTIFICATION.update();
        Notification previewNotification = createPreviewNotification();
        if (Managers.NOTIFICATION.isEmpty() && previewNotification == null) return;

        if (style.is(Style.MD3)) {
            renderMD3(deltaTracker, previewNotification);
        } else {
            renderLegacy(deltaTracker, previewNotification);
        }
    }

    private void renderLegacy(DeltaTracker deltaTracker, Notification previewNotification) {
        TextRenderer textRenderer = textRendererSupplier.get();
        UiTree.Scope scope = renderScope();

        float s = scale.getValue().floatValue();
        float textScale = fontScale.getValue().floatValue() * s;
        float anchorWidth = boxWidth.getValue() * s;
        float boxHeight = this.boxHeight.getValue() * s;
        float spacing = boxHeight + ENTRY_GAP * s;
        int bgAlpha = backgroundAlpha.getValue();

        List<RenderEntry> entries = new ArrayList<>();
        float totalHeight = 0f;

        for (Notification notification : Managers.NOTIFICATION.getNotifications()) {
            RenderFrame frame = getRenderFrame(notification, spacing);
            if (frame.stage == RenderStage.HIDDEN) continue;

            totalHeight += frame.occupiedHeight;
            entries.add(new RenderEntry(notification, anchorWidth, frame));
        }

        if (entries.isEmpty() && previewNotification != null) {
            totalHeight = spacing;
            entries.add(new RenderEntry(previewNotification, anchorWidth, new RenderFrame(RenderStage.SHOW, 1.0f, spacing)));
        }

        if (entries.isEmpty()) return;

        float resolvedHeight = Math.max(boxHeight, totalHeight);
        float currentY = getBaseY(resolvedHeight);

        for (RenderEntry entry : entries) {
            float renderX = getRenderX(anchorWidth, entry.boxWidth);
            renderNotification(scope, textRenderer, entry.notification, entry.frame, renderX, currentY, anchorWidth, entry.boxWidth, boxHeight, s, textScale, bgAlpha);
            currentY += entry.frame.occupiedHeight;
        }

        setBounds(anchorWidth, boxHeight);
    }

    // --- MD3 Style ---

    private static final float MD3_RADIUS = 10f;
    private static final float MD3_PADDING_X = 12f;
    private static final float MD3_PADDING_Y = 10f;
    private static final float MD3_PROGRESS_HEIGHT = 3f;
    private static final float MD3_PROGRESS_GAP = 6f;
    private static final float MD3_ICON_SIZE = 16f;
    private static final float MD3_ICON_GAP = 8f;
    private static final float MD3_TITLE_SCALE = 0.68f;
    private static final float MD3_SUBTITLE_SCALE = 0.54f;
    private static final float MD3_LINE_GAP = 2f;
    private static final float MD3_MIN_WIDTH = 100f;
    private static final float MD3_MAX_WIDTH = 280f;
    private static final Color MD3_SURFACE = new Color(30, 25, 35, 235);

    private void renderMD3(DeltaTracker deltaTracker, Notification previewNotification) {
        UiTree.Scope scope = renderScope();
        TextRenderer textRenderer = textRendererSupplier.get();

        float s = scale.getValue().floatValue();
        float gap = ENTRY_GAP * s;

        List<Notification> active = new ArrayList<>(Managers.NOTIFICATION.getNotifications());
        if (active.isEmpty() && previewNotification != null) {
            active.add(previewNotification);
        }
        if (active.isEmpty()) return;

        // Measure all cards first to compute total height
        float[] cardWidths = new float[active.size()];
        float[] cardHeights = new float[active.size()];
        float maxWidth = MD3_MIN_WIDTH * s;

        for (int i = 0; i < active.size(); i++) {
            Notification n = active.get(i);
            float titleW = textRenderer.getWidth(n.getTitle(), MD3_TITLE_SCALE * s);
            float subW = (n.getSubTitle() != null && !n.getSubTitle().isEmpty())
                    ? textRenderer.getWidth(n.getSubTitle(), MD3_SUBTITLE_SCALE * s) : 0f;
            float contentW = Math.max(titleW, subW);
            float cardW = Mth.clamp(MD3_PADDING_X * 2f + MD3_ICON_SIZE + MD3_ICON_GAP + contentW + MD3_PADDING_X,
                    MD3_MIN_WIDTH * s, MD3_MAX_WIDTH * s);
            boolean hasSub = n.getSubTitle() != null && !n.getSubTitle().isEmpty();
            float textH = textRenderer.getHeight(MD3_TITLE_SCALE * s)
                    + (hasSub ? MD3_LINE_GAP * s + textRenderer.getHeight(MD3_SUBTITLE_SCALE * s) : 0f);
            float cardH = MD3_PADDING_Y * 2f + MD3_PROGRESS_GAP + MD3_PROGRESS_HEIGHT + textH;
            cardWidths[i] = cardW;
            cardHeights[i] = Math.max(cardH, MD3_ICON_SIZE + MD3_PADDING_Y * 2f + MD3_PROGRESS_GAP + MD3_PROGRESS_HEIGHT);
            maxWidth = Math.max(maxWidth, cardW);
        }

        float totalH = 0f;
        for (float h : cardHeights) totalH += h + gap;
        float currentY = getBaseY(totalH);

        for (int i = 0; i < active.size(); i++) {
            Notification notif = active.get(i);
            float cardW = cardWidths[i];
            float cardH = cardHeights[i];

            long elapsed = notif.getElapsedTime();
            int displayMs = notif.getDisplayTime();
            float showProgress = Mth.clamp(elapsed / 200f, 0f, 1f);
            float exitProgress = notif.isExiting() ? 1f - Mth.clamp(notif.getExitTime() / 250f, 0f, 1f) : 1f;
            float alpha = Math.min(showProgress, exitProgress);
            if (alpha <= 0.01f) continue;

            float cardX = this.x;
            if (getHorizontalAnchor() == HorizontalAnchor.Right) {
                cardX = this.x + maxWidth - cardW;
            }
            float yOffset = (1f - alpha) * 16f;
            float cardY = currentY + yOffset;

            // Shadow
            scope.shadow(cardX, cardY, cardW, cardH, MD3_RADIUS, 6f,
                    new Color(0, 0, 0, (int)(60 * alpha)));

            // Card background
            scope.roundRect(cardX, cardY, cardW, cardH, MD3_RADIUS,
                    new Color(MD3_SURFACE.getRed(), MD3_SURFACE.getGreen(), MD3_SURFACE.getBlue(),
                            (int)(MD3_SURFACE.getAlpha() * alpha)));

            // Progress bar
            float progY = cardY + cardH - MD3_PADDING_Y + 2f;
            float progW = cardW - MD3_PADDING_X * 2f;
            // Background
            scope.roundRect(cardX + MD3_PADDING_X, progY, progW, MD3_PROGRESS_HEIGHT, 1.5f,
                    new Color(255, 255, 255, (int)(28 * alpha)));
            // Fill
            float progressRatio = 1f - Mth.clamp((float)elapsed / displayMs, 0f, 1f);
            float progFillW = progW * progressRatio;
            if (progFillW > 1f) {
                Color modeColor = notif.getMode().getColor();
                scope.roundRect(cardX + MD3_PADDING_X, progY, progFillW, MD3_PROGRESS_HEIGHT, 1.5f,
                        new Color(modeColor.getRed(), modeColor.getGreen(), modeColor.getBlue(),
                                (int)(220 * alpha)));
            }

            // Icon
            Color modeColor = notif.getMode().getColor();
            float iconY = cardY + MD3_PADDING_Y;
            scope.roundRect(cardX + MD3_PADDING_X, iconY, MD3_ICON_SIZE, MD3_ICON_SIZE, MD3_ICON_SIZE / 2f,
                    new Color(modeColor.getRed(), modeColor.getGreen(), modeColor.getBlue(),
                            (int)(70 * alpha)));

            String iconChar = switch (notif.getMode()) {
                case Success -> "✓";
                case Error -> "!";
                default -> "i";
            };
            float iconScale = 0.58f;
            float iconTextW = textRenderer.getWidth(iconChar, iconScale);
            float iconTextH = textRenderer.getHeight(iconScale);
            scope.text(iconChar,
                    cardX + MD3_PADDING_X + (MD3_ICON_SIZE - iconTextW) / 2f,
                    iconY + (MD3_ICON_SIZE - iconTextH) / 2f,
                    iconScale,
                    new Color(255, 255, 255, (int)(255 * alpha)));

            // Text — no scissor needed since card is auto-sized
            float textX = cardX + MD3_PADDING_X + MD3_ICON_SIZE + MD3_ICON_GAP;
            float titleY = cardY + MD3_PADDING_Y;
            scope.text(notif.getTitle(), textX, titleY, MD3_TITLE_SCALE * s,
                    new Color(255, 255, 255, (int)(255 * alpha)));

            String subTitle = notif.getSubTitle();
            if (subTitle != null && !subTitle.isEmpty()) {
                float subY = titleY + textRenderer.getHeight(MD3_TITLE_SCALE * s) + MD3_LINE_GAP * s;
                scope.text(subTitle, textX, subY, MD3_SUBTITLE_SCALE * s,
                        new Color(200, 195, 210, (int)(220 * alpha)));
            }

            currentY += cardH + gap;
        }

        setBounds(maxWidth, Math.max(MD3_ICON_SIZE + MD3_PADDING_Y * 2f + MD3_PROGRESS_GAP + MD3_PROGRESS_HEIGHT, totalH));
    }

    private float getSubTitleScale(float scale) {
        return scale * SUBTITLE_SCALE;
    }

    private float getRenderX(float anchorWidth, float boxWidth) {
        return getHorizontalAnchor() == HorizontalAnchor.Right ? this.x + anchorWidth - boxWidth
                : getHorizontalAnchor() == HorizontalAnchor.Center ? this.x + (anchorWidth - boxWidth) / 2.0f
                  : this.x;
    }

    private float getBaseY(float totalHeight) {
        return getVerticalAnchor() == VerticalAnchor.Bottom ? this.y + this.height - totalHeight : this.y;
    }

    private RenderFrame getRenderFrame(Notification notification, float occupiedHeight) {
        long elapsedTime = notification.getElapsedTime();
        if (!notification.shouldSkipIntroAnim()) {
            if (elapsedTime <= 300L) {
                float progress = Easing.EASE_OUT_CUBIC.getFunction().apply(elapsedTime / 300.0f);
                return new RenderFrame(RenderStage.ENTER_BAR, progress, occupiedHeight * progress);
            }

            if (elapsedTime <= 500L) {
                float progress = Easing.EASE_OUT_CUBIC.getFunction().apply((elapsedTime - 300L) / 200.0f);
                return new RenderFrame(RenderStage.ENTER_CONTENT, progress, occupiedHeight);
            }
        }

        long exitTime = notification.getExitTime();
        if (exitTime < 0L) {
            return new RenderFrame(RenderStage.SHOW, 1.0f, occupiedHeight);
        }

        if (exitTime <= 200L) {
            float progress = 1.0f - Easing.EASE_OUT_CUBIC.getFunction().apply(exitTime / 200.0f);
            return new RenderFrame(RenderStage.EXIT_CONTENT, progress, occupiedHeight);
        }

        if (exitTime <= 500L) {
            float progress = 1.0f - Easing.EASE_OUT_CUBIC.getFunction().apply((exitTime - 200L) / 300.0f);
            return new RenderFrame(RenderStage.EXIT_BAR, progress, occupiedHeight * progress);
        }

        return new RenderFrame(RenderStage.HIDDEN, 0.0f, 0.0f);
    }

    private void renderNotification(UiTree.Scope scope, TextRenderer metrics, Notification notification, RenderFrame frame, float x, float y, float anchorWidth, float boxWidth, float boxHeight, float scale, float textScale, int bgAlpha) {
        switch (frame.stage) {
            case ENTER_BAR, EXIT_BAR -> {
                renderStage1(scope, notification, x, y, anchorWidth, boxWidth, boxHeight, frame.progress);
            }
            case ENTER_CONTENT, EXIT_CONTENT, SHOW -> {
                renderStage2(scope, metrics, notification, x, y, boxWidth, boxHeight, scale, textScale, bgAlpha, frame.progress);
            }
            case HIDDEN -> {
            }
        }
    }

    private void renderStage1(UiTree.Scope scope, Notification notification, float x, float y, float anchorWidth, float boxWidth, float boxHeight, float progress) {
        float width = isLeftDocked() ? boxWidth * progress : boxWidth - anchorWidth * (1.0f - progress);
        float renderX = isLeftDocked() ? x : x + boxWidth - width;
        scope.rect(renderX, y, width, boxHeight, notification.getMode().getColor());
    }

    private void renderStage2(UiTree.Scope scope, TextRenderer metrics, Notification notification, float x, float y, float boxWidth, float boxHeight, float scale, float textScale, int bgAlpha, float progress) {
        scope.rect(x, y, boxWidth, boxHeight, new Color(0, 0, 0, bgAlpha));
        boolean requiresScissor = textExceedsBox(metrics, notification, boxWidth, boxHeight, scale, textScale);
        scope.scissorIf(requiresScissor, x, y, boxWidth, boxHeight,
                textScope -> renderText(textScope, metrics, notification, x, y, boxWidth, boxHeight,
                        scale, textScale, Math.round(255.0f * progress)));
        float accentWidth = ACCENT_BAR_WIDTH * scale + (boxWidth - ACCENT_BAR_WIDTH * scale) * (1.0f - progress);
        float accentX = isLeftDocked() ? x + boxWidth - accentWidth : x;
        scope.rect(accentX, y, accentWidth, boxHeight, notification.getMode().getColor());
    }

    private void renderText(UiTree.Scope scope, TextRenderer metrics, Notification n, float x, float y, float boxWidth, float boxHeight, float scale, float desiredTextScale, int alpha) {
        boolean hasSubTitle = !n.getSubTitle().isEmpty();
        float textScale = getFittedTextScale(metrics, n, boxWidth, scale, desiredTextScale);
        float subTitleScale = getSubTitleScale(textScale);
        float lineGap = getLineGap(textScale, scale);
        float titleHeight = metrics.getHeight(textScale);
        float subTitleHeight = hasSubTitle ? metrics.getHeight(subTitleScale) : 0.0f;
        float contentHeight = titleHeight + subTitleHeight + (hasSubTitle ? lineGap : 0.0f);
        float textX = x + (isLeftDocked() ? TEXT_PADDING * scale : (ACCENT_BAR_WIDTH + TEXT_PADDING) * scale);
        float titleY = y + (boxHeight - contentHeight) / 2.0f;

        scope.text(n.getTitle(), textX, titleY, textScale, new Color(255, 255, 255, alpha));
        if (hasSubTitle) {
            float subTitleY = titleY + titleHeight + lineGap;
            scope.text(n.getSubTitle(), textX, subTitleY, subTitleScale, n.getMode().getColor(Math.round(alpha * 0.86f)));
        }
    }

    private float getLineGap(float textScale, float scale) {
        return LINE_GAP * textScale + subtitleYOffset.getValue().floatValue() * scale;
    }

    private float getFittedTextScale(TextRenderer metrics, Notification notification, float boxWidth, float scale, float desiredTextScale) {
        float maxWidth = Math.max(metrics.getWidth(notification.getTitle(), desiredTextScale),
                metrics.getWidth(notification.getSubTitle(), getSubTitleScale(desiredTextScale)));
        float availableWidth = Math.max(1.0f, boxWidth - (TEXT_PADDING * 2.0f + ACCENT_BAR_WIDTH) * scale);
        float widthFit = maxWidth > availableWidth ? availableWidth / maxWidth : 1.0f;

        return Math.max(0.35f, desiredTextScale * widthFit);
    }

    private boolean textExceedsBox(TextRenderer metrics, Notification notification, float boxWidth,
                                   float boxHeight, float scale, float desiredTextScale) {
        float textScale = getFittedTextScale(metrics, notification, boxWidth, scale, desiredTextScale);
        float subTitleScale = getSubTitleScale(textScale);
        float maxWidth = Math.max(metrics.getWidth(notification.getTitle(), textScale),
                metrics.getWidth(notification.getSubTitle(), subTitleScale));
        float availableWidth = Math.max(1.0f,
                boxWidth - (TEXT_PADDING * 2.0f + ACCENT_BAR_WIDTH) * scale);

        float titleHeight = metrics.getHeight(textScale);
        if (notification.getSubTitle().isEmpty()) {
            return maxWidth > availableWidth || titleHeight > boxHeight;
        }

        float subTitleHeight = metrics.getHeight(subTitleScale);
        float lineGap = getLineGap(textScale, scale);
        float contentHeight = titleHeight + lineGap + subTitleHeight;
        float titleY = (boxHeight - contentHeight) * 0.5f;
        float subTitleY = titleY + titleHeight + lineGap;
        float contentTop = Math.min(titleY, subTitleY);
        float contentBottom = Math.max(titleY + titleHeight, subTitleY + subTitleHeight);
        return maxWidth > availableWidth || contentTop < 0.0f || contentBottom > boxHeight;
    }

    private boolean isLeftDocked() {
        return getHorizontalAnchor() == HorizontalAnchor.Left;
    }

    private Notification createPreviewNotification() {
        if (mc.gui.screen() instanceof HudEditorScreen) {
            return new Notification("Preview", "Notification", NotificationMode.Success, false);
        }
        return null;
    }

    private enum RenderStage {
        ENTER_BAR,
        ENTER_CONTENT,
        SHOW,
        EXIT_CONTENT,
        EXIT_BAR,
        HIDDEN
    }

    private record RenderFrame(RenderStage stage, float progress, float occupiedHeight) {
    }

    private record RenderEntry(Notification notification, float boxWidth, RenderFrame frame) {
    }

}
