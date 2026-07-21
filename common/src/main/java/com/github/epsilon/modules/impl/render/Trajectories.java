package com.github.epsilon.modules.impl.render;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.ColorSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.render.TrajectorySimulator;
import com.github.epsilon.utils.render.TrajectorySimulator.TrajectoryDescriptor;
import com.github.epsilon.utils.render.WorldToScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.List;

public class Trajectories extends Module {

    public static final Trajectories INSTANCE = new Trajectories();

    private Trajectories() {
        super("Trajectories", Category.RENDER);
    }

    // ── Master ──
    private final BoolSetting masterSwitch = boolSetting("Master Switch", true);

    // ── Held Items group ──
    private final SettingGroup heldGroup = settingGroup("Held Items");
    private final BoolSetting heldItems = boolSetting("Held Items", true);
    private final BoolSetting alwaysShowBow = boolSetting("Always Show Bow", false, heldItems::getValue);
    private final ColorSetting heldItemsColor = colorSetting("Held Items Color", new Color(255, 255, 255, 200), heldItems::getValue);

    // ── Entities group ──
    private final SettingGroup entitiesGroup = settingGroup("Entities");
    private final BoolSetting activeEntities = boolSetting("Active Entities", true);
    private final BoolSetting activeArrows = boolSetting("Active Arrows", true, activeEntities::getValue);
    private final ColorSetting entitiesColor = colorSetting("Entities Color", new Color(255, 200, 100, 200), activeEntities::getValue);

    // ── Shared ──
    private final IntSetting maxTicks = intSetting("Max Ticks", 300, 50, 1000, 50);
    private final DoubleSetting lineWidth = doubleSetting("Line Width", 1.0, 0.5, 5.0, 0.5);
    private final BoolSetting showImpact = boolSetting("Show Impact", true);
    private final ColorSetting impactColor = colorSetting("Impact Color", new Color(255, 0, 0, 200), showImpact::getValue);

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (nullCheck() || !masterSwitch.getValue() || mc.gui.hud.isHidden()) return;

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

        // Held item trajectory
        if (heldItems.getValue()) {
            renderHeldItemTrajectory(partialTick);
        }

        // Entity trajectories
        if (activeEntities.getValue()) {
            renderEntityTrajectories(partialTick);
        }
    }

    private void renderHeldItemTrajectory(float partialTick) {
        Player player = mc.player;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return;

        TrajectoryDescriptor desc = TrajectorySimulator.resolveHeldItem(player, stack, alwaysShowBow.getValue());
        if (desc == null) return;

        Vec3 eyePos = player.getEyePosition(partialTick).subtract(0, 0.1, 0);

        float yaw = player.getViewYRot(partialTick);
        float pitch = player.getViewXRot(partialTick);
        Vec3 lookDir = Vec3.directionFromRotation(pitch, yaw);

        // Offset to right-hand side so trajectory line doesn't overlap with crosshair
        Vec3 rightDir = new Vec3(-lookDir.z, 0, lookDir.x);
        Vec3 startPos = eyePos.add(rightDir.scale(0.4));

        Vec3 velocity = lookDir.scale(desc.params().initialVelocity());

        // Add player momentum for projectile types that inherit it
        if (desc.params().copiesPlayerVelocity()) {
            Vec3 delta = player.getDeltaMovement();
            velocity = velocity.add(
                delta.x,
                player.onGround() ? 0 : delta.y,
                delta.z
            );
        }

        TrajectorySimulator.SimulationResult result = TrajectorySimulator.simulate(
            startPos, velocity, desc.params(), desc.type(),
            maxTicks.getValue(), player
        );

        List<Vec3> positions = result.positions();
        if (positions.size() < 2) return;

        Color lineColor = heldItemsColor.getValue();
        TrajectorySimulator.renderTrajectory(positions, lineColor, lineWidth.getValue().floatValue());

        if (showImpact.getValue()) {
            TrajectorySimulator.renderImpact(result.hitResult(), impactColor.getValue());
        }
    }

    private void renderEntityTrajectories(float partialTick) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!WorldToScreen.isInFrontOfCamera(entity, partialTick)) continue;

            TrajectoryDescriptor desc = TrajectorySimulator.resolveEntity(
                entity, activeArrows.getValue(), true
            );
            if (desc == null) continue;

            Vec3 pos = entity.getEyePosition(partialTick).subtract(0, 0.1, 0);
            Vec3 velocity = entity.getDeltaMovement();

            TrajectorySimulator.SimulationResult result = TrajectorySimulator.simulate(
                pos, velocity, desc.params(), desc.type(),
                maxTicks.getValue(), entity
            );

            List<Vec3> positions = result.positions();
            if (positions.size() < 2) continue;

            Color lineColor = entitiesColor.getValue();
            TrajectorySimulator.renderTrajectory(positions, lineColor, lineWidth.getValue().floatValue());

            if (showImpact.getValue()) {
                TrajectorySimulator.renderImpact(result.hitResult(), impactColor.getValue());
            }
        }
    }
}
