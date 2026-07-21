# AutoProjectile — Design Specification

**Date**: 2026-07-22
**Status**: Draft

---

## 1. Overview

Automatically throws held projectiles (snowballs, eggs, potions, ender pearls, XP bottles) at enemies using trajectory simulation to calculate launch angles. Targets KillAura's current target when available, falls back to nearest valid enemy.

## 2. Architecture

Single file: `common/src/main/java/com/github/epsilon/modules/impl/combat/AutoProjectile.java`

Reuses existing infrastructure:
- `TrajectorySimulator` — projectile physics simulation + hit detection
- `Managers.TARGET` — entity filtering / KillAura target resolution
- `ClientSetting` — player/mob/animal/invisible target filters

## 3. Settings

| Setting | Type | Default | Range |
|---------|------|---------|-------|
| `targetMode` | EnumSetting | KillAura | KillAura, Nearest |
| `range` | DoubleSetting | 20.0 | 5.0–50.0 |
| `maxAngle` | DoubleSetting | 30.0 | 5.0–90.0 (degrees, crosshair tolerance) |
| `silentRotate` | BoolSetting | true | |
| `maxTicks` | IntSetting | 100 | 30–300 (simulation steps) |

## 4. Algorithm

```
1. If player not holding throwable item → skip
2. Get target:
   - mode=KillAura → Managers.TARGET.acquirePrimary(targetRequest)
   - mode=Nearest → closest enemy in range
3. If no target or target out of range → skip
4. Compute aim angle:
   For pitch from -90 to 0 (step: 1°):
     velocity = lookDir(yaw=player.yaw, pitch) * initialVelocity + player momentum
     result = TrajectorySimulator.simulate(handPos, velocity, params, type, maxTicks, player)
     if result.hitResult is EntityHitResult && entity == target → HIT
   Pick best pitch (closest yaw to player view)
5. If angle difference > maxAngle → skip (not looking at target)
6. Silent rotate to computed angle + right-click
```

## 5. Edge Cases
- Crossbow: skip (needs charging)
- Bow: skip (needs pull timing)
- Pearl: skip if target too close (< 3 blocks, self-damage risk)
- Combo: respect combat pause from KillAura

## 6. Files
- Create: `AutoProjectile.java` (~200 lines)
- Modify: `ModuleHolder.java` (1 line registration)
