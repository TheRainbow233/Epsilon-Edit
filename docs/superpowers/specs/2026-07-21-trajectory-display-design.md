# Trajectories (抛物线显示) — Design Specification

**Date**: 2026-07-21
**Reference**: LiquidBounce trajectory system at `D:\LiquidBounce-ref`
**Status**: Draft

---

## 1. Overview

A projectile trajectory preview module that predicts and renders the flight path of throwable items (bows, ender pearls, snowballs, etc.) held by the player and displays the actual trajectory of in-flight projectile entities (arrows, fireballs, etc.).

Rendering is done as a 3D line strip in the world, with optional hit-point markers on blocks/entities.

## 2. Architecture

Two-file design following Epsilon's module + utility pattern:

```
common/src/main/java/com/github/epsilon/
  modules/impl/render/Trajectories.java       ← Module: settings, event binding
  utils/render/TrajectorySimulator.java       ← Physics simulation + 3D rendering
```

`TrajectorySimulator` is a pure-logic utility (no state, all static methods) that can be reused by other modules (e.g., AimBot).

## 3. Settings

### Master
| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `masterSwitch` | BoolSetting | true | Global on/off |

### Held Items Group (手持物品预测)
| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `heldItems` | BoolSetting | true | Show trajectory for held throwable items |
| `alwaysShowBow` | BoolSetting | false | Show bow trajectory even without pulling |
| `heldItemsColor` | ColorSetting | Color(255, 255, 255, 200) | Line color for held item predictions |

### Entities Group (飞行实体轨迹)
| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `activeEntities` | BoolSetting | true | Show trajectory for in-flight projectile entities |
| `activeArrows` | BoolSetting | true | Include arrows (can be noisy) |
| `entitiesColor` | ColorSetting | Color(255, 200, 100, 200) | Line color for entity trajectories |

### Shared
| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `maxTicks` | IntSetting | 300 | Max simulation steps (50–1000) |
| `lineWidth` | DoubleSetting | 1.0 | Trajectory line width (0.5–5.0) |
| `showImpact` | BoolSetting | true | Show hit-point outline box |
| `impactColor` | ColorSetting | Color(255, 0, 0, 200) | Color of hit-point marker |

## 4. Core Data Types

### TrajectoryParams (record)
```java
record TrajectoryParams(
    double gravity,        // gravity per tick
    double hitboxRadius,   // projectile hitbox radius
    double initialVelocity, // starting speed
    double drag,           // velocity multiplier per tick (air)
    double dragInWater,    // velocity multiplier per tick (water)
    boolean copiesPlayerVelocity
) {}
```

Predefined constants for all vanilla projectile types (arrow, potion, pearl, snowball, egg, XP bottle, trident, fishing rod, firework, fireball, wind charge).

### TrajectoryType (enum)
```java
enum TrajectoryType {
    ARROW, POTION, ENDER_PEARL, SNOWBALL, EGG,
    EXP_BOTTLE, FISHING_ROD, TRIDENT, FIREWORK, FIREBALL, WIND_CHARGE
}
```

## 5. TrajectorySimulator API

```java
public final class TrajectorySimulator {

    // Simulate trajectory and return ordered list of world positions
    public static List<Vec3> simulate(
        Vec3 startPos, Vec3 startVelocity,
        TrajectoryParams params, TrajectoryType type,
        int maxTicks, Entity owner
    );

    // Resolve held item → TrajectoryParams + TrajectoryType (or null)
    public static @Nullable TrajectoryDescriptor resolveHeldItem(
        Player player, ItemStack stack, boolean alwaysShowBow
    );

    // Resolve in-flight entity → TrajectoryParams + TrajectoryType (or null)
    public static @Nullable TrajectoryDescriptor resolveEntity(
        Entity entity, boolean activeArrows, boolean activeOthers
    );

    // Render trajectory lines via Render3DScheduler
    public static void renderTrajectory(
        List<Vec3> positions, Color color, float lineWidth
    );

    // Render hit-point marker (outline box at impact position)
    public static void renderImpact(HitResult hit, Color color);

    // Convenience: compute + render in one call
    public static HitResult computeAndRender(
        Entity owner, TrajectoryDescriptor descriptor,
        int maxTicks, Color lineColor, float lineWidth,
        boolean showImpact, Color impactColor
    );

    record TrajectoryDescriptor(TrajectoryParams params, TrajectoryType type) {}
}
```

## 6. Simulation Algorithm

```
1. pos = player.eyeY - 0.1 (eye offset)
2. velocity = directionFromLook(yaw, pitch) * params.initialVelocity
   + player.deltaMovement (if copiesPlayerVelocity)
3. For PERSISTENT trajectory types: apply first-tick velocity correction
   (tick velocity without moving position, mimicking server spawn behavior)
4. for tick = 0 .. maxTicks:
   a. prevPos = pos.copy()
   b. if pos.y < world.minY → break
   c. newPos = pos + velocity
   d. checkForHits(prevPos, newPos) → HitResult?
      - Block: world.clip(ClipContext.COLLIDER, ClipContext.Fluid.NONE)
      - Entity: ProjectileUtil.getEntityHitResult()
   e. if hit → record positions, return hit
   f. pos = newPos, add to position list
   g. velocity.y -= gravity
   h. velocity *= drag (or dragInWater if in water)
```

Rendered as a line strip where each adjacent pair in `positions` is drawn via `Render3DScheduler.INSTANCE.addLine()`.

## 7. Item → Trajectory Mapping

| Item | TrajectoryParams | TrajectoryType |
|------|-----------------|----------------|
| BowItem | bowWithUsageDuration(useTicks) | ARROW |
| CrossbowItem | CROSSBOW_ARROW | ARROW or FIREWORK |
| FishingRodItem | FISHING_ROD | FISHING_ROD |
| ThrowablePotionItem | POTION | POTION |
| TridentItem | TRIDENT | TRIDENT |
| SnowballItem | GENERIC | SNOWBALL |
| EnderpearlItem | GENERIC | ENDER_PEARL |
| EggItem | GENERIC | EGG |
| ExperienceBottleItem | EXP_BOTTLE | EXP_BOTTLE |
| FireChargeItem | FIREBALL | FIREBALL |
| WindChargeItem | WIND_CHARGE | WIND_CHARGE |

Crossbow with firework rockets uses `FIREWORK_ROCKET` type; supports multishot spread angles (-10°, 0°, +10°).

## 8. Entity → Trajectory Mapping

| Entity Class | TrajectoryDescriptor |
|-------------|---------------------|
| Arrow (not in ground) | ENTITY_ARROW |
| ThrownTrident (not in ground) | TRIDENT |
| AbstractThrownPotion | POTION |
| ThrownEnderpearl | ENDER_PEARL |
| Snowball | SNOWBALL |
| ThrownExperienceBottle | EXP_BOTTLE |
| ThrownEgg | EGG |
| FishingHook | FISHING_BOBBER |
| FireworkRocketEntity | FIREWORK_ROCKET |
| Fireball | FIREBALL |
| WindCharge | WIND_CHARGE |

## 9. Rendering Pipeline

Uses `Render3DScheduler` (existing Epsilon infrastructure):
- Lines: `addLine(from, to, color, lineWidth)` for each trajectory segment
- Impact marker: `addOutlineBox(AABB, impactColor)` at hit position

All rendering happens in `@EventHandler void onRender3D(Render3DEvent)`.

## 10. Error Handling

- `nullCheck()` guard at start of event handlers (standard Epsilon pattern)
- `mc.gui.hud.isHidden()` guard to skip rendering when HUD is hidden
- Guard against empty/failed simulation results (no positions → don't draw)
- `isInFrontOfCamera` check on entity trajectories for performance

## 11. Testing

- Visual: enable module, hold a bow, verify prediction line appears and follows look direction
- Visual: throw an ender pearl, verify trajectory appears on the flying pearl
- Visual: hold crossbow with fireworks, verify firework trajectory type
- Visual: adjust lineWidth and color settings, verify real-time update
- Visual: disable showImpact, verify hit markers disappear
