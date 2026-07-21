package com.github.epsilon.graphics.text.ttf;

import com.github.epsilon.graphics.LuminRenderPipelines;
import com.github.epsilon.graphics.LuminRenderSystem;
import com.github.epsilon.graphics.buffer.BufferUtils;
import com.github.epsilon.graphics.buffer.LuminRingBuffer;
import com.github.epsilon.graphics.text.GlyphDescriptor;
import com.github.epsilon.graphics.text.ITextRenderer;
import com.github.epsilon.graphics.text.SystemEmojiAtlas;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.utils.render.ScissorUtils;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.util.ARGB;
import org.lwjgl.system.MemoryUtil;

import java.awt.*;
import java.util.*;
import java.util.List;

public class TtfTextRenderer implements ITextRenderer {

    private static final float DEFAULT_SCALE = 0.35f;
    private static final float SPACING = 0f;
    private static final int STRIDE = 24;
    private static final long GLYPH_BYTES = STRIDE * 4L;
    private static final int LAYOUT_FLOATS_PER_GLYPH = 10;
    private static final int LAYOUT_CACHE_LIMIT = 256;
    private static final int WIDTH_CACHE_LIMIT = 256;
    private static final float SPACE_WIDTH = 3.0f;
    private final long bufferSize;

    private final Map<TtfGlyphAtlas, Batch> batches = new LinkedHashMap<>();
    private EmojiBatch emojiBatch;
    // 缓存与 scale 无关的布局数据，绘制时只做平移和缩放。
    private final Map<LayoutKey, TextLayout> layoutCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LayoutKey, TextLayout> eldest) {
            return size() > LAYOUT_CACHE_LIMIT;
        }
    };
    // 宽度测量调用很密集，单独缓存避免为了布局缓存而请求/上传字形。
    private final Map<LayoutKey, Float> widthCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LayoutKey, Float> eldest) {
            return size() > WIDTH_CACHE_LIMIT;
        }
    };

    private boolean scissorEnabled = false;
    private int scissorX, scissorY, scissorW, scissorH;
    private GpuBufferSlice sharedDynamicUniforms;
    private int sharedMaxIndexCount;

    public TtfTextRenderer(long bufferSize) {
        this.bufferSize = bufferSize;
    }

    public TtfTextRenderer() {
        this(256 * 1024);
    }

    @Override
    public void addText(String text, float x, float y, float scale, Color color, TtfFontLoader fontLoader) {
        if (text.isEmpty() || color.getAlpha() == 0) return;

        fontLoader.prepareChars(text);
        emitLayout(layoutFor(text, fontLoader), x, y, scale, ARGB.toABGR(color.getRGB()));
    }

    @Override
    public void addRotatedText(String text, float x, float y, float scale, Color color, TtfFontLoader fontLoader, float originX, float originY, float rotationDegrees) {
        if (text.isEmpty() || color.getAlpha() == 0) return;

        fontLoader.prepareChars(text);
        emitRotatedLayout(layoutFor(text, fontLoader), x, y, scale, ARGB.toABGR(color.getRGB()), originX, originY, rotationDegrees);
    }

    @Override
    public void addGradientText(String text, float x, float y, float scale, Color startColor, Color endColor, TtfFontLoader fontLoader) {
        if (text.isEmpty() || startColor.getAlpha() == 0 && endColor.getAlpha() == 0) return;

        fontLoader.prepareChars(text);
        TextLayout layout = layoutFor(text, fontLoader);
        float totalWidth = layout.complete ? layout.width : baseWidth(text, fontLoader);
        emitGradientLayout(layout, x, y, scale, totalWidth, startColor.getRGB(), endColor.getRGB());
    }

    private TextLayout layoutFor(String text, TtfFontLoader fontLoader) {
        long revision = fontLoader.getGlyphRevision();
        long atlasRevision = fontLoader.getAtlasRevision();
        LayoutKey key = new LayoutKey(fontLoader, text, fontLoader.getRenderScale());
        TextLayout cached = layoutCache.get(key);
        // 完整布局可跨无关 glyph 加载复用；未完成布局等待 glyphRevision 变化后重建。
        if (cached != null && cached.atlasRevision == atlasRevision && (cached.complete || cached.glyphRevision == revision)) {
            return cached;
        }

        TextLayout layout = buildLayout(text, fontLoader, revision, atlasRevision);
        layoutCache.put(key, layout);
        return layout;
    }

    private TextLayout buildLayout(String text, TtfFontLoader fontLoader, long revision, long atlasRevision) {
        // 坐标统一存 DEFAULT_SCALE 下的基础值，emit 时再乘调用方传入的 scale。
        Map<TtfGlyphAtlas, LayoutRunBuilder> runBuilders = new LinkedHashMap<>();
        float xOffset = 0.0f;
        float yOffset = 0.0f;
        float maxLine = 0.0f;
        float fontScale = fontLoader.getRenderScale();
        float scaledFont = DEFAULT_SCALE * fontScale;
        float ascent = fontLoader.fontFile.pixelAscent * scaledFont;
        float lineHeight = fontLoader.fontFile.fontHeight * scaledFont;
        float spaceWidth = SPACE_WIDTH * fontScale;
        boolean complete = true;
        int glyphCount = 0;
        List<float[]> emojiEntries = null;

        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint == ' ') {
                xOffset += spaceWidth;
                continue;
            }
            if (codepoint == '\n') {
                maxLine = Math.max(maxLine, xOffset);
                xOffset = 0.0f;
                yOffset += lineHeight;
                continue;
            }

            // Emoji codepoint → use SystemEmojiAtlas
            // isEmojiPresentation: default-emoji codepoints (😀🎉💯)
            // isEmoji: broader set including keycap bases — skip ASCII to avoid false positives
            if (codepoint > 127 && (Character.isEmojiPresentation(codepoint) || Character.isEmoji(codepoint))) {
                String emojiStr = new String(Character.toChars(codepoint));
                SystemEmojiAtlas.EmojiGlyph emojiGlyph = SystemEmojiAtlas.INSTANCE.get(emojiStr);
                if (emojiGlyph != null) {
                    float emojiSize = ascent * 1.25f;
                    float x1 = xOffset;
                    float x2 = x1 + emojiSize;
                    float y1 = yOffset + ascent - emojiSize + emojiSize * 0.15f;
                    float y2 = y1 + emojiSize;
                    if (emojiEntries == null) emojiEntries = new ArrayList<>();
                    emojiEntries.add(new float[]{x1, y1, x2, y2,
                            emojiGlyph.u0(), emojiGlyph.v0(), emojiGlyph.u1(), emojiGlyph.v1()});
                    xOffset += emojiSize + SPACING;
                    glyphCount++;
                    continue;
                }
            }

            GlyphDescriptor glyph = fontLoader.getGlyph(codepoint);
            if (glyph == null) {
                complete = false;
                continue;
            }

            float x1 = xOffset + glyph.xOffset() * scaledFont;
            float x2 = x1 + glyph.width() * scaledFont;
            float y1 = yOffset + ascent + glyph.yOffset() * scaledFont;
            float y2 = y1 + glyph.height() * scaledFont;
            float advance = glyph.advance() * scaledFont + SPACING;

            runBuilders.computeIfAbsent(glyph.atlas(), LayoutRunBuilder::new)
                    .add(x1, y1, x2, y2, glyph.uv(), xOffset, xOffset + glyph.advance() * scaledFont);

            xOffset += advance;
            glyphCount++;
        }

        maxLine = Math.max(maxLine, xOffset);

        LayoutRun[] runs = new LayoutRun[runBuilders.size()];
        int index = 0;
        for (LayoutRunBuilder builder : runBuilders.values()) {
            runs[index++] = builder.build();
        }

        return new TextLayout(runs, glyphCount, maxLine, complete, revision, atlasRevision, emojiEntries);
    }

    private void emitLayout(TextLayout layout, float x, float y, float scale, int argb) {
        // TTF glyphs
        for (LayoutRun run : layout.runs) {
            if (run.glyphCount == 0) continue;
            Batch batch = batchFor(run.atlas);
            long p = batch.beginWrite(run.glyphCount);
            float[] data = run.data;
            for (int i = 0; i < run.glyphCount; i++) {
                writeGlyph(p, x, y, scale, data, i * LAYOUT_FLOATS_PER_GLYPH, argb, argb);
                p += GLYPH_BYTES;
            }
        }

        // Emoji glyphs — only if SystemEmojiAtlas has a valid texture
        if (layout.emojiEntries != null && layout.emojiEntries.size() > 0
                && SystemEmojiAtlas.INSTANCE.getTexture() != null) {
            if (emojiBatch == null) {
                emojiBatch = new EmojiBatch(new LuminRingBuffer(bufferSize, GpuBuffer.USAGE_VERTEX));
            }
            int count = layout.emojiEntries.size();
            long p = emojiBatch.beginWrite(count);
            for (float[] e : layout.emojiEntries) {
                writeEmojiGlyph(p, x, y, scale, e, 0xFFFFFFFF);
                p += GLYPH_BYTES;
            }
        }
    }

    private void emitRotatedLayout(TextLayout layout, float x, float y, float scale, int argb, float originX, float originY, float rotationDegrees) {
        if (layout.glyphCount == 0) return;

        float radians = (float) Math.toRadians(rotationDegrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        for (LayoutRun run : layout.runs) {
            Batch batch = batchFor(run.atlas);
            long p = batch.beginWrite(run.glyphCount);
            float[] data = run.data;
            for (int i = 0; i < run.glyphCount; i++) {
                writeRotatedGlyph(p, x, y, scale, data, i * LAYOUT_FLOATS_PER_GLYPH, argb, argb, originX, originY, cos, sin);
                p += GLYPH_BYTES;
            }
        }
    }

    private void emitGradientLayout(TextLayout layout, float x, float y, float scale, float width, int startArgb, int endArgb) {
        if (layout.glyphCount == 0) return;

        float totalWidth = Math.max(width, 1.0f);
        for (LayoutRun run : layout.runs) {
            Batch batch = batchFor(run.atlas);
            long p = batch.beginWrite(run.glyphCount);
            float[] data = run.data;
            for (int i = 0; i < run.glyphCount; i++) {
                int base = i * LAYOUT_FLOATS_PER_GLYPH;
                int leftArgb = ARGB.toABGR(ARGB.srgbLerp(clamp01(data[base + 8] / totalWidth), startArgb, endArgb));
                int rightArgb = ARGB.toABGR(ARGB.srgbLerp(clamp01(data[base + 9] / totalWidth), startArgb, endArgb));
                writeGlyph(p, x, y, scale, data, base, leftArgb, rightArgb);
                p += GLYPH_BYTES;
            }
        }
    }

    private Batch batchFor(TtfGlyphAtlas atlas) {
        return batches.computeIfAbsent(atlas, k -> new Batch(new LuminRingBuffer(bufferSize, GpuBuffer.USAGE_VERTEX)));
    }

    private static void writeGlyph(long p, float x, float y, float scale, float[] data, int base, int leftArgb, int rightArgb) {
        float x1 = x + data[base] * scale;
        float y1 = y + data[base + 1] * scale;
        float x2 = x + data[base + 2] * scale;
        float y2 = y + data[base + 3] * scale;
        float u0 = data[base + 4];
        float v0 = data[base + 5];
        float u1 = data[base + 6];
        float v1 = data[base + 7];

        BufferUtils.writeUvRectToAddr(p, x1, y1, u0, v0, leftArgb);
        BufferUtils.writeUvRectToAddr(p + STRIDE, x1, y2, u0, v1, leftArgb);
        BufferUtils.writeUvRectToAddr(p + STRIDE * 2L, x2, y2, u1, v1, rightArgb);
        BufferUtils.writeUvRectToAddr(p + STRIDE * 3L, x2, y1, u1, v0, rightArgb);
    }

    private static void writeEmojiGlyph(long p, float x, float y, float scale, float[] data, int argb) {
        float x1 = x + data[0] * scale;
        float y1 = y + data[1] * scale;
        float x2 = x + data[2] * scale;
        float y2 = y + data[3] * scale;
        float u0 = data[4];
        float v0 = data[5];
        float u1 = data[6];
        float v1 = data[7];

        // Emoji uses WHITE tint — its own RGBA colors show through
        BufferUtils.writeUvRectToAddr(p, x1, y1, u0, v0, argb);
        BufferUtils.writeUvRectToAddr(p + STRIDE, x1, y2, u0, v1, argb);
        BufferUtils.writeUvRectToAddr(p + STRIDE * 2L, x2, y2, u1, v1, argb);
        BufferUtils.writeUvRectToAddr(p + STRIDE * 3L, x2, y1, u1, v0, argb);
    }

    private static void writeRotatedGlyph(long p, float x, float y, float scale, float[] data, int base, int leftArgb, int rightArgb, float originX, float originY, float cos, float sin) {
        float x1 = x + data[base] * scale;
        float y1 = y + data[base + 1] * scale;
        float x2 = x + data[base + 2] * scale;
        float y2 = y + data[base + 3] * scale;
        float u0 = data[base + 4];
        float v0 = data[base + 5];
        float u1 = data[base + 6];
        float v1 = data[base + 7];

        writeRotatedVertex(p, x1, y1, u0, v0, leftArgb, originX, originY, cos, sin);
        writeRotatedVertex(p + STRIDE, x1, y2, u0, v1, leftArgb, originX, originY, cos, sin);
        writeRotatedVertex(p + STRIDE * 2L, x2, y2, u1, v1, rightArgb, originX, originY, cos, sin);
        writeRotatedVertex(p + STRIDE * 3L, x2, y1, u1, v0, rightArgb, originX, originY, cos, sin);
    }

    private static void writeRotatedVertex(long p, float x, float y, float u, float v, int color, float originX, float originY, float cos, float sin) {
        float dx = x - originX;
        float dy = y - originY;
        float rx = originX + dx * cos - dy * sin;
        float ry = originY + dx * sin + dy * cos;
        BufferUtils.writeUvRectToAddr(p, rx, ry, u, v, color);
    }

    private float baseWidth(String text, TtfFontLoader fontLoader) {
        LayoutKey key = new LayoutKey(fontLoader, text, fontLoader.getRenderScale());
        Float cached = widthCache.get(key);
        if (cached != null) {
            return cached;
        }

        float maxLine = 0.0f;
        float currentLine = 0.0f;
        float fontScale = fontLoader.getRenderScale();
        float scaledFont = DEFAULT_SCALE * fontScale;
        float spaceWidth = SPACE_WIDTH * fontScale;
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint == ' ') {
                currentLine += spaceWidth;
            } else if (codepoint == '\n') {
                maxLine = Math.max(maxLine, currentLine);
                currentLine = 0.0f;
            } else {
                currentLine += fontLoader.getAdvance(codepoint) * scaledFont + SPACING;
            }
        }

        float width = Math.max(maxLine, currentLine);
        widthCache.put(key, width);
        return width;
    }

    private static float clamp01(float value) {
        return Math.clamp(value, 0.0f, 1.0f);
    }

    @Override
    public void draw() {
        if (batches.isEmpty()) return;

        LuminRenderSystem.applyOrthoProjection();

        GpuTextureView colorView = LuminRenderSystem.resolveColorView();
        GpuTextureView depthView = LuminRenderSystem.resolveDepthView();
        if (colorView == null) return;
        if (scissorEnabled && !ScissorUtils.isVisible(scissorW, scissorH)) return;

        int maxIndexCount = prepareTextBatches();
        if (maxIndexCount == 0) return;

        GpuBufferSlice dynamicUniforms = LuminRenderSystem.writeDefaultGuiTransform();
        GpuBuffer ibo = LuminRenderSystem.getQuadIndexBuffer(maxIndexCount);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Lumin TTF Draws",
                colorView, Optional.empty(),
                depthView, OptionalDouble.empty())
        ) {
            pass.setPipeline(ClientSetting.INSTANCE.fontAntiAliasing.getValue()
                    ? LuminRenderPipelines.TTF_FONT_AA
                    : LuminRenderPipelines.TTF_FONT_NO_AA);
            if (scissorEnabled) {
                ScissorUtils.enableScissor(pass, scissorX, scissorY, scissorW, scissorH);
            }

            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicUniforms);
            pass.setIndexBuffer(ibo, LuminRenderSystem.getQuadIndexType());

            drawPrepared(pass);
        }
    }

    @Override
    public boolean prepareSharedDraw() {
        sharedDynamicUniforms = null;
        sharedMaxIndexCount = 0;
        if (batches.isEmpty() && (emojiBatch == null || emojiBatch.offsetInAtlas == 0)) return false;
        if (scissorEnabled && !ScissorUtils.isVisible(scissorW, scissorH)) return false;

        sharedMaxIndexCount = prepareTextBatches();
        if (emojiBatch != null && emojiBatch.offsetInAtlas > 0) {
            if (emojiBatch.buffer.isMapped()) {
                emojiBatch.buffer.unmap();
                emojiBatch.mappedAddress = 0L;
            }
            int vc = (int) (emojiBatch.offsetInAtlas / STRIDE);
            sharedMaxIndexCount = Math.max(sharedMaxIndexCount, (vc / 4) * 6);
        }
        if (sharedMaxIndexCount == 0) return false;

        LuminRenderSystem.getQuadIndexBuffer(sharedMaxIndexCount);
        sharedDynamicUniforms = LuminRenderSystem.writeDefaultGuiTransform();
        return sharedDynamicUniforms != null;
    }

    @Override
    public void draw(RenderPass pass) {
        if (sharedDynamicUniforms == null || sharedMaxIndexCount == 0) return;

        GpuBuffer ibo = LuminRenderSystem.getQuadIndexBuffer(sharedMaxIndexCount);
        pass.setIndexBuffer(ibo, LuminRenderSystem.getQuadIndexType());
        pass.setUniform("DynamicTransforms", sharedDynamicUniforms);
        drawPrepared(pass);
    }

    private int prepareTextBatches() {
        int maxIndexCount = 0;
        for (Batch batch : batches.values()) {
            if (batch.offsetInAtlas == 0) continue;
            if (batch.buffer.isMapped()) {
                batch.buffer.unmap();
                batch.mappedAddress = 0L;
            }

            int vertexCount = (int) (batch.offsetInAtlas / STRIDE);
            maxIndexCount = Math.max(maxIndexCount, (vertexCount / 4) * 6);
        }
        return maxIndexCount;
    }

    private void drawPrepared(RenderPass pass) {
        if (scissorEnabled) {
            if (!ScissorUtils.enableScissor(pass, scissorX, scissorY, scissorW, scissorH)) {
                return;
            }
        } else {
            pass.disableScissor();
        }

        // TTF glyph batches
        for (Map.Entry<TtfGlyphAtlas, Batch> entry : batches.entrySet()) {
            final var atlas = entry.getKey();
            final var batch = entry.getValue();

            if (batch.offsetInAtlas == 0) continue;

            int vertexCount = (int) (batch.offsetInAtlas / STRIDE);
            int indexCount = (vertexCount / 4) * 6;

            pass.setVertexBuffer(0, batch.buffer.getGpuBuffer().slice());
            pass.bindTexture("Sampler0", atlas.getTexture().getTextureView(), atlas.getTexture().getSampler());
            pass.drawIndexed(indexCount, 1, 0, 0, 0);
        }

        // Emoji batch — separate pipeline with RGBA texture
        if (emojiBatch != null && emojiBatch.offsetInAtlas > 0) {
            var emojiTex = SystemEmojiAtlas.INSTANCE.getTexture();
            if (emojiTex != null) {
                pass.setPipeline(LuminRenderPipelines.EMOJI);
                int vertexCount = (int) (emojiBatch.offsetInAtlas / STRIDE);
                int indexCount = (vertexCount / 4) * 6;
                pass.setVertexBuffer(0, emojiBatch.buffer.getGpuBuffer().slice());
                pass.bindTexture("Sampler0", emojiTex.getTextureView(), emojiTex.getSampler());
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
                // Restore TTF pipeline for any subsequent batches in shared passes
                pass.setPipeline(ClientSetting.INSTANCE.fontAntiAliasing.getValue()
                        ? LuminRenderPipelines.TTF_FONT_AA
                        : LuminRenderPipelines.TTF_FONT_NO_AA);
            }
        }
    }

    @Override
    public void clear() {
        for (Batch batch : batches.values()) {
            if (batch.offsetInAtlas > 0) {
                if (batch.buffer.isMapped()) {
                    batch.buffer.unmap();
                    batch.mappedAddress = 0L;
                }
                batch.buffer.rotate();
            }
            batch.offsetInAtlas = 0;
        }
        if (emojiBatch != null && emojiBatch.offsetInAtlas > 0) {
            if (emojiBatch.buffer.isMapped()) {
                emojiBatch.buffer.unmap();
                emojiBatch.mappedAddress = 0L;
            }
            emojiBatch.buffer.rotate();
            emojiBatch.offsetInAtlas = 0;
        }
        sharedDynamicUniforms = null;
        sharedMaxIndexCount = 0;
    }

    @Override
    public void close() {
        clear();
        for (Batch batch : batches.values()) {
            batch.buffer.close();
        }
        batches.clear();
        if (emojiBatch != null) {
            emojiBatch.buffer.close();
            emojiBatch = null;
        }
        layoutCache.clear();
        widthCache.clear();
    }

    @Override
    public float getHeight(float scale, TtfFontLoader fontLoader) {
        return fontLoader.fontFile.fontHeight * DEFAULT_SCALE * fontLoader.getRenderScale() * scale;
    }

    @Override
    public float getWidth(String text, float scale, TtfFontLoader fontLoader) {
        if (text.isEmpty()) return 0.0f;
        return baseWidth(text, fontLoader) * scale;
    }

    @Override
    public void setScissor(int x, int y, int width, int height) {
        LuminRenderSystem.ScissorRect scissor = ScissorUtils.clampFramebufferScissor(x, y, width, height);
        scissorEnabled = true;
        scissorX = scissor.x();
        scissorY = scissor.y();
        scissorW = scissor.width();
        scissorH = scissor.height();
    }

    @Override
    public void clearScissor() {
        scissorEnabled = false;
    }

    private static final class Batch {
        final LuminRingBuffer buffer;
        long offsetInAtlas = 0;
        long mappedAddress = 0L;

        private Batch(LuminRingBuffer buffer) {
            this.buffer = buffer;
        }

        private long beginWrite(int glyphCount) {
            long start = offsetInAtlas;
            long requiredBytes = start + glyphCount * GLYPH_BYTES;
            buffer.ensureCapacity(requiredBytes);

            // 同一 atlas run 只映射一次，后续 glyph 直接顺序写入 mapped memory。
            if (!buffer.isMapped()) {
                buffer.tryMap();
                mappedAddress = MemoryUtil.memAddress(buffer.getMappedBuffer());
            } else if (mappedAddress == 0L) {
                mappedAddress = MemoryUtil.memAddress(buffer.getMappedBuffer());
            }

            offsetInAtlas = requiredBytes;
            return mappedAddress + start;
        }
    }

    private static final class EmojiBatch {
        final LuminRingBuffer buffer;
        long offsetInAtlas = 0;
        long mappedAddress = 0L;

        private EmojiBatch(LuminRingBuffer buffer) {
            this.buffer = buffer;
        }

        private long beginWrite(int glyphCount) {
            long start = offsetInAtlas;
            long requiredBytes = start + glyphCount * GLYPH_BYTES;
            buffer.ensureCapacity(requiredBytes);

            if (!buffer.isMapped()) {
                buffer.tryMap();
                mappedAddress = MemoryUtil.memAddress(buffer.getMappedBuffer());
            } else if (mappedAddress == 0L) {
                mappedAddress = MemoryUtil.memAddress(buffer.getMappedBuffer());
            }

            offsetInAtlas = requiredBytes;
            return mappedAddress + start;
        }
    }

    private record LayoutKey(TtfFontLoader fontLoader, String text, float renderScale) {
    }

    private static final class TextLayout {
        final LayoutRun[] runs;
        final int glyphCount;
        final float width;
        final boolean complete;
        final long glyphRevision;
        final long atlasRevision;
        final List<float[]> emojiEntries;

        private TextLayout(LayoutRun[] runs, int glyphCount, float width, boolean complete,
                           long glyphRevision, long atlasRevision, List<float[]> emojiEntries) {
            this.runs = runs;
            this.glyphCount = glyphCount;
            this.width = width;
            this.complete = complete;
            this.glyphRevision = glyphRevision;
            this.atlasRevision = atlasRevision;
            this.emojiEntries = emojiEntries;
        }
    }

    private static final class LayoutRun {
        final TtfGlyphAtlas atlas;
        final float[] data;
        final int glyphCount;

        private LayoutRun(TtfGlyphAtlas atlas, float[] data, int glyphCount) {
            this.atlas = atlas;
            this.data = data;
            this.glyphCount = glyphCount;
        }
    }

    private static final class LayoutRunBuilder {
        private final TtfGlyphAtlas atlas;
        private float[] data = new float[LAYOUT_FLOATS_PER_GLYPH * 16];
        private int glyphCount;

        private LayoutRunBuilder(TtfGlyphAtlas atlas) {
            this.atlas = atlas;
        }

        private void add(float x1, float y1, float x2, float y2, TtfGlyphAtlas.GlyphUV uv, float gradientLeft, float gradientRight) {
            ensureCapacity(glyphCount + 1);
            int base = glyphCount * LAYOUT_FLOATS_PER_GLYPH;
            data[base] = x1;
            data[base + 1] = y1;
            data[base + 2] = x2;
            data[base + 3] = y2;
            data[base + 4] = uv.u0();
            data[base + 5] = uv.v0();
            data[base + 6] = uv.u1();
            data[base + 7] = uv.v1();
            data[base + 8] = gradientLeft;
            data[base + 9] = gradientRight;
            glyphCount++;
        }

        private void ensureCapacity(int targetGlyphCount) {
            int required = targetGlyphCount * LAYOUT_FLOATS_PER_GLYPH;
            if (required <= data.length) {
                return;
            }
            data = Arrays.copyOf(data, Math.max(required, data.length * 2));
        }

        private LayoutRun build() {
            int used = glyphCount * LAYOUT_FLOATS_PER_GLYPH;
            return new LayoutRun(atlas, used == data.length ? data : Arrays.copyOf(data, used), glyphCount);
        }
    }

}
