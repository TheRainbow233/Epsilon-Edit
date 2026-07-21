package com.github.epsilon.holders;

import com.github.epsilon.graphics.renderers.IRenderer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class RendererHolder {

    public static final RendererHolder INSTANCE = new RendererHolder();

    private final List<IRenderer> renderers = new CopyOnWriteArrayList<>();

    private RendererHolder() {
    }

    public <T extends IRenderer> T register(T renderer) {
        renderers.add(renderer);
        return renderer;
    }

    public void unregister(IRenderer renderer) {
        renderers.remove(renderer);
    }

    public void destroyAll() {
        for (IRenderer renderer : renderers) {
            renderer.close();
        }
        renderers.clear();
    }

}
