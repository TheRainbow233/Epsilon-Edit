package com.github.epsilon.elements.impl

import com.github.epsilon.elements.HudModule
import com.github.epsilon.graphics.renderers.TextRenderer
import com.github.epsilon.graphics.shaders.BlurShader
import com.github.epsilon.graphics.text.ComponentText
import com.github.epsilon.gui.lib.RoundRect
import com.github.epsilon.gui.lib.Shadow
import com.github.epsilon.gui.lib.Text
import com.github.epsilon.gui.lib.UiScope
import com.github.epsilon.kernel.jvm.runSafe
import com.google.common.base.Suppliers
import net.minecraft.client.DeltaTracker
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import java.awt.Color
import java.util.function.Supplier

object CustomScoreboard : HudModule("Scoreboard", 0f, 0f, 140f, 80f) {

    private val scale by doubleSetting("Scale", 1.0, 0.5..2.0, 0.05)
    private val backgroundColor by colorSetting("Background Color", Color(15, 15, 15, 145))
    private val cornerRadius by doubleSetting("Corner Radius", 6.0, 0.0..20.0, 0.5)
    private val backgroundBlur by boolSetting("Background Blur", true)
    private val blurStrength by doubleSetting("Blur Strength", 15.0, 1.0..30.0, 1.0, { backgroundBlur })
    private val drawShadow by boolSetting("Drop Shadow", true)
    private val shadowBlur by doubleSetting("Shadow Blur", 8.0, 1.0..30.0, 1.0, { drawShadow })
    private val shadowColor by colorSetting("Shadow Color", Color(0, 0, 0, 80), true, { drawShadow })

    private const val TITLE_SCALE = 0.66f
    private const val ENTRY_SCALE = 0.58f
    private const val PADDING_X = 10f
    private const val PADDING_Y = 8f
    private const val HEADER_GAP = 15f
    private const val ENTRY_GAP = 2f

    private val textRenderer: Supplier<TextRenderer> = Suppliers.memoize { TextRenderer.create() }

    private data class Row(
        val name: List<ComponentText.Run>,
        val score: String,
        val scoreWidth: Float,
    )

    private data class Layout(
        val scale: Float,
        val width: Float,
        val height: Float,
        val radius: Float,
        val headerHeight: Float,
        val title: List<ComponentText.Run>,
        val titleWidth: Float,
        val titleHeight: Float,
        val rows: List<Row>,
        val entryHeight: Float,
    )

    private var visible = false
    private var layout: Layout? = null

    override fun measure(deltaTracker: DeltaTracker) {
        visible = false
        layout = null
        runSafe { buildLayout() }
    }

    private fun buildLayout() {
        val clientLevel = mc.level ?: return
        if (mc.connection == null) return

        val scoreboard = clientLevel.scoreboard
        val sidebar: Objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return
        val displayName: Component = sidebar.displayName ?: return

        val entries = scoreboard.listPlayerScores(sidebar)
            .filter { !it.isHidden }
            .sortedByDescending { it.value }
        if (entries.isEmpty()) return

        val renderer = textRenderer.get()
        val s = scale.toFloat()
        val titleScale = TITLE_SCALE * s
        val entryScale = ENTRY_SCALE * s

        val title = ComponentText.runs(displayName, Color.WHITE, titleScale, renderer)
        if (title.isEmpty()) return
        val titleWidth = title.sumOf { it.width.toDouble() }.toFloat()
        val titleHeight = renderer.getHeight(titleScale)

        val rows = entries.map { entry: PlayerScoreEntry ->
            val team = scoreboard.getPlayersTeam(entry.owner)
            val raw = entry.ownerName()
            val formatted = team?.getFormattedName(raw) ?: raw
            val name = ComponentText.runs(formatted, Color.WHITE, entryScale, renderer)
            val score = entry.value.toString()
            Row(name, score, renderer.getWidth(score, entryScale))
        }

        var maxWidth = titleWidth
        for (row in rows) {
            val nameWidth = row.name.sumOf { it.width.toDouble() }.toFloat()
            maxWidth = maxOf(maxWidth, nameWidth + row.scoreWidth + 8f * s)
        }

        val entryHeight = renderer.getHeight(entryScale)
        val totalWidth = maxWidth + PADDING_X * s * 2f
        val totalHeight = PADDING_Y * s * 2f + titleHeight + HEADER_GAP * s +
                rows.size * (entryHeight + ENTRY_GAP * s) - ENTRY_GAP * s

        setBounds(totalWidth, totalHeight)
        layout = Layout(
            scale = s,
            width = totalWidth,
            height = totalHeight,
            radius = cornerRadius.toFloat() * s,
            headerHeight = titleHeight + PADDING_Y * s * 2f,
            title = title,
            titleWidth = titleWidth,
            titleHeight = titleHeight,
            rows = rows,
            entryHeight = entryHeight,
        )
        visible = true
    }

    override fun UiScope.drawUi(deltaTracker: DeltaTracker) {
        if (!visible) return
        val frame = layout ?: return

        if (backgroundBlur) {
            BlurShader.render(
                origin.x,
                origin.y,
                frame.width,
                frame.height,
                frame.radius,
                blurStrength.toFloat()
            )
        }
        if (drawShadow) {
            Shadow(
                0f, 0f, frame.width, frame.height, frame.radius,
                shadowBlur.toFloat(), shadowColor
            )
        }
        RoundRect(0f, 0f, frame.width, frame.height, frame.radius, backgroundColor)
        RoundRect(0f, 0f, frame.width, frame.headerHeight, frame.radius, Color(0, 0, 0, 50))

        var titleX = (frame.width - frame.titleWidth) / 2f
        for (run in frame.title) {
            Text(run.text, titleX, PADDING_Y * frame.scale, TITLE_SCALE * frame.scale, run.color)
            titleX += run.width
        }

        var rowY = PADDING_Y * frame.scale + frame.titleHeight + HEADER_GAP * frame.scale
        for (row in frame.rows) {
            var nameX = PADDING_X * frame.scale
            for (run in row.name) {
                Text(run.text, nameX, rowY, ENTRY_SCALE * frame.scale, run.color)
                nameX += run.width
            }
            Text(
                row.score,
                frame.width - PADDING_X * frame.scale - row.scoreWidth,
                rowY,
                ENTRY_SCALE * frame.scale,
                Color(255, 85, 85, 230)
            )
            rowY += frame.entryHeight + ENTRY_GAP * frame.scale
        }
    }

}
