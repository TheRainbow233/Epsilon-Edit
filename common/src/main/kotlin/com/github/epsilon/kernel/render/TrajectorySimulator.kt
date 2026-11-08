package com.github.epsilon.utils.render

import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler
import com.github.epsilon.kernel.MinecraftWrapper.mc
import java.awt.Color
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.ThrownTrident
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.EggItem
import net.minecraft.world.item.EnderpearlItem
import net.minecraft.world.item.ExperienceBottleItem
import net.minecraft.world.item.FireChargeItem
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SnowballItem
import net.minecraft.world.item.ThrowablePotionItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.WindChargeItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * 客户端投掷物弹道模拟器。
 *
 * 逐 tick 复现重力、阻力、入水与碰撞顺序，供自动投掷与轨迹渲染使用。
 */
object TrajectorySimulator {

    data class TrajectoryParams(
        val gravity: Double,
        val hitboxRadius: Double,
        val initialVelocity: Double,
        val drag: Double,
        val dragInWater: Double,
        val copiesPlayerVelocity: Boolean,
    ) {
        fun hitbox(center: Vec3): AABB {
            val r = hitboxRadius
            return AABB(
                center.x - r, center.y - r, center.z - r,
                center.x + r, center.y + r, center.z + r,
            )
        }
    }

    enum class TrajectoryType(val initialTickCorrection: Boolean) {
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
        WIND_CHARGE(true),
    }

    data class TrajectoryDescriptor(val params: TrajectoryParams, val type: TrajectoryType)

    data class SimulationResult(val positions: List<Vec3>, val hitResult: HitResult?)

    val GENERIC = TrajectoryParams(0.03, 0.25, 1.5, 0.99, 0.8, true)
    val PERSISTENT = TrajectoryParams(0.05, 0.5, 1.5, 0.99, 0.99, true)
    val POTION = TrajectoryParams(0.05, 0.25, 0.5, 0.99, 0.8, true)
    val EXP_BOTTLE = TrajectoryParams(0.07, 0.25, 0.7, 0.99, 0.8, true)
    val FISHING_ROD = TrajectoryParams(0.04, 0.25, 1.5, 0.92, 0.92, true)
    val TRIDENT = TrajectoryParams(0.05, 0.5, 2.5, 0.99, 0.99, true)
    val CROSSBOW_ARROW = TrajectoryParams(0.05, 0.5, 3.15, 0.99, 0.99, false)
    val FIREWORK = TrajectoryParams(0.0, 0.25, 1.6, 1.0, 1.0, false)
    val FIREBALL = TrajectoryParams(0.0, 1.0, 1.5, 0.99, 0.8, true)
    val WIND_CHARGE = TrajectoryParams(0.0, 1.0, 1.5, 1.0, 1.0, true)
    val ENTITY_ARROW = TrajectoryParams(0.05, 0.3, 1.5, 0.99, 0.6, true)

    val CROSSBOW = TrajectoryDescriptor(CROSSBOW_ARROW, TrajectoryType.ARROW)
    val POTION_DESC = TrajectoryDescriptor(POTION, TrajectoryType.POTION)
    val ENDER_PEARL_DESC = TrajectoryDescriptor(GENERIC, TrajectoryType.ENDER_PEARL)
    val SNOWBALL_DESC = TrajectoryDescriptor(GENERIC, TrajectoryType.SNOWBALL)
    val EGG_DESC = TrajectoryDescriptor(GENERIC, TrajectoryType.EGG)
    val EXP_BOTTLE_DESC = TrajectoryDescriptor(EXP_BOTTLE, TrajectoryType.EXP_BOTTLE)
    val FISHING_ROD_DESC = TrajectoryDescriptor(FISHING_ROD, TrajectoryType.FISHING_ROD)
    val TRIDENT_DESC = TrajectoryDescriptor(TRIDENT, TrajectoryType.TRIDENT)
    val FIREWORK_DESC = TrajectoryDescriptor(FIREWORK, TrajectoryType.FIREWORK)
    val FIREBALL_DESC = TrajectoryDescriptor(FIREBALL, TrajectoryType.FIREBALL)
    val WIND_CHARGE_DESC = TrajectoryDescriptor(WIND_CHARGE, TrajectoryType.WIND_CHARGE)
    val ENTITY_ARROW_DESC = TrajectoryDescriptor(ENTITY_ARROW, TrajectoryType.ARROW)

    // -- 物品 / 实体 → 描述符 --

    fun resolveHeldItem(player: Player, stack: ItemStack, alwaysShowBow: Boolean): TrajectoryDescriptor? {
        return when (val item = stack.item) {
            is BowItem -> {
                val useTicks = if (alwaysShowBow && player.ticksUsingItem < 1) 40 else player.ticksUsingItem
                val power = BowItem.getPowerForTime(useTicks)
                if (power < 0.1f) return null
                TrajectoryDescriptor(TrajectoryParams(0.05, 0.5, power * 3.0, 0.99, 0.99, true), TrajectoryType.ARROW)
            }

            is CrossbowItem -> CROSSBOW
            is FishingRodItem -> FISHING_ROD_DESC
            is ThrowablePotionItem -> POTION_DESC
            is TridentItem -> TRIDENT_DESC
            is SnowballItem -> SNOWBALL_DESC
            is EnderpearlItem -> ENDER_PEARL_DESC
            is EggItem -> EGG_DESC
            is ExperienceBottleItem -> EXP_BOTTLE_DESC
            is FireChargeItem -> FIREBALL_DESC
            is WindChargeItem -> WIND_CHARGE_DESC
            else -> null
        }
    }

    fun resolveEntity(entity: Entity, activeArrows: Boolean, activeOthers: Boolean): TrajectoryDescriptor? {
        if (activeArrows && entity is AbstractArrow && entity !is ThrownTrident &&
            entity.deltaMovement.lengthSqr() > 1.0E-4
        ) {
            return ENTITY_ARROW_DESC
        }
        if (!activeOthers) return null

        return when (entity) {
            is AbstractThrownPotion -> POTION_DESC
            is ThrownTrident -> if (entity.deltaMovement.lengthSqr() > 1.0E-4) TRIDENT_DESC else null
            is ThrownEnderpearl -> ENDER_PEARL_DESC
            is Snowball -> SNOWBALL_DESC
            is ThrownExperienceBottle -> EXP_BOTTLE_DESC
            is ThrownEgg -> EGG_DESC
            is FishingHook -> FISHING_ROD_DESC
            is FireworkRocketEntity -> FIREWORK_DESC
            is Fireball -> FIREBALL_DESC
            is WindCharge -> WIND_CHARGE_DESC
            else -> null
        }
    }

    // -- 模拟 --

    fun simulate(
        startPos: Vec3,
        startVelocity: Vec3,
        params: TrajectoryParams,
        type: TrajectoryType,
        maxTicks: Int,
        owner: Entity,
    ): SimulationResult {
        val world = mc.level ?: return SimulationResult(emptyList(), null)

        var pos = startPos
        var velocity = startVelocity
        val positions = ArrayList<Vec3>()
        positions.add(pos)
        var hitResult: HitResult? = null

        // 某些投掷物的首 tick 会先应用一次速度修正
        if (type.initialTickCorrection) {
            velocity = tickVelocity(velocity, params, world, pos)
        }

        val startTick = if (type.initialTickCorrection) 1 else 0

        for (tick in startTick until maxTicks) {
            if (pos.y < world.minY) break

            val prevPos = pos
            pos = pos.add(velocity)
            positions.add(pos)

            val hit = checkForHits(prevPos, pos, params, owner, world)
            if (hit != null) {
                positions.add(hit.location)
                hitResult = hit
                break
            }

            velocity = tickVelocity(velocity, params, world, pos)
        }

        return SimulationResult(positions, hitResult)
    }

    private fun tickVelocity(velocity: Vec3, params: TrajectoryParams, world: Level, pos: Vec3): Vec3 {
        val inWater = !world.getBlockState(
            BlockPos.containing(pos.x, pos.y, pos.z)
        ).fluidState.isEmpty
        val drag = if (inWater) params.dragInWater else params.drag
        return velocity.scale(drag).add(0.0, -params.gravity, 0.0)
    }

    private fun checkForHits(
        posBefore: Vec3,
        posAfter: Vec3,
        params: TrajectoryParams,
        owner: Entity,
        world: Level,
    ): HitResult? {
        val blockHit = world.clip(
            ClipContext(
                posBefore, posAfter,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner,
            )
        )
        if (blockHit.type != HitResult.Type.MISS) {
            return blockHit
        }

        val searchBox = params.hitbox(posBefore)
            .expandTowards(posAfter.subtract(posBefore))
            .inflate(1.0)

        val entityHit = ProjectileUtil.getEntityHitResult(
            owner, posBefore, posAfter, searchBox,
            { entity ->
                !entity.isSpectator && entity.isAlive &&
                        (entity.isPickable || entity === mc.player) &&
                        !owner.isPassengerOfSameVehicle(entity)
            },
            0.0,
        )

        if (entityHit != null && entityHit.type != HitResult.Type.MISS) {
            return entityHit
        }
        return null
    }

    // -- 渲染 --

    fun renderTrajectory(positions: List<Vec3>, color: Color, lineWidth: Float) {
        if (positions.size < 2) return
        for (index in 0 until positions.size - 1) {
            Render3DScheduler.addLine(positions[index], positions[index + 1], color, lineWidth)
        }
    }

    fun renderImpact(hit: HitResult?, color: Color) {
        if (hit == null) return
        val pos = hit.location
        val r = 0.15
        val box = AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r)
        Render3DScheduler.addOutlineBox(box, color)
    }

}
