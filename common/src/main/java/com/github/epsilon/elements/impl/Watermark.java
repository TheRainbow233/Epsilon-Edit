package com.github.epsilon.elements.impl;

import com.github.epsilon.Constants;
import com.github.epsilon.elements.HudModule;
import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.graphics.shaders.BlurShader;
import com.github.epsilon.graphics.text.StaticFontLoader;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.ColorSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.StringSetting;
import com.google.common.base.Suppliers;
import net.minecraft.client.DeltaTracker;

import java.awt.*;
import java.util.function.Supplier;

public class Watermark extends HudModule {

    public static final Watermark INSTANCE = new Watermark();

    private Watermark() {
        super("Watermark", 0f, 0f, 200f, 28f);
    }

    private enum StyleMode {
        Rect,
        Text
    }

    private final EnumSetting<StyleMode> styleSetting = enumSetting("Style", StyleMode.Rect);

    private final StringSetting renderText = stringSetting("Render Text", "EPSILON");
    private final DoubleSetting scale = doubleSetting("Scale", 0.5, 0.5, 2.0, 0.1);
    private final ColorSetting textColor = colorSetting("Text Color", new Color(255, 255, 255, 235));

    // Rect mode settings
    private final ColorSetting backgroundColor = colorSetting("Background Color",
            new Color(15, 15, 15, 145), () -> styleSetting.is(StyleMode.Rect));
    private final DoubleSetting cornerRadius = doubleSetting("Corner Radius", 4.0, 0.0, 20.0, 0.5,
            () -> styleSetting.is(StyleMode.Rect));
    private final BoolSetting drawShadow = boolSetting("Drop Shadow", true,
            () -> styleSetting.is(StyleMode.Rect));
    private final DoubleSetting shadowBlur = doubleSetting("Shadow Blur", 2.2, 0.1, 32.0, 0.5,
            () -> styleSetting.is(StyleMode.Rect) && drawShadow.getValue());
    private final ColorSetting shadowColor = colorSetting("Shadow Color", new Color(0, 0, 0, 80),
            () -> styleSetting.is(StyleMode.Rect) && drawShadow.getValue());
    private final BoolSetting backgroundBlur = boolSetting("Background Blur", true,
            () -> styleSetting.is(StyleMode.Rect));
    private final DoubleSetting blurStrength = doubleSetting("Blur Strength", 15.0, 1.0, 30.0, 1.0,
            () -> styleSetting.is(StyleMode.Rect) && backgroundBlur.getValue());

    private final Supplier<TextRenderer> textRendererSupplier = Suppliers.memoize(TextRenderer::create);

    @Override
    public void render(DeltaTracker deltaTracker) {
        TextRenderer textRenderer = textRendererSupplier.get();
        float s = scale.getValue().floatValue() * 2f;
        float padding = 6f * s;
        float radius = cornerRadius.getValue().floatValue() * s;

        String text = styleSetting.is(StyleMode.Rect)
                ? renderText.getValue() + " | " + Constants.VERSION
                : renderText.getValue();

        float textWidth = textRenderer.getWidth(text, s, StaticFontLoader.DEFAULT);
        float textHeight = textRenderer.getHeight(s, StaticFontLoader.DEFAULT);
        float totalWidth = textWidth + padding * 2f;
        float totalHeight = textHeight + padding * 2f;

        if (styleSetting.is(StyleMode.Rect)) {
            if (drawShadow.getValue()) {
                renderScope().shadow(this.x, this.y, totalWidth, totalHeight, radius,
                        shadowBlur.getValue().floatValue(), shadowColor.getValue());
            }
            if (backgroundBlur.getValue()) {
                BlurShader.INSTANCE.render(this.x, this.y, totalWidth, totalHeight, radius,
                        blurStrength.getValue().floatValue());
            }
            renderScope().roundRect(this.x, this.y, totalWidth, totalHeight, radius,
                    backgroundColor.getValue());
        }

        float textX = styleSetting.is(StyleMode.Rect) ? this.x + padding : this.x;
        float textY = styleSetting.is(StyleMode.Rect)
                ? this.y + (totalHeight - textHeight) / 2f
                : this.y;

        renderScope().text(text, textX, textY, s, textColor.getValue(), StaticFontLoader.DEFAULT);

        setBounds(totalWidth, totalHeight);
    }

}
