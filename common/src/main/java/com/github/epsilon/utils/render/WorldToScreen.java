package com.github.epsilon.utils.render;

import com.github.epsilon.graphics.LuminRenderSystem;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4d;
import org.joml.Vector4f;

import static com.github.epsilon.Constants.mc;

public class WorldToScreen {

    private WorldToScreen() {
    }

    public static Vector3f getWorldPositionToScreen(Vec3 pos) {
        Vector3f cameraRelativePos = pos.subtract(mc.gameRenderer.mainCamera().position()).toVector3f();
        Vector4f projected = new Vector4f();
        int[] viewport = getViewport();

        getViewProjectionMatrix().project(cameraRelativePos, viewport, projected);
        projected.y = viewport[3] - projected.y;

        return new Vector3f(projected.x, projected.y, projected.z);
    }

    public static Vector4d getEntityPositionsOn2D(Entity entity, float tickDelta) {
        Vec3 position = interpolate(entity, tickDelta);
        float halfWidth = entity.getBbWidth() / 2.0f;
        float height = entity.getBbHeight() + 0.2f;
        AABB boundingBox = new AABB(
                position.x - halfWidth, position.y, position.z - halfWidth,
                position.x + halfWidth, position.y + height, position.z + halfWidth
        );
        return projectAbsoluteAABBOn2D(boundingBox);
    }

    public static Vector4d projectAbsoluteAABBOn2D(AABB absoluteBoundingBox) {
        Vector4d projection = projectEntity(getViewport(), getViewProjectionMatrix(), absoluteBoundingBox);
        if (projection == null) {
            return null;
        }

        return projection.div(LuminRenderSystem.getGuiScale());
    }

    public static Vector4d projectEntity(int[] viewport, Matrix4f matrix, AABB absoluteBoundingBox) {
        return projectEntity(viewport, matrix, absoluteBoundingBox, mc.gameRenderer.mainCamera().position());
    }

    public static Vector4d projectEntity(int[] viewport, Matrix4f matrix, AABB absoluteBoundingBox, Vec3 cameraPos) {
        Vector4f projected = new Vector4f();
        Vector4d bounds = null;

        for (int i = 0; i < 8; i++) {
            Vector3f point = new Vector3f(
                    (float) (((i & 1) == 0 ? absoluteBoundingBox.minX : absoluteBoundingBox.maxX) - cameraPos.x),
                    (float) (((i & 2) == 0 ? absoluteBoundingBox.minY : absoluteBoundingBox.maxY) - cameraPos.y),
                    (float) (((i & 4) == 0 ? absoluteBoundingBox.minZ : absoluteBoundingBox.maxZ) - cameraPos.z)
            );

            matrix.project(point, viewport, projected);
            projected.y = viewport[3] - projected.y;
            if (!Float.isFinite(projected.x) || !Float.isFinite(projected.y) || !Float.isFinite(projected.z)
                    || projected.z < 0.0f || projected.z > 1.0f) {
                continue;
            }

            if (bounds == null) {
                bounds = new Vector4d(projected.x, projected.y, projected.x, projected.y);
            } else {
                bounds.x = Math.min(bounds.x, projected.x);
                bounds.y = Math.min(bounds.y, projected.y);
                bounds.z = Math.max(bounds.z, projected.x);
                bounds.w = Math.max(bounds.w, projected.y);
            }
        }

        return bounds;
    }

    /**
     * Returns true if the entity center is in front of the camera.
     * Use this as a fast pre-check before expensive AABB projection.
     */
    public static boolean isInFrontOfCamera(Entity entity, float tickDelta) {
        Vec3 pos = interpolate(entity, tickDelta);
        Vec3 cameraPos = mc.gameRenderer.mainCamera().position();
        Vector3f relative = new Vector3f(
                (float)(pos.x - cameraPos.x),
                (float)(pos.y - cameraPos.y),
                (float)(pos.z - cameraPos.z)
        );

        // viewRotationMatrix is rotation-only; transform relative pos to camera space to get depth
        CameraRenderState cameraState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Matrix4f viewRot = new Matrix4f(cameraState.viewRotationMatrix);
        Vector3f cameraSpace = relative.mulPosition(viewRot);

        // camera looks down -Z in OpenGL convention, so cameraSpace.z should be negative for in-front
        // with reversed depth: near=1, far=0. Entity must be between near and far.
        // We just check that the entity is on the correct side of the camera (z < 0 in view space)
        return cameraSpace.z < 0.0f;
    }

    public static Vec3 interpolate(Entity entity, float tickDelta) {
        return new Vec3(
                Mth.lerp(tickDelta, entity.xOld, entity.getX()),
                Mth.lerp(tickDelta, entity.yOld, entity.getY()),
                Mth.lerp(tickDelta, entity.zOld, entity.getZ())
        );
    }

    private static Matrix4f getViewProjectionMatrix() {
        CameraRenderState cameraState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        return new Matrix4f(cameraState.projectionMatrix).mul(cameraState.viewRotationMatrix);
    }

    private static int[] getViewport() {
        return new int[]{0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight()};
    }
}
