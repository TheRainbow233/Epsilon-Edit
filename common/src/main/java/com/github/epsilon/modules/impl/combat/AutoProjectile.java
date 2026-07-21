package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.managers.Managers;
import com.github.epsilon.managers.impl.target.TargetRequest;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.render.TrajectorySimulator;
import com.github.epsilon.utils.render.TrajectorySimulator.TrajectoryDescriptor;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

public class AutoProjectile extends Module {

    public static final AutoProjectile INSTANCE = new AutoProjectile();

    private enum TargetMode {
        KillAura,
        Nearest
    }

    private final EnumSetting<TargetMode> targetMode = enumSetting("Target Mode", TargetMode.KillAura);
    private final DoubleSetting range = doubleSetting("Range", 20.0, 5.0, 50.0, 1.0);
    private final DoubleSetting maxAngle = doubleSetting("Max Angle", 30.0, 5.0, 90.0, 1.0);
    private final BoolSetting silentRotate = boolSetting("Silent Rotate", true);
    private final IntSetting maxTicks = intSetting("Max Ticks", 100, 30, 300, 10);
    private final BoolSetting stopAtKARange = boolSetting("Stop At KA Range", true);
    private final IntSetting throwDelay = intSetting("Throw Delay", 5, 1, 40, 1);

    private int delayCounter;

    private AutoProjectile() {
        super("Auto Projectile", Category.COMBAT);
    }

    @Override
    protected void onDisable() {
        delayCounter = 0;
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck() || mc.gui.screen() != null) return;

        if (mc.player.isUsingItem()) return;

        // Delay between throws
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        Player player = mc.player;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return;

        TrajectoryDescriptor desc = TrajectorySimulator.resolveHeldItem(player, stack, false);
        if (desc == null) return;

        // Skip bow and crossbow
        if (desc.type() == TrajectorySimulator.TrajectoryType.ARROW) return;

        LivingEntity target = findTarget();
        if (target == null) return;

        // Stop if KillAura is handling melee range
        if (stopAtKARange.getValue() && KillAura.INSTANCE.isEnabled()) {
            if (player.distanceToSqr(target) <= KillAura.INSTANCE.aimRange.getValue() * KillAura.INSTANCE.aimRange.getValue()) {
                return;
            }
        }

        // Skip ender pearl if target is too close (self-damage risk)
        if (desc.type() == TrajectorySimulator.TrajectoryType.ENDER_PEARL
                && player.distanceToSqr(target) < 9.0) return;

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

        // Compute hand position offset to the right
        Vec3 eyePos = player.getEyePosition(partialTick).subtract(0, 0.1, 0);
        float yaw = player.getViewYRot(partialTick);
        float pitch = player.getViewXRot(partialTick);
        Vec3 lookDir = Vec3.directionFromRotation(pitch, yaw);
        Vec3 rightDir = new Vec3(-lookDir.z, 0, lookDir.x);
        Vec3 handPos = eyePos.add(rightDir.scale(0.4));

        // Brute-force search for best launch pitch
        Rot2f bestAngle = findLaunchAngle(handPos, yaw, desc, target, partialTick);
        if (bestAngle == null) return;

        // Check crosshair angle tolerance
        float angleDiff = Math.abs(bestAngle.getPitch() - pitch);
        if (angleDiff > maxAngle.getValue()) return;

        // Silent rotate
        if (silentRotate.getValue()) {
            Managers.ROTATION.setRotations(bestAngle, 10, Priority.High);
        }

        // Throw
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        delayCounter = throwDelay.getValue();
    }

    private LivingEntity findTarget() {
        if (targetMode.getValue() == TargetMode.KillAura) {
            LivingEntity killAuraTarget = Managers.TARGET.acquirePrimary(TargetRequest.of(
                    range.getValue(),
                    360,
                    ClientSetting.INSTANCE.targetPlayer.getValue(),
                    ClientSetting.INSTANCE.targetMob.getValue(),
                    ClientSetting.INSTANCE.targetAnimal.getValue(),
                    ClientSetting.INSTANCE.targetVillager.getValue(),
                    ClientSetting.INSTANCE.targetInvisible.getValue(),
                    1
            ));
            if (killAuraTarget != null) return killAuraTarget;
        }

        // Fallback: nearest enemy
        return mc.level.players().stream()
                .filter(p -> p != mc.player && p.isAlive() && !p.isSpectator())
                .filter(p -> mc.player.distanceToSqr(p) <= range.getValue() * range.getValue())
                .min(Comparator.comparingDouble(mc.player::distanceToSqr))
                .map(p -> (LivingEntity) p)
                .orElse(null);
    }

    private Rot2f findLaunchAngle(Vec3 handPos, float yaw, TrajectoryDescriptor desc, LivingEntity target, float partialTick) {
        Rot2f best = null;
        double bestDist = Double.MAX_VALUE;

        Vec3 playerVel = mc.player.getDeltaMovement();
        Vec3 momentum = desc.params().copiesPlayerVelocity()
                ? new Vec3(playerVel.x, mc.player.onGround() ? 0 : playerVel.y, playerVel.z)
                : Vec3.ZERO;

        // Search pitch from player's current pitch downward
        float startPitch = mc.player.getViewXRot(partialTick);
        float endPitch = Math.max(startPitch - 45, -90);

        for (float testPitch = startPitch; testPitch >= endPitch; testPitch -= 1.0f) {
            Vec3 lookDir = Vec3.directionFromRotation(testPitch, yaw);
            Vec3 velocity = lookDir.scale(desc.params().initialVelocity()).add(momentum);

            TrajectorySimulator.SimulationResult result = TrajectorySimulator.simulate(
                    handPos, velocity, desc.params(), desc.type(), maxTicks.getValue(), mc.player
            );

            HitResult hit = result.hitResult();
            if (hit instanceof EntityHitResult ehr && ehr.getEntity() == target) {
                double dist = hit.getLocation().distanceTo(target.getEyePosition(partialTick));
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new Rot2f(yaw, testPitch);
                }
            }
        }

        return best;
    }
}
