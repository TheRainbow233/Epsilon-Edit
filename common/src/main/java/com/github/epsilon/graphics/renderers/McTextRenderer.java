package com.github.epsilon.graphics.renderers;

import com.github.epsilon.gui.lib.UiTree;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringDecomposer;

import java.awt.*;

/**
 * Renders Minecraft {@link Component} text through Epsilon's UiTree text pipeline,
 * with correct §-code formatting (color).
 */
public class McTextRenderer {

    /**
     * Render a Component's text into the given scope at (x, y) with scale.
     * Uses the provided TextRenderer for width measurement, ensuring consistency
     * with the same font used by scope.text().
     */
    public static void addComponent(UiTree.Scope scope, TextRenderer tr, Component component,
                                    float x, float y, float scale) {
        if (component == null) return;

        StringBuilder buf = new StringBuilder();
        Color[] currentColor = {WHITE};
        float[] cursor = {x};

        StringDecomposer.iterateFormatted(component, Style.EMPTY, (pos, style, codepoint) -> {
            Color c = resolveColor(style);
            if (Character.isWhitespace(codepoint)) {
                flush(scope, tr, buf, cursor, y, scale, currentColor[0]);
                cursor[0] += tr.getWidth(" ", scale);
                return true;
            }
            if (!c.equals(currentColor[0]) && buf.length() > 0) {
                flush(scope, tr, buf, cursor, y, scale, currentColor[0]);
            }
            currentColor[0] = c;
            buf.appendCodePoint(codepoint);
            return true;
        });
        flush(scope, tr, buf, cursor, y, scale, currentColor[0]);
    }

    private static void flush(UiTree.Scope scope, TextRenderer tr, StringBuilder buf,
                              float[] cursor, float y, float scale, Color color) {
        if (buf.length() == 0) return;
        String text = buf.toString();
        scope.text(text, cursor[0], y, scale, color);
        cursor[0] += tr.getWidth(text, scale);
        buf.setLength(0);
    }

    private static Color resolveColor(Style style) {
        var tc = style.getColor();
        if (tc != null) return new Color(tc.getValue() | 0xFF000000, true);
        return WHITE;
    }

    private static final Color WHITE = new Color(255, 255, 255, 255);
}
