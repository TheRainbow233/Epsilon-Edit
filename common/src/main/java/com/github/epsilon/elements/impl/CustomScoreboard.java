package com.github.epsilon.elements.impl;

import com.github.epsilon.elements.HudModule;
import com.github.epsilon.graphics.renderers.McTextRenderer;
import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.graphics.shaders.BlurShader;
import com.github.epsilon.gui.lib.UiTree;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.ColorSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.google.common.base.Suppliers;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

public class CustomScoreboard extends HudModule {

    public static final CustomScoreboard INSTANCE = new CustomScoreboard();

    private CustomScoreboard() {
        super("Scoreboard", 0f, 0f, 140f, 80f);
    }

    private final DoubleSetting scale = doubleSetting("Scale", 1.0, 0.5, 2.0, 0.05);
    private final ColorSetting backgroundColor = colorSetting("Background Color", new Color(15, 15, 15, 145));
    private final DoubleSetting cornerRadius = doubleSetting("Corner Radius", 6.0, 0.0, 20.0, 0.5);
    private final BoolSetting backgroundBlur = boolSetting("Background Blur", true);
    private final DoubleSetting blurStrength = doubleSetting("Blur Strength", 15.0, 1.0, 30.0, 1.0,
            backgroundBlur::getValue);
    private final BoolSetting drawShadow = boolSetting("Drop Shadow", true);
    private final DoubleSetting shadowBlur = doubleSetting("Shadow Blur", 8.0, 1.0, 30.0, 1.0,
            drawShadow::getValue);
    private final ColorSetting shadowColor = colorSetting("Shadow Color", new Color(0, 0, 0, 80),
            drawShadow::getValue);

    private static final float TITLE_SCALE = 0.66f;
    private static final float ENTRY_SCALE = 0.58f;
    private static final float PADDING_X = 10f;
    private static final float PADDING_Y = 8f;
    private static final float HEADER_GAP = 15f;
    private static final float ENTRY_GAP = 2f;

    private final Supplier<TextRenderer> trSupplier = Suppliers.memoize(TextRenderer::create);

    @Override
    public void render(DeltaTracker deltaTracker) {
        if (mc.level == null || mc.getConnection() == null) return;
        Scoreboard sb = mc.level.getScoreboard();
        if (sb == null) return;

        Objective sidebar = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return;
        var displayName = sidebar.getDisplayName();
        if (displayName == null) return;

        List<PlayerScoreEntry> entries = new ArrayList<>();
        var scores = sb.listPlayerScores(sidebar);
        if (scores != null)
            for (PlayerScoreEntry e : scores) if (!e.isHidden()) entries.add(e);
        if (entries.isEmpty()) return;
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

        record Row(Component nameComp, String plainName, String score) {}
        List<Row> rows = new ArrayList<>();
        for (PlayerScoreEntry e : entries) {
            var team = sb.getPlayersTeam(e.owner());
            var raw = e.ownerName() != null ? e.ownerName() : Component.literal(e.owner());
            Component comp = team != null ? team.getFormattedName(raw) : raw;
            rows.add(new Row(comp, comp.getString().replaceAll("§.", ""), String.valueOf(e.value())));
        }

        float s = scale.getValue().floatValue();
        float titleScale = TITLE_SCALE * s;
        float entryScale = ENTRY_SCALE * s;
        float px = PADDING_X * s;
        float py = PADDING_Y * s;
        float hGap = HEADER_GAP * s;
        float entryGap = ENTRY_GAP * s;

        TextRenderer tr = trSupplier.get();
        String titlePlain = displayName.getString().replaceAll("§.", "");

        float titleW = tr.getWidth(titlePlain, titleScale);
        float titleH = tr.getHeight(titleScale);
        float entryH = tr.getHeight(entryScale);
        float maxW = titleW;
        for (Row r : rows)
            maxW = Math.max(maxW, tr.getWidth(r.plainName(), entryScale) + tr.getWidth(r.score(), entryScale) + 8f * s);

        float totalW = maxW + px * 2f;
        float totalH = py * 2f + titleH + hGap + rows.size() * (entryH + entryGap) - entryGap;
        float radius = cornerRadius.getValue().floatValue() * s;

        UiTree.Scope scope = renderScope();

        if (drawShadow.getValue())
            scope.shadow(this.x, this.y, totalW, totalH, radius, shadowBlur.getValue().floatValue(), shadowColor.getValue());
        if (backgroundBlur.getValue())
            BlurShader.INSTANCE.render(this.x, this.y, totalW, totalH, radius, blurStrength.getValue().floatValue());
        scope.roundRect(this.x, this.y, totalW, totalH, radius, backgroundColor.getValue());

        float headerH = titleH + py * 2f;
        scope.roundRect(this.x, this.y, totalW, headerH, radius, new Color(0, 0, 0, 50));

        McTextRenderer.addComponent(scope, tr, displayName,
                this.x + (totalW - titleW) / 2f, this.y + py, titleScale);

        float y = this.y + py + titleH + hGap;
        for (Row r : rows) {
            float sw = tr.getWidth(r.score(), entryScale);
            McTextRenderer.addComponent(scope, tr, r.nameComp(), this.x + px, y, entryScale);
            scope.text(r.score(), this.x + totalW - px - sw, y, entryScale, new Color(255, 85, 85, 230));
            y += entryH + entryGap;
        }

        setBounds(totalW, totalH);
    }

}
