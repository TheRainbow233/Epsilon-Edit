package com.github.epsilon.gui.dropdown.widget;

import com.github.epsilon.gui.dropdown.DropdownTheme;
import com.github.epsilon.gui.lib.UiTextMetrics;
import com.github.epsilon.gui.lib.UiTree;
import com.github.epsilon.gui.panel.utils.IMEFocusHelper;
import com.github.epsilon.gui.theme.MD3Theme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.util.function.Predicate;

import static com.github.epsilon.Constants.mc;

public class DropdownTextField {

    private final int maxLength;
    private final Predicate<String> inputFilter;
    private boolean focused;
    private String text = "";
    private int cursor;
    private float[] cursorMidpoints = new float[0];
    private float lastX, lastY, lastWidth, lastHeight;
    private boolean showContextMenu;
    private float menuX, menuY;

    // Mouse selection
    private int selStart = -1;
    private int selEnd = -1;
    private boolean selecting;
    private float lastTextScale;
    private float lastTextX;

    public DropdownTextField(int maxLength) {
        this(maxLength, value -> true);
    }

    public DropdownTextField(int maxLength, Predicate<String> inputFilter) {
        this.maxLength = maxLength;
        this.inputFilter = inputFilter == null ? value -> true : inputFilter;
    }

    // --- Draw ---

    public void draw(UiTree.Scope scope, UiTextMetrics textMetrics, float x, float y, float width, float height, int mouseX, int mouseY, String placeholder, float textScale) {
        lastX = x; lastY = y; lastWidth = width; lastHeight = height;
        lastTextScale = textScale;
        scope.roundRect(x, y, width, height, DropdownTheme.INPUT_RADIUS, DropdownTheme.inputSurface(focused));
        scope.outline(x, y, width, height, DropdownTheme.INPUT_RADIUS, 0.7f, focused ? MD3Theme.PRIMARY : MD3Theme.withAlpha(MD3Theme.OUTLINE, 90));

        boolean showPlaceholder = text.isEmpty() && !focused;
        String display = showPlaceholder ? placeholder : text;
        float textY = y + (height - textMetrics.textHeight(textScale)) / 2.0f;
        float textX = x + 4.0f;
        lastTextX = textX;
        updateCursorLayout(textMetrics, textX, textScale);
        scope.text(trimToWidth(display, textScale, width - 8.0f, textMetrics), textX, textY, textScale, showPlaceholder ? MD3Theme.TEXT_MUTED : MD3Theme.TEXT_PRIMARY);

        // Selection highlight
        if (focused && hasSelection()) {
            int low = Math.min(selStart, selEnd);
            int high = Math.max(selStart, selEnd);
            float selX1 = textX + textMetrics.textWidth(text.substring(0, low), textScale);
            float selX2 = textX + textMetrics.textWidth(text.substring(0, high), textScale);
            scope.rect(selX1, textY, selX2 - selX1, textMetrics.textHeight(textScale),
                    new Color(208, 188, 255, 70));
        }

        if (focused) {
            int safeCursor = Math.min(cursor, text.length());
            float caretX = textX + textMetrics.textWidth(text.substring(0, safeCursor), textScale);
            drawCaret(scope, textMetrics, caretX, textY, textScale);
            IMEFocusHelper.updateCursorPos(caretX, textY);
        }

        if (showContextMenu) {
            drawContextMenu(scope, textMetrics);
        }
    }

    public void drawCentered(UiTree.Scope scope, UiTextMetrics textMetrics, float x, float y, float width, float height, int mouseX, int mouseY, String placeholder, float textScale) {
        lastX = x; lastY = y; lastWidth = width; lastHeight = height;
        lastTextScale = textScale;
        scope.roundRect(x, y, width, height, DropdownTheme.INPUT_RADIUS, DropdownTheme.inputSurface(focused));
        scope.outline(x, y, width, height, DropdownTheme.INPUT_RADIUS, 0.7f, focused ? MD3Theme.PRIMARY : MD3Theme.withAlpha(MD3Theme.OUTLINE, 90));

        boolean showPlaceholder = text.isEmpty() && !focused;
        String display = showPlaceholder ? placeholder : text;
        float textY = y + (height - textMetrics.textHeight(textScale)) / 2.0f;
        String visibleText = trimToWidth(display, textScale, width - 8.0f, textMetrics);
        float textX = x + (width - textMetrics.textWidth(visibleText, textScale)) * 0.5f;
        float caretBaseX = x + (width - textMetrics.textWidth(text, textScale)) * 0.5f;
        lastTextX = caretBaseX;
        updateCursorLayout(textMetrics, caretBaseX, textScale);
        scope.text(visibleText, textX, textY, textScale, showPlaceholder ? MD3Theme.TEXT_MUTED : MD3Theme.TEXT_PRIMARY);

        if (focused) {
            int safeCursor = Math.min(cursor, text.length());
            String beforeCursor = text.substring(0, safeCursor);
            float caretX = caretBaseX + textMetrics.textWidth(beforeCursor, textScale);
            drawCaret(scope, textMetrics, caretX, textY, textScale);
            IMEFocusHelper.updateCursorPos(caretX, textY);
        }

        if (showContextMenu) {
            drawContextMenu(scope, textMetrics);
        }
    }

    // --- Focus ---

    public boolean focusIfContains(double mouseX, double mouseY, float x, float y, float width, float height) {
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) return false;
        focused = true;
        cursor = resolveCursor(mouseX);
        clearSelection();
        IMEFocusHelper.activate();
        return true;
    }

    public boolean focusIfContainsCentered(double mouseX, double mouseY, float x, float y, float width, float height) {
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) return false;
        focused = true;
        cursor = resolveCursor(mouseX);
        clearSelection();
        IMEFocusHelper.activate();
        return true;
    }

    public void focus() {
        focused = true;
        cursor = text.length();
        clearSelection();
        IMEFocusHelper.activate();
    }

    public void blur() {
        if (focused) {
            focused = false;
            clearSelection();
            IMEFocusHelper.deactivate();
        }
    }

    // --- Mouse ---

    public boolean isHovering(double mouseX, double mouseY) {
        return mouseX >= lastX && mouseX <= lastX + lastWidth && mouseY >= lastY && mouseY <= lastY + lastHeight;
    }

    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (!isHovering(mouseX, mouseY)) {
            if (focused) blur();
            return false;
        }
        if (!focused) {
            focused = true;
            IMEFocusHelper.activate();
        }
        selStart = resolveCursor(mouseX);
        selEnd = selStart;
        cursor = selStart;
        selecting = true;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY) {
        if (!selecting) return false;
        selEnd = resolveCursor(mouseX);
        cursor = selEnd;
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!selecting) return false;
        selecting = false;
        if (selStart == selEnd) {
            clearSelection();
        }
        return true;
    }

    public boolean rightClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        if (!isHovering(mouseX, mouseY)) {
            showContextMenu = false;
            return false;
        }
        if (!focused) {
            focused = true;
            cursor = resolveCursor(mouseX);
            IMEFocusHelper.activate();
        }
        showContextMenu = true;

        // Smart positioning: flip if menu would overflow bottom/right edges
        float menuW = 90f;
        float menuH = 36f;
        menuX = (float) mouseX;
        menuY = (float) mouseY;
        float screenW = mc.getWindow().getWidth() / (float) mc.getWindow().getGuiScale();
        float screenH = mc.getWindow().getHeight() / (float) mc.getWindow().getGuiScale();
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
            insertText(mc.keyboardHandler.getClipboard());
            showContextMenu = false;
            return true;
        }
        showContextMenu = false;
        return false;
    }

    public boolean isContextMenuOpen() {
        return showContextMenu;
    }

    public void closeContextMenu() {
        showContextMenu = false;
    }

    private void drawContextMenu(UiTree.Scope scope, UiTextMetrics textMetrics) {
        float itemH = 18f;
        float menuW = 90f;
        float menuH = itemH * 2;
        float scale = 0.54f;

        scope.roundRect(menuX, menuY, menuW, menuH, 6f, new Color(40, 36, 48, 248));
        scope.outline(menuX, menuY, menuW, menuH, 6f, 0.6f, MD3Theme.withAlpha(MD3Theme.PRIMARY, 50));

        // Copy
        float textY1 = menuY + (itemH - textMetrics.textHeight(scale)) / 2f;
        scope.text("Copy", menuX + 8f, textY1, scale, MD3Theme.TEXT_PRIMARY);

        // Paste
        float textY2 = menuY + itemH + (itemH - textMetrics.textHeight(scale)) / 2f;
        scope.text("Paste", menuX + 8f, textY2, scale, MD3Theme.TEXT_PRIMARY);

        scope.rect(menuX + 4f, menuY + itemH, menuW - 8f, 0.5f, MD3Theme.withAlpha(MD3Theme.OUTLINE, 60));
    }

    // --- Keyboard ---

    public boolean keyPressed(KeyEvent event) {
        return keyPressed(event.key());
    }

    public boolean keyPressed(int keyCode) {
        if (!focused) return false;
        if (isControlDown()) {
            return handleControlShortcut(keyCode);
        }
        return switch (keyCode) {
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
                if (isShiftDown() && selStart < 0) selStart = cursor + 1;
                if (isShiftDown()) selEnd = cursor;
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                cursor = Math.min(text.length(), cursor + 1);
                if (!isShiftDown()) clearSelection();
                if (isShiftDown() && selStart < 0) selStart = cursor - 1;
                if (isShiftDown()) selEnd = cursor;
                yield true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursor = 0;
                if (!isShiftDown()) clearSelection();
                if (isShiftDown() && selStart < 0) selStart = cursor;
                yield true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursor = text.length();
                if (!isShiftDown()) clearSelection();
                if (isShiftDown() && selStart < 0) selStart = cursor;
                yield true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> { blur(); yield true; }
            default -> false;
        };
    }

    public boolean charTyped(CharacterEvent event) {
        if (!focused) return false;
        return insertText(event.codepointAsString());
    }

    public boolean charTyped(String typedText) {
        if (!focused) return false;
        return insertText(typedText);
    }

    public boolean isFocused() {
        return focused;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = clamp(text == null ? "" : text);
        cursor = Math.min(cursor, this.text.length());
        clearSelection();
    }

    public void clear() {
        text = "";
        cursor = 0;
        clearSelection();
    }

    public void setCursorToEnd() {
        cursor = text.length();
        clearSelection();
    }

    // --- Internals ---

    private boolean hasSelection() {
        return selStart >= 0 && selEnd >= 0 && selStart != selEnd;
    }

    private void clearSelection() {
        selStart = -1;
        selEnd = -1;
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int low = Math.min(selStart, selEnd);
        int high = Math.max(selStart, selEnd);
        text = text.substring(0, low) + text.substring(high);
        cursor = low;
        clearSelection();
    }

    private boolean insertText(String inserted) {
        if (inserted == null || inserted.isEmpty()) return false;
        if (hasSelection()) deleteSelection();

        StringBuilder accepted = new StringBuilder();
        inserted.codePoints().forEach(codePoint -> {
            String candidate = new String(Character.toChars(codePoint));
            if (inputFilter.test(candidate)) accepted.append(candidate);
        });
        if (accepted.isEmpty()) return false;
        int available = maxLength - text.length();
        if (available <= 0) return false;
        String safe = accepted.length() > available ? accepted.substring(0, available) : accepted.toString();
        if (safe.isEmpty()) return false;
        text = text.substring(0, cursor) + safe + text.substring(cursor);
        cursor += safe.length();
        clearSelection();
        return true;
    }

    private boolean handleControlShortcut(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_A -> { cursor = 0; selStart = 0; selEnd = text.length(); yield true; }
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
            case GLFW.GLFW_KEY_V -> insertText(mc.keyboardHandler.getClipboard());
            default -> false;
        };
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

    private String clamp(String value) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private void updateCursorLayout(UiTextMetrics textMetrics, float textX, float textScale) {
        if (text.isEmpty()) {
            cursorMidpoints = new float[0];
            return;
        }
        if (cursorMidpoints.length != text.length()) {
            cursorMidpoints = new float[text.length()];
        }
        float left = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            float right = textMetrics.textWidth(text.substring(0, i + 1), textScale);
            cursorMidpoints[i] = textX + (left + right) * 0.5f;
            left = right;
        }
    }

    private int resolveCursor(double mouseX) {
        if (text.isEmpty()) return 0;
        if (cursorMidpoints.length != text.length()) return text.length();
        for (int i = 0; i < cursorMidpoints.length; i++) {
            if (mouseX < cursorMidpoints[i]) return i;
        }
        return text.length();
    }

    private void drawCaret(UiTree.Scope scope, UiTextMetrics textMetrics, float x, float y, float textScale) {
        if (System.currentTimeMillis() % 1000 > 500) {
            scope.rect(x, y, 0.8f, textMetrics.textHeight(textScale), MD3Theme.TEXT_PRIMARY);
        }
    }

    private String trimToWidth(String value, float scale, float maxWidth, UiTextMetrics textMetrics) {
        if (value == null || value.isEmpty()) return "";
        if (textMetrics.textWidth(value, scale) <= maxWidth) return value;
        String ellipsis = "...";
        float ellipsisWidth = textMetrics.textWidth(ellipsis, scale);
        if (ellipsisWidth >= maxWidth) return ellipsis;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String candidate = value.substring(0, mid) + ellipsis;
            if (textMetrics.textWidth(candidate, scale) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return value.substring(0, low) + ellipsis;
    }

}
