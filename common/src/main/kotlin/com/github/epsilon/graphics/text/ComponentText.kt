package com.github.epsilon.graphics.text

import com.github.epsilon.graphics.renderers.TextRenderer
import java.awt.Color
import java.util.Optional
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

/**
 * Minecraft 文本组件（含 § 传统色码）到同色文本段的解析工具。
 *
 * 返回的 [Run] 按颜色合并并自带宽度，调用方可直接按顺序绘制，从而保留团队色、
 * 组件样式与 §0-§f / §x§R§R§G§G§B§B 色码。
 */
object ComponentText {

    data class Run(val text: String, val color: Color, val width: Float)

    /** 解析单个组件为一组按颜色合并的文本段。 */
    fun runs(component: Component, fallback: Color, scale: Float, renderer: TextRenderer): List<Run> {
        val raw = ArrayList<Run>(8)
        component.visit(
            { style: Style, text: String ->
                val color = resolveStyleColor(style, fallback)
                parseLegacy(raw, text, color, color, scale, renderer)
                Optional.empty<Any>()
            },
            Style.EMPTY,
        )
        return merge(raw, scale, renderer)
    }

    /** 解析组件并按换行拆分为多行，空行会被丢弃。 */
    fun splitLines(
        component: Component?,
        fallback: Color,
        scale: Float,
        renderer: TextRenderer,
    ): List<List<Run>> {
        if (component == null) return emptyList()

        val lines = ArrayList<MutableList<Run>>()
        lines.add(ArrayList())
        component.visit(
            { style: Style, text: String ->
                val color = resolveStyleColor(style, fallback)
                parseLegacyIntoLines(lines, text, color, color, scale, renderer)
                Optional.empty<Any>()
            },
            Style.EMPTY,
        )

        val result = ArrayList<List<Run>>(lines.size)
        for (rawLine in lines) {
            val line = merge(rawLine, scale, renderer)
            if (line.all { it.text.isBlank() }) continue
            result.add(line)
        }
        return result
    }

    private fun resolveStyleColor(style: Style, fallback: Color): Color {
        val textColor = style.color
        return if (textColor == null) fallback else Color(textColor.value)
    }

    private fun merge(raw: List<Run>, scale: Float, renderer: TextRenderer): List<Run> {
        if (raw.isEmpty()) return emptyList()

        val merged = ArrayList<Run>(raw.size)
        val builder = StringBuilder()
        var color = raw.first().color

        fun flush() {
            if (builder.isEmpty()) return
            val text = builder.toString()
            builder.setLength(0)
            merged.add(Run(text, color, renderer.getWidth(text, scale)))
        }

        for (run in raw) {
            if (run.color == color) {
                builder.append(run.text)
                continue
            }
            flush()
            color = run.color
            builder.append(run.text)
        }
        flush()
        return merged
    }

    private fun parseLegacy(
        output: MutableList<Run>,
        text: String?,
        baseColor: Color,
        resetColor: Color,
        scale: Float,
        renderer: TextRenderer,
    ) {
        if (text.isNullOrEmpty()) return
        var currentColor = baseColor
        val builder = StringBuilder()

        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\u00a7' && index + 1 < text.length) {
                val code = Character.toLowerCase(text[index + 1])
                if (code == 'x') {
                    val parsed = parseLegacyHexColor(text, index)
                    if (parsed != null) {
                        flushRun(output, builder, currentColor, scale, renderer)
                        currentColor = parsed
                        index += 14
                        continue
                    }
                }
                val mapped = legacyColor(code)
                if (mapped != null) {
                    flushRun(output, builder, currentColor, scale, renderer)
                    currentColor = mapped
                    index += 2
                    continue
                }
                if (code == 'r') {
                    flushRun(output, builder, currentColor, scale, renderer)
                    currentColor = resetColor
                }
                index += 2
                continue
            }
            builder.append(if (character == '\n') ' ' else character)
            index++
        }
        flushRun(output, builder, currentColor, scale, renderer)
    }

    private fun parseLegacyIntoLines(
        lines: MutableList<MutableList<Run>>,
        text: String?,
        baseColor: Color,
        resetColor: Color,
        scale: Float,
        renderer: TextRenderer,
    ) {
        if (text.isNullOrEmpty()) return
        var currentColor = baseColor
        val builder = StringBuilder()

        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\n') {
                flushRun(lines.last(), builder, currentColor, scale, renderer)
                lines.add(ArrayList())
                currentColor = baseColor
                index++
                continue
            }
            if (character == '\u00a7' && index + 1 < text.length) {
                val code = Character.toLowerCase(text[index + 1])
                if (code == 'x') {
                    val parsed = parseLegacyHexColor(text, index)
                    if (parsed != null) {
                        flushRun(lines.last(), builder, currentColor, scale, renderer)
                        currentColor = parsed
                        index += 14
                        continue
                    }
                }
                val mapped = legacyColor(code)
                if (mapped != null) {
                    flushRun(lines.last(), builder, currentColor, scale, renderer)
                    currentColor = mapped
                    index += 2
                    continue
                }
                if (code == 'r') {
                    flushRun(lines.last(), builder, currentColor, scale, renderer)
                    currentColor = resetColor
                }
                index += 2
                continue
            }
            builder.append(character)
            index++
        }
        flushRun(lines.last(), builder, currentColor, scale, renderer)
    }

    private fun flushRun(
        output: MutableList<Run>,
        builder: StringBuilder,
        color: Color,
        scale: Float,
        renderer: TextRenderer,
    ) {
        if (builder.isEmpty()) return
        val text = builder.toString()
        builder.setLength(0)
        output.add(Run(text, color, renderer.getWidth(text, scale)))
    }

    private fun legacyColor(code: Char): Color? {
        return when (code) {
            '0' -> Color(0x000000)
            '1' -> Color(0x0000aa)
            '2' -> Color(0x00aa00)
            '3' -> Color(0x00aaaa)
            '4' -> Color(0xaa0000)
            '5' -> Color(0xaa00aa)
            '6' -> Color(0xffaa00)
            '7' -> Color(0xaaaaaa)
            '8' -> Color(0x555555)
            '9' -> Color(0x5555ff)
            'a' -> Color(0x55ff55)
            'b' -> Color(0x55ffff)
            'c' -> Color(0xff5555)
            'd' -> Color(0xff55ff)
            'e' -> Color(0xffff55)
            'f' -> Color.WHITE
            else -> null
        }
    }

    private fun parseLegacyHexColor(text: String, sectionIndex: Int): Color? {
        if (sectionIndex < 0 || sectionIndex + 13 >= text.length) return null
        if (text[sectionIndex] != '\u00a7' || Character.toLowerCase(text[sectionIndex + 1]) != 'x') {
            return null
        }

        val hex = CharArray(6)
        var index = sectionIndex + 2
        for (i in hex.indices) {
            if (index + 1 >= text.length || text[index] != '\u00a7') return null
            val digit = text[index + 1]
            if (Character.digit(digit, 16) < 0) return null
            hex[i] = digit
            index += 2
        }

        return try {
            Color(Integer.parseInt(String(hex), 16))
        } catch (ignored: NumberFormatException) {
            null
        }
    }

}
