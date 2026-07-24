package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.listeners.ConsumerListener;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.managers.Managers;
import com.github.epsilon.managers.impl.target.TargetRequest;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.settings.impl.*;
import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.render.esp.CaptureMarkESP;
import com.github.epsilon.utils.render.esp.CircleESP;
import com.github.epsilon.utils.render.esp.DeobfESP;
import com.github.epsilon.utils.render.esp.FireflyESP;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.RaytraceUtils;
import com.github.epsilon.utils.rotation.RotationUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.awt.*;
import java.util.List;

public class KillAura extends Module {

    public static final KillAura INSTANCE = new KillAura();

    private KillAura() {
        super("Kill Aura", Category.COMBAT);
        EventBus.INSTANCE.subscribe(new ConsumerListener<>(Render3DEvent.class, event -> {
            DeobfESP.render(
                    event.getPoseStack(),
                    deobfSize.getValue().floatValue(),
                    deobfSpins.getValue().floatValue(),
                    deobfWobble.getValue().floatValue(),
                    deobfFlyHeight.getValue().floatValue()
            );
        }));
    }

    private enum Mode {
        OnePointEight,
        OnePointNinePlus
    }

    public enum TargetMode {
        Single,
        Switch,
        Multiple,
    }

    private enum ESPMode {
        CaptureMark,
        Circle,
        Firefly,
        Deobf
    }

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.OnePointEight);

    private final EnumSetting<TargetMode> targetMode = enumSetting("Target Mode", TargetMode.Single);

    public final DoubleSetting aimRange = doubleSetting("Aim Range", 4.0, 1.0, 6.0, 0.1);

    private final IntSetting fov = intSetting("FOV", 360, 10, 360, 1);

    private final DoubleSetting rotationSpeed = doubleSetting("Rotation Speed", 10.0, 1.0, 10.0, 0.5);

    private final IntSetting minCPS = intSetting("Min CPS", 10, 1, 20, 1, () -> mode.is(Mode.OnePointEight));
    private final IntSetting maxCPS = intSetting("Max CPS", 12, 1, 20, 1, () -> mode.is(Mode.OnePointEight));

    private final BoolSetting throughWalls = boolSetting("Through Walls", false);

    private final BoolSetting swingHand = boolSetting("SwingHand", true);

    private final BoolSetting esp = boolSetting("ESP", true);

    private final EnumSetting<ESPMode> espMode = enumSetting("ESP Mode", ESPMode.Circle, esp::getValue);

    private final DoubleSetting deobfSize = doubleSetting("Deobf Size", 0.75, 0.25, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfSpins = doubleSetting("Deobf Spins", 3.0, 0.5, 8.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfWobble = doubleSetting("Deobf Wobble", 1.0, 0.0, 2.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfFlyHeight = doubleSetting("Deobf Fly Height", 5.0, 1.0, 12.0, 0.5, () -> esp.getValue() && espMode.is(ESPMode.Deobf));

    private final ColorSetting espColor1 = colorSetting("ESP Main", new Color(255, 183, 197), () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final ColorSetting espColor2 = colorSetting("ESP Second", new Color(255, 133, 161), () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting espSize = doubleSetting("ESP Size", 1.2, 0.5, 3.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting espRotSpeed = doubleSetting("Rot Speed", 2.0, 0.5, 10.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting waveSpeed = doubleSetting("Wave Speed", 3.0, 0.5, 10.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));

    private final ColorSetting sideColor = colorSetting("Side Color", Color.WHITE, false);
    private final ColorSetting lineColor = colorSetting("Line Color", new Color(255, 255, 255, 233), () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final DoubleSetting circleRadius = doubleSetting("Circle Radius", 0.75, 0.1, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final DoubleSetting circleAlphaFactor = doubleSetting("Circle Alpha Factor", 1.0, 0.0, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Circle));

    private final ColorSetting fireflyColor = colorSetting("Firefly Color", new Color(149, 149, 149, 255), false, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final EnumSetting<FireflyESP.ColorMode> fireflyColorMode = enumSetting("Firefly Color Mode", FireflyESP.ColorMode.Blend, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final ColorSetting fireflyColor2 = colorSetting("Firefly Color 2", new Color(255, 133, 161, 255), false, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyColorMix = doubleSetting("Firefly Color Mix", 0.65, 0.0, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyColorSpeed = doubleSetting("Firefly Color Speed", 1.2, 0.1, 6.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyRainbowSpeed = doubleSetting("Firefly Rainbow Speed", 1.0, 0.1, 6.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final DoubleSetting fireflyRainbowSaturation = doubleSetting("Firefly Rainbow Saturation", 0.85, 0.1, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final DoubleSetting fireflyRainbowBrightness = doubleSetting("Firefly Rainbow Brightness", 1.0, 0.1, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final IntSetting fireflyLength = intSetting("Firefly Length", 14, 8, 128, 1, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final IntSetting fireflyFactor = intSetting("Firefly Factor", 8, 1, 10, 1, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final DoubleSetting fireflyShaking = doubleSetting("Firefly Shaking", 1.8, 0.25, 10.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final DoubleSetting fireflyAmplitude = doubleSetting("Firefly Amplitude", 3.0, 0.0, 10.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Firefly));

    public LivingEntity target;

    private int switchIndex = 0;
    private double attacks = 0.0;

    @Override
    protected void onDisable() {
        target = null;
        switchIndex = 0;
        DeobfESP.retainRisingEffects();
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (!esp.getValue() || !espMode.is(ESPMode.Deobf)) {
            DeobfESP.clear();
        }

        if (mc.player.isUsingItem() || mc.player.isBlocking()) return;

        List<LivingEntity> targets = Managers.TARGET.acquireTargets(TargetRequest.of(
                aimRange.getValue(),
                fov.getValue().floatValue(),
                ClientSetting.INSTANCE.targetPlayer.getValue(),
                ClientSetting.INSTANCE.targetMob.getValue(),
                ClientSetting.INSTANCE.targetAnimal.getValue(),
                ClientSetting.INSTANCE.targetVillager.getValue(),
                ClientSetting.INSTANCE.targetInvisible.getValue(),
                64
        ));

        if (targets.isEmpty()) {
            target = null;
            return;
        }

        if (targetMode.is(TargetMode.Single)) {
            target = targets.getFirst();
        } else if (targetMode.is(TargetMode.Switch)) {
            if (switchIndex >= targets.size()) {
                switchIndex = 0;
            }
            target = targets.get(switchIndex);
        } else if (targetMode.is(TargetMode.Multiple)) {
            target = targets.getFirst();
        }

        if (target != null) {
            Managers.ROTATION.setRotations(RotationUtils.getRotationsToEntity(target), rotationSpeed.getValue().floatValue(), Priority.Medium);
            if (mode.is(Mode.OnePointEight)) {
                attacks += MathUtils.getRandom(minCPS.getValue().doubleValue(), maxCPS.getValue().doubleValue()) / 20.0;
                while (attacks >= 1.0) {
                    clickTargets(targets);
                    attacks -= 1.0;
                }
            } else {
                if (mc.player.getAttackStrengthScale(0.5f) >= 1.0f) {
                    clickTargets(targets);
                }
            }
        }
    }

    private void clickTargets(List<LivingEntity> targets) {
        if (targetMode.is(TargetMode.Multiple)) {
            for (LivingEntity entity : targets) {
                Managers.ROTATION.setRotations(RotationUtils.getRotationsToEntity(entity), rotationSpeed.getValue().floatValue(), Priority.Medium);
                if (throughWalls.getValue() || canAttack(entity)) {
                    doAttack(entity);
                }
            }
            switchIndex++;
        } else {
            if (throughWalls.getValue() || canAttack(target)) {
                doAttack(target);
            }
            if (targetMode.is(TargetMode.Switch)) {
                switchIndex++;
            }
        }
    }

    private boolean canAttack(LivingEntity entity) {
        HitResult result = RaytraceUtils.raytrace(
                RotationUtils.getRotationsToEntity(entity),
                aimRange.getValue()
        );
        return result instanceof EntityHitResult entityHit
                && entityHit.getEntity() == entity;
    }

    private void doAttack(LivingEntity target) {
        mc.gameMode.attack(mc.player, target);
        if (esp.getValue() && espMode.is(ESPMode.Deobf)) {
            DeobfESP.markHit(target);
        }
        if (swingHand.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!esp.getValue() || espMode.is(ESPMode.Deobf) || target == null) return;

        PoseStack stack = event.getPoseStack();

        switch (espMode.getValue()) {
            case CaptureMark -> {
                CaptureMarkESP.render(
                        stack,
                        target,
                        espSize.getValue(),
                        espRotSpeed.getValue(),
                        waveSpeed.getValue(),
                        espColor1.getValue(),
                        espColor2.getValue()
                );
            }
            case Circle -> {
                CircleESP.render(
                        stack,
                        target,
                        circleRadius.getValue().floatValue(),
                        sideColor.getValue(),
                        lineColor.getValue(),
                        circleAlphaFactor.getValue().floatValue()
                );
            }
            case Firefly -> {
                FireflyESP.render(
                        stack,
                        target,
                        fireflyLength.getValue(),
                        fireflyFactor.getValue(),
                        fireflyShaking.getValue(),
                        fireflyAmplitude.getValue(),
                        fireflyColor.getValue(),
                        fireflyColorMode.getValue(),
                        fireflyColor2.getValue(),
                        fireflyColorMix.getValue(),
                        fireflyColorSpeed.getValue(),
                        fireflyRainbowSpeed.getValue(),
                        fireflyRainbowSaturation.getValue(),
                        fireflyRainbowBrightness.getValue()
                );
            }
        }
    }

}
