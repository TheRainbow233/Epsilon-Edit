# Trajectories (抛物线显示) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a projectile trajectory/parabola preview module that renders 3D flight paths for held throwable items and in-flight projectile entities.

**Architecture:** Two files — `TrajectorySimulator.java` (pure-logic utility: physics simulation, collision detection, rendering via `Render3DScheduler`) and `Trajectories.java` (module: settings, event binding to `Render3DEvent`). Simulation is done per-frame (no state caching) for simplicity.

**Tech Stack:** Java 21, Minecraft 1.21.4 fabric-loader, Epsilon Render3DScheduler, ProjectileUtil, JOML math.

## Global Constraints

- Use `Render3DScheduler.INSTANCE.addLine()` for trajectory line segments (auto-flushed, no pipeline management needed)
- Use `Render3DScheduler.INSTANCE.addOutlineBox()` for impact point markers
- Follow Epsilon module conventions: `public static final X INSTANCE = new X()`, private constructor, `Category.RENDER`
- Settings via `SettingHost` factory methods (`boolSetting`, `colorSetting`, `doubleSetting`, `intSetting`)
- `nullCheck()` guard before any game logic
- Register module in `ModuleHolder.initModules()` under the Render section

---

### Task 1: Create TrajectorySimulator utility class

**Files:**
- Create: `common/src/main/java/com/github/epsilon/utils/render/TrajectorySimulator.java`

**Interfaces:**
- Produces:
  - `record TrajectorySimulator.TrajectoryParams(double gravity, double hitboxRadius, double initialVelocity, double drag, double dragInWater, boolean copiesPlayerVelocity)` with `hitbox()` method returning `AABB`
  - `enum TrajectorySimulator.TrajectoryType { ARROW, POTION, ENDER_PEARL, SNOWBALL, EGG, EXP_BOTTLE, FISHING_ROD, TRIDENT, FIREWORK, FIREBALL, WIND_CHARGE }` with boolean `requiresInitialTickCorrection()`
  - `record TrajectorySimulator.TrajectoryDescriptor(TrajectoryParams params, TrajectoryType type)`
  - `static TrajectoryDescriptor resolveHeldItem(Player player, ItemStack stack, boolean alwaysShowBow)` — returns null if not a throwable
  - `static TrajectoryDescriptor resolveEntity(Entity entity, boolean activeArrows, boolean activeOthers)` — returns null if not a projectile
  - `static List<Vec3> simulate(Vec3 startPos, Vec3 startVelocity, TrajectoryParams params, TrajectoryType type, int maxTicks, Entity owner)` — returns positions list + final Vec3 `null` hit position (encoded as positions list, last element is impact point)
  - `static @Nullable HitResult getHitResult(Vec3 startPos, Vec3 startVelocity, TrajectoryParams params, TrajectoryType type, int maxTicks, Entity owner)` — returns the hit result or null
  - `static void renderTrajectory(List<Vec3> positions, Color color, float lineWidth)` — draws line segments via Render3DScheduler
  - `static void renderImpact(HitResult hit, Color color)` — draws outline box at impact position
  - `static Color resolveColor(TrajectoryType type, Entity entity, Color defaultColor)` — maps projectile type to a sensible color

- [ ] **Step 1: Write the file skeleton**

Create `TrajectorySimulator.java` with package, imports, class declaration, and all nested types:

```java
package com.github.epsilon.utils.render;

import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
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
import net.minecraft.world.phys.shapes.CollisionContext;
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
            double v0 = power * 3.0; // full-pull = 3.0
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
                positions.add(hit.getLocation()); // Last position = impact point
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
            net.minecraft.core.BlockPos.containing(pos.x, pos.y, pos.z)
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
```

- [ ] **Step 2: Commit**

```bash
git add common/src/main/java/com/github/epsilon/utils/render/TrajectorySimulator.java
git commit -m "feat: add TrajectorySimulator utility for projectile physics and rendering"
```

---

### Task 2: Create Trajectories module

**Files:**
- Create: `common/src/main/java/com/github/epsilon/modules/impl/render/Trajectories.java`

**Interfaces:**
- Consumes: `TrajectorySimulator.TrajectoryDescriptor`, `.resolveHeldItem()`, `.resolveEntity()`, `.simulate()`, `.getHitResult()`, `.renderTrajectory()`, `.renderImpact()`, `.resolveColor()` from Task 1
- Produces: `Trajectories.INSTANCE` singleton (registered in Task 3)

- [ ] **Step 1: Write the module file**

```java
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

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
    private final BoolSetting alwaysShowBow = boolSetting("Always Show Bow", false);
    private final ColorSetting heldItemsColor = colorSetting("Held Items Color", new Color(255, 255, 255, 200));

    // ── Entities group ──
    private final SettingGroup entitiesGroup = settingGroup("Entities");
    private final BoolSetting activeEntities = boolSetting("Active Entities", true);
    private final BoolSetting activeArrows = boolSetting("Active Arrows", true);
    private final ColorSetting entitiesColor = colorSetting("Entities Color", new Color(255, 200, 100, 200));

    // ── Shared ──
    private final IntSetting maxTicks = intSetting("Max Ticks", 300, 50, 1000, 50);
    private final DoubleSetting lineWidth = doubleSetting("Line Width", 1.0, 0.5, 5.0, 0.5);
    private final BoolSetting showImpact = boolSetting("Show Impact", true);
    private final ColorSetting impactColor = colorSetting("Impact Color", new Color(255, 0, 0, 200));

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (nullCheck() || !masterSwitch.getValue() || mc.gui.hud.isHidden()) return;

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

        // ── Held item trajectory ──
        if (heldItems.getValue()) {
            renderHeldItemTrajectory(partialTick);
        }

        // ── Entity trajectories ──
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
        Vec3 velocity = lookDir.scale(desc.params().initialVelocity());

        // Add player momentum
        if (desc.params().copiesPlayerVelocity()) {
            Vec3 delta = player.getDeltaMovement();
            velocity = velocity.add(
                delta.x,
                player.onGround() ? 0 : delta.y,
                delta.z
            );
        }
        // Account for crossbow's lack of player momentum copy
        if (!desc.params().copiesPlayerVelocity()) {
            // no-op, velocity is just look dir * initialVelocity
        }

        List<Vec3> positions = TrajectorySimulator.simulate(
            eyePos, velocity, desc.params(), desc.type(),
            maxTicks.getValue(), player
        );

        if (positions.size() < 2) return;

        Color lineColor = heldItemsColor.getValue();
        TrajectorySimulator.renderTrajectory(positions, lineColor, lineWidth.getValue().floatValue());

        if (showImpact.getValue()) {
            HitResult hit = TrajectorySimulator.getHitResult(
                eyePos, velocity, desc.params(), desc.type(),
                maxTicks.getValue(), player
            );
            TrajectorySimulator.renderImpact(hit, impactColor.getValue());
        }
    }

    private void renderEntityTrajectories(float partialTick) {
        List<Vec3> positions;
        for (Entity entity : mc.level.entitiesForRendering()) {
            TrajectoryDescriptor desc = TrajectorySimulator.resolveEntity(
                entity, activeArrows.getValue(), true
            );
            if (desc == null) continue;

            Vec3 pos = entity.getEyePosition(partialTick).subtract(0, 0.1, 0);
            Vec3 velocity = entity.getDeltaMovement();

            positions = TrajectorySimulator.simulate(
                pos, velocity, desc.params(), desc.type(),
                maxTicks.getValue(), entity
            );

            if (positions.size() < 2) continue;

            Color lineColor = entitiesColor.getValue();
            TrajectorySimulator.renderTrajectory(positions, lineColor, lineWidth.getValue().floatValue());

            if (showImpact.getValue()) {
                HitResult hit = TrajectorySimulator.getHitResult(
                    pos, velocity, desc.params(), desc.type(),
                    maxTicks.getValue(), entity
                );
                TrajectorySimulator.renderImpact(hit, impactColor.getValue());
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add common/src/main/java/com/github/epsilon/modules/impl/render/Trajectories.java
git commit -m "feat: add Trajectories module with held-item and entity trajectory preview"
```

---

### Task 3: Register module in ModuleHolder

**Files:**
- Modify: `common/src/main/java/com/github/epsilon/holders/ModuleHolder.java`

**Interfaces:**
- Consumes: `Trajectories.INSTANCE` from Task 2

- [ ] **Step 1: Add import and registration**

In `ModuleHolder.java`, add the import near other render module imports (around line 22):

```java
import com.github.epsilon.modules.impl.render.Trajectories;
```

In `initModules()`, add the registration line in the Render section (after `addModule(JumpCircle.INSTANCE);` around line 140):

```java
addModule(Trajectories.INSTANCE);
```

- [ ] **Step 2: Commit**

```bash
git add common/src/main/java/com/github/epsilon/holders/ModuleHolder.java
git commit -m "feat: register Trajectories module"
```

---

### Task 4: Verify compilation

- [ ] **Step 1: Attempt to compile**

```bash
cd "D:/Epsilon-26.2.x" && ./gradlew :common:compileJava 2>&1 | tail -40
```

- [ ] **Step 2: Fix any compilation errors**

Common issues to check:
- `BowItem.getPowerForTime()` — verify this static method exists in the Minecraft version
- `Vec3.directionFromRotation()` — verify this static method exists
- `ProjectileUtil.getEntityHitResult()` — verify parameter types match (may differ from LiquidBounce's Kotlin syntax)
- `AbstractThrownPotion.getColor()` — check method name (might be `getPotionColor()` or similar)
- `mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)` — verify method chain
- `mc.gui.hud.isHidden()` — verify method exists

If any method signatures differ, adjust the code to match the actual fabric-loader + Minecraft 1.21.4 API.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A && git commit -m "fix: adjust API calls for Minecraft 1.21.4 compatibility"
```
