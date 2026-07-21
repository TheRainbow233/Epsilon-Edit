package com.github.epsilon.gui.panel.view.settings;

import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.gui.lib.UiRect;
import com.github.epsilon.gui.lib.UiTree;
import com.github.epsilon.gui.panel.utils.IMEFocusHelper;
import com.github.epsilon.gui.theme.MD3Theme;
import com.github.epsilon.utils.render.animation.Animation;
import com.github.epsilon.utils.render.animation.Easing;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

import static com.github.epsilon.Constants.mc;

public class ClientSettingTextField {

    private static final long HOVER_DURATION = 120L;
    private final int maxLength;
    private final Animation hoverAnimation = new Animation(Easing.EASE_OUT_CUBIC, HOVER_DURATION);
    private final Animation focusAnimation = new Animation(Easing.EASE_OUT_CUBIC, HOVER_DURATION);

    private boolean focused;
    private String text = "";
    private int cursor;
    // Selection
    private int selStart = -1, selEnd = -1;
    private boolean selecting;
    // Context menu
    private boolean showContextMenu;
    private float menuX, menuY;
    // Last render state for cursor resolution
    private TextRenderer lastTextRenderer;
    private float lastTextX, lastTextScale, lastTextY;

    public ClientSettingTextField(int maxLength) {
        this.maxLength = maxLength;
        hoverAnimation.setStartValue(0.0f);
        focusAnimation.setStartValue(0.0f);
    }

    public void buildUi(UiTree.Scope scope, UiRect bounds, int mouseX, int mouseY,
                        TextRenderer textRenderer, String placeholder, float textScale, String trailingHint) {
        boolean hovered = bounds.contains(mouseX, mouseY);
        float hoverProgress = scope.animate(hoverAnimation, hovered);
        float focusProgress = scope.animate(focusAnimation, focused);
        float textInset = 10.0f;
        float textHeight = textRenderer.getHeight(textScale);
        float textX = bounds.x() + textInset;
        float textY = bounds.y() + (bounds.height() - textHeight) / 2.0f;
        lastTextRenderer = textRenderer;
        lastTextX = textX;
        lastTextScale = textScale;
        lastTextY = textY;

        boolean showPlaceholder = text.isEmpty() && !focused;
        String display = showPlaceholder ? placeholder : text;
        Color textColor = showPlaceholder ? MD3Theme.TEXT_MUTED : MD3Theme.TEXT_PRIMARY;
        final UiTree.SelectionRange sel;
        final Color selColor;
        if (focused && hasSelection()) {
            int low = Math.min(selStart, selEnd);
            int high = Math.max(selStart, selEnd);
            sel = new UiTree.SelectionRange(low, high);
            selColor = new Color(208, 188, 255, 70);
        } else {
            sel = null;
            selColor = null;
        }
        scope.input(bounds, focused, hovered ? 0.6f : 0.0f,
                focusProgress, MD3Theme.PRIMARY, 1.0f,
                textInset, display, textScale, textColor,
                sel, selColor,
                focused ? Math.min(cursor, text.length()) : null, focused ? MD3Theme.TEXT_PRIMARY : null,
                focused && trailingHint != null && !trailingHint.isBlank() && !text.isEmpty() ? trailingHint : null,
                0.56f,
                focused && trailingHint != null && !trailingHint.isBlank() && !text.isEmpty() ? MD3Theme.TEXT_MUTED : null);

        if (focused) {
            int safeCursor = Math.min(cursor, text.length());
            float caretX = textX + textRenderer.getWidth(text.substring(0, safeCursor), textScale);
            IMEFocusHelper.updateCursorPos(caretX, textY);
        }

        if (showContextMenu) {
            drawContextMenu(scope, textRenderer, 0.54f);
        }
    }

    // --- Focus ---

    public boolean focusIfContains(UiRect bounds, double mouseX, double mouseY) {
        if (!bounds.contains(mouseX, mouseY)) return false;
        focused = true;
        cursor = text.length();
        clearSelection();
        IMEFocusHelper.activate();
        return true;
    }

    public void blur() {
        if (focused) {
            focused = false;
            clearSelection();
            IMEFocusHelper.deactivate();
        }
    }

    // --- Mouse ---

    public boolean isHovering(UiRect bounds, double mouseX, double mouseY) {
        return bounds.contains(mouseX, mouseY);
    }

    public boolean mousePressed(UiRect bounds, double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (!bounds.contains(mouseX, mouseY)) {
            if (focused) blur();
            return false;
        }
        if (!focused) {
            focused = true;
            IMEFocusHelper.activate();
        }
        int pos = resolveCursorPos(mouseX);
        selStart = pos;
        selEnd = pos;
        cursor = pos;
        selecting = true;
        return true;
    }

    public boolean mouseDragged(UiRect bounds, double mouseX, double mouseY) {
        if (!selecting) return false;
        int pos = resolveCursorPos(mouseX);
        selEnd = pos;
        cursor = pos;
        return true;
    }

    public boolean mouseReleased(UiRect bounds, double mouseX, double mouseY, int button) {
        if (!selecting) return false;
        selecting = false;
        if (selStart == selEnd) clearSelection();
        return true;
    }

    private int resolveCursorPos(double mouseX) {
        if (lastTextRenderer == null || text.isEmpty()) return 0;
        // Binary search for cursor position based on text width
        for (int i = 0; i <= text.length(); i++) {
            float w = lastTextRenderer.getWidth(text.substring(0, i), lastTextScale);
            if (mouseX < lastTextX + w) return i;
        }
        return text.length();
    }

    public boolean rightClicked(UiRect bounds, double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        if (!bounds.contains(mouseX, mouseY)) {
            showContextMenu = false;
            return false;
        }
        showContextMenu = true;
        float menuW = 90f;
        float menuH = 36f;
        float screenW = mc.getWindow().getWidth() / (float) mc.getWindow().getGuiScale();
        float screenH = mc.getWindow().getHeight() / (float) mc.getWindow().getGuiScale();
        menuX = (float) mouseX;
        menuY = (float) mouseY;
        if (menuX + menuW > screenW) menuX = (float) mouseX - menuW;
        if (menuY + menuH > screenH) menuY = (float) mouseY - menuH;
        if (menuX < 0) menuX = 2f;
        if (menuY < 0) menuY = 2f;
        return true;
    }

    public boolean contextMenuClicked(double mouseX, double mouseY, int button) {
        if (!showContextMenu || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        float itemH = 18f;
        float menuW = 90f;

        if (mouseX >= menuX && mouseX <= menuX + menuW && mouseY >= menuY && mouseY <= menuY + itemH) {
            if (hasSelection()) {
                int low = Math.min(selStart, selEnd);
                int high = Math.max(selStart, selEnd);
                mc.keyboardHandler.setClipboard(text.substring(low, high));
            } else {
                mc.keyboardHandler.setClipboard(text);
            }
            showContextMenu = false;
            return true;
        }
        if (mouseX >= menuX && mouseX <= menuX + menuW && mouseY >= menuY + itemH && mouseY <= menuY + itemH * 2) {
            if (hasSelection()) deleteSelection();
            String clipboard = mc.keyboardHandler.getClipboard();
            if (!clipboard.isEmpty()) {
                String safe = clipboard.codePoints()
                        .filter(cp -> cp >= 32 && cp != 127)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString();
                if (!safe.isEmpty()) insertText(safe);
            }
            showContextMenu = false;
            return true;
        }
        showContextMenu = false;
        return false;
    }

    public boolean isContextMenuOpen() { return showContextMenu; }
    public void closeContextMenu() { showContextMenu = false; }

    private void drawContextMenu(UiTree.Scope scope, TextRenderer textRenderer, float scale) {
        float itemH = 18f;
        float menuW = 90f;
        float menuH = itemH * 2;

        scope.roundRect(menuX, menuY, menuW, menuH, 6f, new Color(40, 36, 48, 248));
        scope.outline(menuX, menuY, menuW, menuH, 6f, 0.6f, MD3Theme.withAlpha(MD3Theme.PRIMARY, 50));

        float textY1 = menuY + (itemH - textRenderer.getHeight(scale)) / 2f;
        scope.text("Copy", menuX + 8f, textY1, scale, MD3Theme.TEXT_PRIMARY);

        float textY2 = menuY + itemH + (itemH - textRenderer.getHeight(scale)) / 2f;
        scope.text("Paste", menuX + 8f, textY2, scale, MD3Theme.TEXT_PRIMARY);

        scope.rect(menuX + 4f, menuY + itemH, menuW - 8f, 0.5f, MD3Theme.withAlpha(MD3Theme.OUTLINE, 60));
    }

    // --- Keyboard ---

    public boolean keyPressed(KeyEvent event) {
        if (!focused) return false;
        if (isControlDown()) return handleControlShortcut(event.key());
        return handleNavigationKey(event.key());
    }

    public boolean keyPressed(int keyCode) {
        if (!focused) return false;
        if (isControlDown()) return handleControlShortcut(keyCode);
        return handleNavigationKey(keyCode);
    }

    private boolean handleNavigationKey(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hasSelection()) { deleteSelection(); }
                else if (cursor > 0 && !text.isEmpty()) {
                    text = text.substring(0, cursor - 1) + text.substring(cursor);
                    cursor--;
                }
                clearSelection();
                yield true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (hasSelection()) { deleteSelection(); }
                else if (cursor < text.length()) {
                    text = text.substring(0, cursor) + text.substring(cursor + 1);
                }
                clearSelection();
                yield true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                cursor = Math.max(0, cursor - 1);
                if (!isShiftDown()) clearSelection();
                else if (selStart < 0) selStart = cursor + 1;
                if (isShiftDown()) selEnd = cursor;
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                cursor = Math.min(text.length(), cursor + 1);
                if (!isShiftDown()) clearSelection();
                else if (selStart < 0) selStart = cursor - 1;
                if (isShiftDown()) selEnd = cursor;
                yield true;
            }
            case GLFW.GLFW_KEY_HOME -> { cursor = 0; if (!isShiftDown()) clearSelection(); yield true; }
            case GLFW.GLFW_KEY_END -> { cursor = text.length(); if (!isShiftDown()) clearSelection(); yield true; }
            case GLFW.GLFW_KEY_ESCAPE -> { blur(); yield true; }
            default -> false;
        };
    }

    public boolean charTyped(CharacterEvent event) {
        if (!focused) return false;
        String typed = event.codepointAsString();
        if (typed.isEmpty()) return true;
        if (hasSelection()) deleteSelection();
        insertText(typed);
        return true;
    }

    // --- Getters/Setters ---

    public boolean hasActiveAnimations() {
        return !hoverAnimation.isFinished() || !focusAnimation.isFinished();
    }

    public boolean isFocused() { return focused; }
    public String getText() { return text; }

    public void setText(String text) {
        this.text = text == null ? "" : clampToMaxLength(text);
        this.cursor = Math.min(this.cursor, this.text.length());
        clearSelection();
    }

    public void clear() { text = ""; cursor = 0; clearSelection(); }
    public void setCursorToEnd() { cursor = text.length(); clearSelection(); }

    // --- Internals ---

    private boolean hasSelection() { return selStart >= 0 && selEnd >= 0 && selStart != selEnd; }
    private void clearSelection() { selStart = -1; selEnd = -1; }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int low = Math.min(selStart, selEnd);
        int high = Math.max(selStart, selEnd);
        text = text.substring(0, low) + text.substring(high);
        cursor = low;
        clearSelection();
    }

    private boolean handleControlShortcut(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_A -> { selStart = 0; selEnd = text.length(); cursor = text.length(); yield true; }
            case GLFW.GLFW_KEY_C -> {
                if (hasSelection()) {
                    int low = Math.min(selStart, selEnd);
                    int high = Math.max(selStart, selEnd);
                    mc.keyboardHandler.setClipboard(text.substring(low, high));
                }
                yield true;
            }
            case GLFW.GLFW_KEY_X -> {
                if (hasSelection()) {
                    int low = Math.min(selStart, selEnd);
                    int high = Math.max(selStart, selEnd);
                    mc.keyboardHandler.setClipboard(text.substring(low, high));
                    deleteSelection();
                }
                yield true;
            }
            case GLFW.GLFW_KEY_V -> {
                String clipboard = mc.keyboardHandler.getClipboard();
                if (!clipboard.isEmpty()) {
                    if (hasSelection()) deleteSelection();
                    String safe = clipboard.codePoints()
                            .filter(cp -> cp >= 32 && cp != 127)
                            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                            .toString();
                    if (!safe.isEmpty()) insertText(safe);
                }
                yield true;
            }
            default -> false;
        };
    }

    private void insertText(String inserted) {
        if (inserted == null || inserted.isEmpty()) return;
        int available = maxLength - text.length();
        if (available <= 0) return;
        String safeInsert = inserted.length() > available ? inserted.substring(0, available) : inserted;
        text = text.substring(0, cursor) + safeInsert + text.substring(cursor);
        cursor += safeInsert.length();
        clearSelection();
    }

    private String clampToMaxLength(String value) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private boolean isControlDown() {
        var window = mc.getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private boolean isShiftDown() {
        var window = mc.getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}
