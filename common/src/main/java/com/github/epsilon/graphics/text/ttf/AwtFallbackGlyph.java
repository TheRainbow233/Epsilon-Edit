package com.github.epsilon.graphics.text.ttf;

import org.lwjgl.system.MemoryUtil;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Renders individual glyphs using AWT's system font fallback chain.
 * Used as a last-resort glyph source when STB TrueType fonts don't cover a codepoint.
 */
final class AwtFallbackGlyph {

    private static final Font AWT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 48);
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    /**
     * Generate an SDF-like glyph using AWT for a codepoint not covered by STB fonts.
     * Returns null if AWT also can't render it.
     */
    static TtfGlyph generate(int codepoint, TtfFontFile primaryFont) {
        String text = new String(Character.toChars(codepoint));
        if (!AWT_FONT.canDisplay(codepoint)) return null;

        GlyphVector gv = AWT_FONT.createGlyphVector(FRC, text);
        Rectangle2D bounds = gv.getVisualBounds();
        if (bounds.isEmpty()) return null;

        // Scale to match the primary font's pixel height
        float targetHeight = primaryFont.pixelAscent + Math.abs(primaryFont.fontHeight - primaryFont.pixelAscent);
        float awtHeight = (float) bounds.getHeight();
        if (awtHeight <= 0) awtHeight = 40f;
        float scale = targetHeight / awtHeight * 0.9f;

        int pad = 4;
        int width = (int) Math.ceil(bounds.getWidth() * scale) + pad * 2;
        int height = (int) Math.ceil(targetHeight) + pad * 2;
        if (width <= 0 || height <= 0) return null;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);

        // Position glyph: yOffset aligns with STB convention.
        // STB yOff = distance from cell top to baseline, negative = above baseline.
        // We set baseline at `pad + primaryFont.pixelAscent` from cell top.
        float baselineY = pad + primaryFont.pixelAscent;
        float x = pad - (float) bounds.getX() * scale;
        float y = baselineY;
        g.setFont(AWT_FONT.deriveFont(AWT_FONT.getSize() * scale));
        g.drawString(text, x, y);
        g.dispose();

        ByteBuffer buffer = MemoryUtil.memAlloc(width * height);
        int[] pixels = new int[width * height];
        img.getRGB(0, 0, width, height, pixels, 0, width);
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int gray = (pixels[py * width + px] >> 16) & 0xFF;
                buffer.put(py * width + px, (byte) gray);
            }
        }

        int advance = (int) (gv.getGlyphMetrics(0).getAdvanceX() * scale);
        // Use same offsets as typical STB glyphs — positions glyph at the top of the line
        int yOff = -(primaryFont.pixelAscent + pad - 3);
        int xOff = -(int) bounds.getX();

        return new TtfGlyph(buffer, width, height, xOff, yOff, advance);
    }
}
