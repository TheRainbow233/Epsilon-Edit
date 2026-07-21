package com.github.epsilon.utils.render;

import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class TrajectorySimulator {

    private static final Minecraft mc = Minecraft.getInstance();

    private TrajectorySimulator() {}

    // ── Data types ──

    public record TrajectoryParams(
        double gravity,
        double hitboxRadius,
        double initialVelocity,
        double drag,
        double dragInWater,
        boolean copiesPlayerVelocity
    ) {
        public AABB hitbox(Vec3 center) {
            double r = hitboxRadius;
            return new AABB(
                center.x - r, center.y - r, center.z - r,
                center.x + r, center.y + r, center.z + r
            );
        }
    }

    public enum TrajectoryType {
        ARROW(false),
        POTION(true),
        ENDER_PEARL(true),
        SNOWBALL(true),
        EGG(true),
        EXP_BOTTLE(true),
        FISHING_ROD(true),
        TRIDENT(false),
        FIREWORK(false),
        FIREBALL(true),
        WIND_CHARGE(true);

        private final boolean initialTickCorrection;
        TrajectoryType(boolean initialTickCorrection) {
            this.initialTickCorrection = initialTickCorrection;
        }
        public boolean requiresInitialTickCorrection() { return initialTickCorrection; }
    }

    public record TrajectoryDescriptor(TrajectoryParams params, TrajectoryType type) {}

    // ── Physics constants (from vanilla Minecraft / LiquidBounce) ──

    public static final TrajectoryParams GENERIC      = new TrajectoryParams(0.03, 0.25, 1.5,  0.99, 0.8,  true);
    public static final TrajectoryParams PERSISTENT   = new TrajectoryParams(0.05, 0.5,  1.5,  0.99, 0.99, true);
    public static final TrajectoryParams POTION       = new TrajectoryParams(0.05, 0.25, 0.5,  0.99, 0.8,  true);
    public static final TrajectoryParams EXP_BOTTLE   = new TrajectoryParams(0.07, 0.25, 0.7,  0.99, 0.8,  true);
    public static final TrajectoryParams FISHING_ROD  = new TrajectoryParams(0.04, 0.25, 1.5,  0.92, 0.92, true);
    public static final TrajectoryParams TRIDENT      = new TrajectoryParams(0.05, 0.5,  2.5,  0.99, 0.99, true);
    public static final TrajectoryParams CROSSBOW_ARROW = new TrajectoryParams(0.05, 0.5, 3.15, 0.99, 0.99, false);
    public static final TrajectoryParams FIREWORK     = new TrajectoryParams(0.0,  0.25, 1.6,  1.0,  1.0,  false);
    public static final TrajectoryParams FIREBALL     = new TrajectoryParams(0.0,  1.0,  1.5,  0.99, 0.8,  true);
    public static final TrajectoryParams WIND_CHARGE  = new TrajectoryParams(0.0,  1.0,  1.5,  1.0,  1.0,  true);
    public static final TrajectoryParams ENTITY_ARROW = new TrajectoryParams(0.05, 0.3,  1.5,  0.99, 0.6,  true);

    // ── Pre-built descriptors ──

    public static final TrajectoryDescriptor BOW_ARROW       = new TrajectoryDescriptor(PERSISTENT, TrajectoryType.ARROW);
    public static final TrajectoryDescriptor CROSSBOW        = new TrajectoryDescriptor(CROSSBOW_ARROW, TrajectoryType.ARROW);
    public static final TrajectoryDescriptor POTION_DESC     = new TrajectoryDescriptor(POTION, TrajectoryType.POTION);
    public static final TrajectoryDescriptor ENDER_PEARL_DESC = new TrajectoryDescriptor(GENERIC, TrajectoryType.ENDER_PEARL);
    public static final TrajectoryDescriptor SNOWBALL_DESC   = new TrajectoryDescriptor(GENERIC, TrajectoryType.SNOWBALL);
    public static final TrajectoryDescriptor EGG_DESC        = new TrajectoryDescriptor(GENERIC, TrajectoryType.EGG);
    public static final TrajectoryDescriptor EXP_BOTTLE_DESC = new TrajectoryDescriptor(EXP_BOTTLE, TrajectoryType.EXP_BOTTLE);
    public static final TrajectoryDescriptor FISHING_ROD_DESC = new TrajectoryDescriptor(FISHING_ROD, TrajectoryType.FISHING_ROD);
    public static final TrajectoryDescriptor TRIDENT_DESC    = new TrajectoryDescriptor(TRIDENT, TrajectoryType.TRIDENT);
    public static final TrajectoryDescriptor FIREWORK_DESC   = new TrajectoryDescriptor(FIREWORK, TrajectoryType.FIREWORK);
    public static final TrajectoryDescriptor FIREBALL_DESC   = new TrajectoryDescriptor(FIREBALL, TrajectoryType.FIREBALL);
    public static final TrajectoryDescriptor WIND_CHARGE_DESC = new TrajectoryDescriptor(WIND_CHARGE, TrajectoryType.WIND_CHARGE);
    public static final TrajectoryDescriptor ENTITY_ARROW_DESC = new TrajectoryDescriptor(ENTITY_ARROW, TrajectoryType.ARROW);

    // ── Item → Descriptor resolution ──

    public static @Nullable TrajectoryDescriptor resolveHeldItem(Player player, ItemStack stack, boolean alwaysShowBow) {
        Item item = stack.getItem();
        if (item instanceof BowItem) {
            int useTicks = alwaysShowBow && player.getTicksUsingItem() < 1 ? 40 : player.getTicksUsingItem();
            float power = BowItem.getPowerForTime(useTicks);
            if (power < 0.1F) return null;
            double v0 = power * 3.0;
            return new TrajectoryDescriptor(
                new TrajectoryParams(0.05, 0.5, v0, 0.99, 0.99, true),
                TrajectoryType.ARROW
            );
        }
        if (item instanceof CrossbowItem) {
            return CROSSBOW;
        }
        if (item instanceof FishingRodItem)  return FISHING_ROD_DESC;
        if (item instanceof ThrowablePotionItem) return POTION_DESC;
        if (item instanceof TridentItem)     return TRIDENT_DESC;
        if (item instanceof SnowballItem)    return SNOWBALL_DESC;
        if (item instanceof EnderpearlItem)  return ENDER_PEARL_DESC;
        if (item instanceof EggItem)         return EGG_DESC;
        if (item instanceof ExperienceBottleItem) return EXP_BOTTLE_DESC;
        if (item instanceof FireChargeItem)  return FIREBALL_DESC;
        if (item instanceof WindChargeItem)  return WIND_CHARGE_DESC;
        return null;
    }

    // ── Entity → Descriptor resolution ──

    public static @Nullable TrajectoryDescriptor resolveEntity(Entity entity, boolean activeArrows, boolean activeOthers) {
        if (activeArrows && entity instanceof AbstractArrow a && !(entity instanceof ThrownTrident) && !a.isInGround()) {
            return ENTITY_ARROW_DESC;
        }
        if (!activeOthers) return null;

        if (entity instanceof AbstractThrownPotion)    return POTION_DESC;
        if (entity instanceof ThrownTrident t && !t.isInGround()) return TRIDENT_DESC;
        if (entity instanceof ThrownEnderpearl)        return ENDER_PEARL_DESC;
        if (entity instanceof Snowball)                return SNOWBALL_DESC;
        if (entity instanceof ThrownExperienceBottle)  return EXP_BOTTLE_DESC;
        if (entity instanceof ThrownEgg)               return EGG_DESC;
        if (entity instanceof FishingHook)             return FISHING_ROD_DESC;
        if (entity instanceof FireworkRocketEntity)    return FIREWORK_DESC;
        if (entity instanceof Fireball)                return FIREBALL_DESC;
        if (entity instanceof WindCharge)              return WIND_CHARGE_DESC;
        return null;
    }

    // ── Simulation ──

    public static List<Vec3> simulate(
        Vec3 startPos, Vec3 startVelocity,
        TrajectoryParams params, TrajectoryType type,
        int maxTicks, Entity owner
    ) {
        Level world = mc.level;
        if (world == null) return List.of();

        Vec3 pos = startPos;
        Vec3 velocity = startVelocity;
        List<Vec3> positions = new ArrayList<>();
        positions.add(pos);

        // First-tick correction for certain projectile types
        if (type.requiresInitialTickCorrection()) {
            velocity = tickVelocity(velocity, params, world, pos);
        }

        int startTick = type.requiresInitialTickCorrection() ? 1 : 0;

        for (int tick = startTick; tick < maxTicks; tick++) {
            if (pos.y < world.getMinY()) break;

            Vec3 prevPos = pos;
            pos = pos.add(velocity);
            positions.add(pos);

            // Check collisions
            HitResult hit = checkForHits(prevPos, pos, params, owner, world);
            if (hit != null) {
                positions.add(hit.getLocation());
                return positions;
            }

            velocity = tickVelocity(velocity, params, world, pos);
        }

        return positions;
    }

    public static @Nullable HitResult getHitResult(
        Vec3 startPos, Vec3 startVelocity,
        TrajectoryParams params, TrajectoryType type,
        int maxTicks, Entity owner
    ) {
        Level world = mc.level;
        if (world == null) return null;

        Vec3 pos = startPos;
        Vec3 velocity = startVelocity;

        if (type.requiresInitialTickCorrection()) {
            velocity = tickVelocity(velocity, params, world, pos);
        }

        int startTick = type.requiresInitialTickCorrection() ? 1 : 0;

        for (int tick = startTick; tick < maxTicks; tick++) {
            if (pos.y < world.getMinY()) return null;

            Vec3 prevPos = pos;
            pos = pos.add(velocity);

            HitResult hit = checkForHits(prevPos, pos, params, owner, world);
            if (hit != null) return hit;

            velocity = tickVelocity(velocity, params, world, pos);
        }

        return null;
    }

    private static Vec3 tickVelocity(Vec3 velocity, TrajectoryParams params, Level world, Vec3 pos) {
        boolean inWater = !world.getBlockState(
            BlockPos.containing(pos.x, pos.y, pos.z)
        ).getFluidState().isEmpty();
        double drag = inWater ? params.dragInWater : params.drag;
        return velocity.scale(drag).add(0, -params.gravity, 0);
    }

    private static @Nullable HitResult checkForHits(
        Vec3 posBefore, Vec3 posAfter,
        TrajectoryParams params, Entity owner, Level world
    ) {
        // Block collision
        BlockHitResult blockHit = world.clip(new ClipContext(
            posBefore, posAfter,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner
        ));
        if (blockHit.getType() != HitResult.Type.MISS) {
            return blockHit;
        }

        // Entity collision
        AABB searchBox = params.hitbox(posBefore)
            .expandTowards(posAfter.subtract(posBefore))
            .inflate(1.0);

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            world, owner, posBefore, posAfter, searchBox,
            e -> !e.isSpectator() && e.isAlive() && (e.isPickable() || e == mc.player)
                && !owner.isPassengerOfSameVehicle(e),
            0f
        );

        if (entityHit != null && entityHit.getType() != HitResult.Type.MISS) {
            return entityHit;
        }

        return null;
    }

    // ── Rendering ──

    public static void renderTrajectory(List<Vec3> positions, Color color, float lineWidth) {
        if (positions.size() < 2) return;
        for (int i = 0; i < positions.size() - 1; i++) {
            Render3DScheduler.INSTANCE.addLine(positions.get(i), positions.get(i + 1), color, lineWidth);
        }
    }

    public static void renderImpact(@Nullable HitResult hit, Color color) {
        if (hit == null) return;
        Vec3 pos = hit.getLocation();
        double r = 0.15;
        AABB box = new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r);
        Render3DScheduler.INSTANCE.addOutlineBox(box, color);
    }

    // ── Color ──

    public static Color resolveColor(TrajectoryType type, @Nullable Entity entity, Color defaultColor) {
        return switch (type) {
            case ARROW -> new Color(255, 255, 255, 220);
            case POTION -> {
                if (entity instanceof AbstractThrownPotion p) {
                    int c = p.getColor();
                    yield new Color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 220);
                }
                yield new Color(180, 80, 200, 220);
            }
            case ENDER_PEARL  -> new Color(128, 0, 128, 200);
            case SNOWBALL     -> new Color(200, 200, 200, 220);
            case EGG          -> new Color(240, 234, 214, 220);
            case EXP_BOTTLE   -> new Color(120, 230, 120, 220);
            case FISHING_ROD  -> new Color(0, 200, 200, 200);
            case TRIDENT      -> new Color(180, 210, 255, 220);
            case FIREWORK     -> new Color(255, 160, 0, 220);
            case FIREBALL     -> new Color(255, 100, 0, 220);
            case WIND_CHARGE  -> new Color(180, 235, 255, 220);
        };
    }
}
