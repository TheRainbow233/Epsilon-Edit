package com.github.epsilon.modules.impl.movement

import com.github.epsilon.assets.i18n.EpsilonTranslations
import com.github.epsilon.events.api.EventPriority
import com.github.epsilon.events.api.handler
import com.github.epsilon.events.api.safeEventHandler
import com.github.epsilon.events.impl.KeyboardInputEvent
import com.github.epsilon.events.impl.PacketEvent
import com.github.epsilon.events.impl.PlayerTickEvent
import com.github.epsilon.events.impl.Render3DEvent
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler
import com.github.epsilon.kernel.gameplay.grimac.C03Interact.placeBlock
import com.github.epsilon.kernel.gameplay.inventory.FindItemResult
import com.github.epsilon.kernel.gameplay.inventory.InvUtils.find
import com.github.epsilon.kernel.gameplay.inventory.InvUtils.findInHotbar
import com.github.epsilon.kernel.gameplay.inventory.InvUtils.invSwap
import com.github.epsilon.kernel.gameplay.inventory.InvUtils.invSwapBack
import com.github.epsilon.kernel.gameplay.inventory.InvUtils.swap
import com.github.epsilon.kernel.gameplay.movement.FallingPlayer
import com.github.epsilon.kernel.gameplay.player.SwingMode
import com.github.epsilon.kernel.gameplay.rotation.RaycastUtils.overBlock
import com.github.epsilon.kernel.gameplay.rotation.Rotation
import com.github.epsilon.kernel.gameplay.rotation.RotationUtils.calculate
import com.github.epsilon.kernel.jvm.NonNullContext
import com.github.epsilon.kernel.jvm.runSafe
import com.github.epsilon.kernel.jvm.runSafeOrElse
import com.github.epsilon.kernel.jvm.runSafeOrFalse
import com.github.epsilon.kernel.math.MathUtils
import com.github.epsilon.kernel.render.animation.Easing
import com.github.epsilon.managers.NotificationManager
import com.github.epsilon.managers.RotationManager
import com.github.epsilon.modules.Category
import com.github.epsilon.modules.Module
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.util.Mth
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.StandingAndWallBlockItem
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.math.roundToInt
import com.github.epsilon.kernel.gameplay.inventory.InvUtils.swapBack as restoreHotbarSlot

object Scaffold : Module("Scaffold", Category.MOVEMENT) {

    private enum class Mode {
        TellyBridge,
        GodBridge,
    }

    private enum class RotationMode {
        Static,
        Hypixel,
        Heypixel,
        Packet,
    }

    private enum class RaytraceMode {
        Normal,
        Strict,
    }

    private enum class SwapMode {
        None,
        Normal,
        Silent,
        InvSwitch,
    }

    private val blacklistedBlocks by blockListSetting(
        "Blacklisted Blocks",
        listOf(
            Blocks.AIR,
            Blocks.WATER,
            Blocks.LAVA,
            Blocks.ENCHANTING_TABLE,
            Blocks.GLASS_PANE,
            Blocks.IRON_BARS,
            Blocks.SNOW,
            Blocks.COAL_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.EMERALD_ORE,
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.TORCH,
            Blocks.ANVIL,
            Blocks.NOTE_BLOCK,
            Blocks.JUKEBOX,
            Blocks.TNT,
            Blocks.GOLD_ORE,
            Blocks.IRON_ORE,
            Blocks.LAPIS_ORE,
            Blocks.STONE_PRESSURE_PLATE,
            Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,
            Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,
            Blocks.STONE_BUTTON,
            Blocks.LEVER,
            Blocks.TALL_GRASS,
            Blocks.TRIPWIRE,
            Blocks.TRIPWIRE_HOOK,
            Blocks.RAIL,
            Blocks.CORNFLOWER,
            Blocks.RED_MUSHROOM,
            Blocks.BROWN_MUSHROOM,
            Blocks.VINE,
            Blocks.SUNFLOWER,
            Blocks.LADDER,
            Blocks.FURNACE,
            Blocks.SAND,
            Blocks.CACTUS,
            Blocks.DISPENSER,
            Blocks.DROPPER,
            Blocks.CRAFTING_TABLE,
            Blocks.COBWEB,
            Blocks.PUMPKIN,
            Blocks.COBBLESTONE_WALL,
            Blocks.OAK_FENCE,
            Blocks.REDSTONE_TORCH,
            Blocks.FLOWER_POT,
            Blocks.SCAFFOLDING
        )
    )
    private val toggleOnTeleport by boolSetting("Toggle On Teleport", false)
    private val selfRescue by boolSetting("Self Rescue", true, { rotationMode != RotationMode.Packet })
    private val mode by enumSetting("Mode", Mode.TellyBridge)
    private val swapMode by enumSetting("Swap Mode", SwapMode.Normal)
    private val swapBack by boolSetting("Swap Back", true, { swapMode == SwapMode.Normal })
    private val snap by boolSetting("Snap", false, { mode == Mode.GodBridge && rotationMode != RotationMode.Packet })
    // 两次成功放置之间至少跳过的可放置机会（tick）；4 对应原版右键 4 tick 冷却，接近人手节奏。
    private val placeDelay by intSetting("Place Delay", 4, 0..10, 1, { mode != Mode.TellyBridge })
    private val edgeSneak by boolSetting("Edge Sneak", true, { mode != Mode.TellyBridge })
    private val cancelSprint by boolSetting("Cancel Sprint", true, { mode != Mode.TellyBridge })
    private val stopWhenNoBlocks by boolSetting("Stop When No Blocks", true, { mode != Mode.TellyBridge })
    private val degrees45 by boolSetting("45 Degrees", false, { rotationMode != RotationMode.Packet })
    private val rotationMode by enumSetting("Rotation Mode", RotationMode.Static)
    private val raytrace by enumSetting("Raytrace Mode", RaytraceMode.Normal, { rotationMode != RotationMode.Packet })
    private val rotationSpeed by intSetting(
        "Rotation Speed", 127, 10..180, 10,
        { rotationMode != RotationMode.Hypixel && rotationMode != RotationMode.Packet }
    )
    private val rotationSpeed2 by intSetting(
        "Rotation Speed 2", 36, 10..180, 10,
        { rotationMode == RotationMode.Heypixel }
    )
    private val rotationBackSpeed by intSetting(
        "Rotation Back Speed", 180, 10..180, 10,
        { mode == Mode.TellyBridge && rotationMode != RotationMode.Packet }
    )
    private val tellyTicks by intSetting(
        "Telly Ticks", 1, 0..6, 1,
        { mode == Mode.TellyBridge }
    )

    private val swingHand by boolSetting("Swing Hand", true)
    private val render by boolSetting("Render", true)
    private val fade by boolSetting("Fade", true, { render })
    private val fadeTime by intSetting("Fade Time", 500, 0..3000, 50, { render && fade })
    private val shrink by boolSetting("Shrink", false, { render })
    private val sideColor by colorSetting("Side Color", Color(255, 183, 197, 100), true, { render })
    private val lineColor by colorSetting("Line Color", Color(255, 105, 180), true, { render })

    private var airTicks = 0
    private var yLevel = 0
    private var blockPos: BlockPos? = null
    private var direction: Direction? = null
    private var rotation: Rotation? = null
    private var rotateCount = 0
    private var forwardInput = 0.0f
    private var strafeInput = 0.0f
    private var rawInputYaw = 0.0f
    private var lengthSqr = 4.5 * 4.5

    /** 相对移动方向的偏航候选，按“离移动方向由近到远”排列。 */
    private val YAW_OFFSETS = floatArrayOf(
        0f, 10f, -10f, 20f, -20f, 30f, -30f, 40f, -40f, 50f, -50f, 60f, -60f, 70f, -70f,
        80f, -80f, 90f, -90f, 100f, -100f, 110f, -110f, 120f, -120f, 130f, -130f, 140f,
        -140f, 150f, -150f, 160f, -160f, 170f, -170f, 180f
    )

    /** 俯仰候选，覆盖从接近垂直俯视到较平视的范围。 */
    private val PITCH_CANDIDATES = floatArrayOf(82f, 78f, 74f, 70f, 66f, 60f, 55f)

    /** 踩空预测取样距离：下一 tick 的步进。 */
    private const val NEAR_LOOKAHEAD = 0.12

    private var blockResult: FindItemResult = FindItemResult(-1, 0, 0)
    private var shouldSwapBack = false
    private var emergencyPlacementActive = false
    private var pearlUsePacketSent = false

    /** GodBridge 的放置冷却（tick）；其他模式不读取。 */
    private var placeCooldown = 0

    // 放置后保持朝向一小段时间，再回到移动方向的俯视角度，避免“放完立刻甩到下一块”的机械感。
    private var heldRotation: Rotation? = null
    private var holdUntil = 0L
    private var returnUntil = 0L

    // 跳搭起跳时的多段旋转序列，模拟人手起跳过程中逐步抬视角的过程。
    private val jumpStages = ArrayDeque<Rotation>()

    // 安全保护：本 tick 是否成功放置（用于判断“继续走是否安全”）。
    private var placedThisTick = false
    private var noBlocksNotified = false

    private val renderBoxes: MutableList<RenderInfo> = ArrayList()

    init {
        onEnable {
            airTicks = 0
            blockPos = null
            direction = null
            rotation = null
            rotateCount = 0
            blockResult = FindItemResult(-1, 0, 0)
            shouldSwapBack = false
            emergencyPlacementActive = false
            pearlUsePacketSent = false
            placeCooldown = 0
            heldRotation = null
            jumpStages.clear()
        }

        onDisable {
            yLevel = 0
            emergencyPlacementActive = false
            pearlUsePacketSent = false
            placeCooldown = 0
            heldRotation = null
            jumpStages.clear()
            if (shouldSwapBack) {
                runSafe { restoreHotbarSlot() }
                shouldSwapBack = false
            }
        }

        handler<Render3DEvent>(alwaysListening = true) {
            if (!render || renderBoxes.isEmpty()) return@handler

            val time = System.currentTimeMillis()
            val fadeMillis = fadeTime.toLong()

            renderBoxes.removeAll { box -> time - box.startTime > fadeMillis }

            for (box in renderBoxes) {
                val progress = ((time - box.startTime).toFloat() / fadeMillis).coerceIn(0.0f, 1.0f)

                var scale = 1.0
                if (box.shrink) {
                    scale = 1.0 - Easing.EASE_IN_OUT_EXPO.function.apply(progress)
                    if (scale < 0) scale = 0.0
                }

                val alphaFactor = if (box.fade) (1.0f - progress).coerceIn(0.0f, 1.0f) else 1.0f

                val boxSideColor = box.sideColor
                val boxLineColor = box.lineColor

                val side = Color(
                    boxSideColor.red, boxSideColor.green, boxSideColor.blue,
                    (boxSideColor.alpha * alphaFactor).toInt()
                )
                val line = Color(
                    boxLineColor.red, boxLineColor.green, boxLineColor.blue,
                    (boxLineColor.alpha * alphaFactor).toInt()
                )

                var renderBox = box.aabb
                if (box.shrink) {
                    renderBox = AABB.ofSize(
                        renderBox.center,
                        renderBox.xsize * scale,
                        renderBox.ysize * scale,
                        renderBox.zsize * scale
                    )
                }

                Render3DScheduler.addFilledBox(renderBox, side)
                Render3DScheduler.addOutlineBox(renderBox, line)
            }
        }

        safeEventHandler<PlayerTickEvent.Pre> { event ->
            if (!event.isCancelled) emergencyPlacementActive = false
            placedThisTick = false

            blockResult = findBlockResult()
            if (!blockResult.found()) {
                // 没有方块：如果正朝边缘走，提示一次；停手由 KeyboardInputEvent 统一处理。
                if (stopWhenNoBlocks && mode != Mode.TellyBridge && !noBlocksNotified && player.onGround() && isAboutToStepOff()) {
                    noBlocksNotified = true
                    NotificationManager.warning(
                        translatedName,
                        EpsilonTranslations.Notifications.SCAFFOLD_NO_BLOCKS.getTranslatedName(),
                        this@Scaffold.hashCode()
                    )
                }
                return@safeEventHandler
            }
            noBlocksNotified = false

            if (player.onGround()) {
                airTicks = 0
                yLevel = Mth.floor(player.y) - 1
                jumpStages.clear()
            } else {
                airTicks++
            }

            getBlockInfo()

            if (selfRescue && rotationMode != RotationMode.Packet) {
                var reachable = true
                if (player.deltaMovement.y < -0.1 && blockPos != null) {
                    val fallingPlayer = with(FallingPlayer(player)) { calculate(2) }
                    if (blockPos!!.y > fallingPlayer.y) {
                        reachable = false
                    }
                }
                val strength = player.deltaMovement.horizontal().length()
                if (strength >= 1.5) {
                    NotificationManager.warning(
                        translatedName,
                        EpsilonTranslations.Notifications.SCAFFOLD_FLYING_WARNING.getTranslatedName(),
                        this@Scaffold.hashCode()
                    )
                }
                if ((!reachable || strength >= 1.5) && rotateCount <= 8 && getBlockCount() >= 1 && canUseBlockResult()) {
                    emergencyPlacementActive = true
                    event.cancel()

                    rotateCount++
                    val pos = blockPos ?: return@safeEventHandler
                    val face = direction ?: return@safeEventHandler

                    val rotation = getRotation(pos, face)
                    RotationManager.rotations = rotation
                    RotationManager.updateActive(true)

                    connection.send(
                        ServerboundMovePlayerPacket.Rot(
                            rotation.yaw,
                            rotation.pitch,
                            player.onGround(),
                            player.horizontalCollision
                        )
                    )

                    placeBlock(pos, face)

                    return@safeEventHandler
                } else {
                    rotateCount = 0
                }
            }

            when (mode) {
                Mode.TellyBridge -> handleTelly()
                Mode.GodBridge -> handleNormal()
            }
        }

        safeEventHandler<KeyboardInputEvent>(EventPriority.HIGHEST) { event ->
            forwardInput = event.forward
            strafeInput = event.strafe
            rawInputYaw = Mth.wrapDegrees(
                (player.yRot + Math.toDegrees(atan2(-strafeInput.toDouble(), forwardInput.toDouble()))
                    .toFloat()).roundToInt().toFloat()
            )

            if (mode == Mode.TellyBridge && player.onGround() && !mc.options.keyJump.isDown && player.isMoving) {
                event.jump = true
            }

            // 跳搭依赖精确的起跳/放置节奏，不做这些保护，否则会打断连跳。
            if (mode != Mode.TellyBridge) {
                val noBlocks = !blockResult.found() || !canUseBlockResult()
                val aboutToFall = player.onGround() &&
                        isAboutToStepOff(event.forward.toDouble(), event.strafe.toDouble())
                if (aboutToFall) {
                    if (noBlocks && stopWhenNoBlocks) {
                        // 没有方块可放：直接停掉移动输入，避免继续前冲。
                        event.forward = 0.0f
                        event.strafe = 0.0f
                        event.sneak = true
                        event.jump = false
                        if (cancelSprint) {
                            event.sprint = false
                            player.setSprinting(false)
                        }
                    } else if (!placedThisTick) {
                        // 这一 tick 没有成功放置：取消冲刺（服务端可见）并按需蹲住。
                        if (cancelSprint) {
                            event.sprint = false
                            player.setSprinting(false)
                        }
                        if (edgeSneak) {
                            event.sneak = true
                            event.jump = false
                        }
                    }
                }
            }
        }

        safeEventHandler<PacketEvent.Receive> { event ->
            if (toggleOnTeleport && pearlUsePacketSent && event.packet is ClientboundPlayerPositionPacket) {
                pearlUsePacketSent = false
                NotificationManager.error(
                    translatedName,
                    EpsilonTranslations.Notifications.SCAFFOLD_TOGGLE_ON_TELEPORT.getTranslatedName()
                )
                isEnabled = false
            }
        }

        safeEventHandler<PacketEvent.Send> { event ->
            val packet = event.packet
            if (packet is ServerboundUseItemPacket) {
                val usedStack = player.getItemInHand(packet.hand)
                if (usedStack.`is`(Items.ENDER_PEARL) ||
                    usedStack.isEmpty && player.cooldowns.isOnCooldown(Items.ENDER_PEARL.defaultInstance)
                ) {
                    pearlUsePacketSent = true
                }
            }
        }
    }

    fun getBlockCount(): Int = runSafeOrElse(91) {
        var total = 0

        if (isValidStack(player.offhandItem)) {
            total += player.offhandItem.count
        }

        val maxSlot = if (swapMode == SwapMode.InvSwitch) player.inventory.containerSize else 9
        for (i in 0 until maxSlot) {
            val stack = player.inventory.getItem(i)
            if (isValidStack(stack)) {
                total += stack.count
            }
        }

        total
    }

    fun getBlockStack(): ItemStack = runSafeOrElse(ItemStack.EMPTY) {
        val offhand = player.offhandItem
        if (isValidStack(offhand)) return@runSafeOrElse offhand

        if (blockResult.found()) {
            val stack = if (blockResult.isOffhand()) player.offhandItem else player.inventory.getItem(blockResult.slot)
            if (isValidStack(stack)) return@runSafeOrElse stack
        }

        ItemStack.EMPTY
    }

    private fun handleTelly() {
        runSafe {
            if (rotationMode == RotationMode.Packet) {
                if (airTicks > tellyTicks) place()
                return@runSafe
            }

            if (player.onGround() && (strafeInput != 0.0f || forwardInput != 0.0f)) {
                val current = rotation
                RotationManager.setRotations(
                    Rotation(rawInputYaw, current?.pitch ?: player.xRot),
                    rotationBackSpeed.toDouble()
                )
                return@runSafe
            }

            // 起跳多段旋转：先走完 4 帧的抬视角序列，再进入正常瞄准/放置。
            if (airTicks == 1 && jumpStages.isEmpty()) {
                beginJumpStages()
            }
            if (jumpStages.isNotEmpty()) {
                RotationManager.setRotations(jumpStages.removeFirst(), 180.0)
                return@runSafe
            }

            val targetPos = blockPos
            val targetFace = direction
            if (targetPos != null && targetFace != null) {
                val aim = getRotation(targetPos, targetFace)
                rotation = aim
                var speed = rotationSpeed

                if (rotationMode == RotationMode.Hypixel) {
                    speed = if (airTicks <= 1) 127 else 35
                } else if (rotationMode == RotationMode.Heypixel) {
                    speed = if (airTicks <= 1) rotationSpeed else rotationSpeed2
                }

                applyAim(aim, speed)
            } else {
                holdAim()
            }

            if (airTicks > tellyTicks) {
                place()
            }
        }
    }

    private fun handleNormal() {
        runSafe {
            if (rotationMode != RotationMode.Packet && (Eagle.isOverEdge() || !snap || !player.onGround())) {
                val targetPos = blockPos
                val targetFace = direction
                if (targetPos != null && targetFace != null) {
                    val aim = getRotation(targetPos, targetFace)
                    rotation = aim
                    applyAim(aim, rotationSpeed)
                } else {
                    holdAim()
                }
            }
            place()
        }
    }

    /** 正常瞄准；速度沿用设置，避免连续放置时把朝向“甩”过去。 */
    private fun NonNullContext.applyAim(desired: Rotation, speed: Int) {
        RotationManager.setRotations(desired, speed.toDouble())
    }

    /** 没有可放置目标时，保持上一轮放置朝向一小段时间，再回到移动方向的俯视角度。 */
    private fun NonNullContext.holdAim() {
        val hold = heldRotation ?: return
        val now = System.currentTimeMillis()
        when {
            now < holdUntil -> RotationManager.setRotations(hold, 180.0)
            now < returnUntil -> RotationManager.setRotations(restRotation(), 55.0)
            else -> heldRotation = null
        }
    }

    /** 保持当前放置朝向一段时间；冲刺时最长，静止次之，行走最短（与原版搭路节奏接近）。 */
    private fun NonNullContext.beginHold() {
        val now = System.currentTimeMillis()
        heldRotation = RotationManager.getRotation()
        val holdMillis = when {
            player.isSprinting -> 650L
            !player.isMoving -> 450L
            else -> 110L
        }
        holdUntil = now + holdMillis
        returnUntil = holdUntil + 220L
    }

    /** 回落目标：移动方向 + 略微俯视，避免服务端朝向永远停在 85°。 */
    private fun NonNullContext.restRotation(): Rotation = Rotation(
        Mth.wrapDegrees(rawInputYaw + MathUtils.getRandom(-2.5f, 2.5f)),
        (78.0f + MathUtils.getRandom(-3.0f, 3.0f)).coerceIn(-90.0f, 90.0f)
    )

    /**
     * 预测“下一步是否会踩空”。
     *
     * 旧实现把整个包围盒平移后向下探测：包围盒有 0.6 宽，平移 0.35 仍会和当前方块相交，
     * 斜着走（两个轴各走一半）时几乎永远不会命中，这就是斜搭偶尔不蹲的原因。
     * 这里改为沿真实移动方向取样“脚下支撑点”，取 0.12 格和 0.35+速度 两个距离，
     * 任意一点下方没有碰撞形状就视为即将踩空。
     */
    private fun NonNullContext.isAboutToStepOff(
        forward: Double = player.input.moveVector.y.toDouble(),
        strafe: Double = player.input.moveVector.x.toDouble(),
    ): Boolean {
        if (!player.onGround()) return false

        val speed = player.deltaMovement.horizontal().length()
        val dirX: Double
        val dirZ: Double
        if (speed > 0.03) {
            // 优先用真实速度方向，兼容 MovementFix / 惯性 / 击退
            dirX = player.deltaMovement.x / speed
            dirZ = player.deltaMovement.z / speed
        } else {
            if (forward == 0.0 && strafe == 0.0) return false
            val rad = Math.toRadians(player.yRot.toDouble())
            val sin = sin(rad)
            val cos = cos(rad)
            val rawX = strafe * cos - forward * sin
            val rawZ = forward * cos + strafe * sin
            val length = hypot(rawX, rawZ)
            if (length < 1.0E-4) return false
            dirX = rawX / length
            dirZ = rawZ / length
        }

        val pos = player.position()
        val far = maxOf(0.35, speed * 1.2).coerceAtMost(0.7)
        return !hasSupportUnder(pos.x + dirX * NEAR_LOOKAHEAD, pos.z + dirZ * NEAR_LOOKAHEAD) ||
                !hasSupportUnder(pos.x + dirX * far, pos.z + dirZ * far)
    }

    /** 检查某个水平位置脚下是否还有碰撞形状（薄盒采样，兼容台阶/地毯/雪层等非整格方块）。 */
    private fun NonNullContext.hasSupportUnder(x: Double, z: Double): Boolean {
        val box = AABB(
            x - 0.02, player.y - 0.2, z - 0.02,
            x + 0.02, player.y + 0.01, z + 0.02
        )
        return level.getBlockCollisions(player, box).any()
    }

    /** 跳搭起跳的四段旋转：先回望，再逐步转回移动方向并抬高俯仰角。 */
    private fun beginJumpStages() {
        val base = Mth.wrapDegrees(rawInputYaw)
        val side = if (Random.nextBoolean()) 1.0f else -1.0f
        heldRotation = null
        jumpStages.clear()
        jumpStages.add(Rotation(Mth.wrapDegrees(base + 180.0f + MathUtils.getRandom(-4.0f, 4.0f)), 22.0f + MathUtils.getRandom(-3.0f, 3.0f)))
        jumpStages.add(Rotation(Mth.wrapDegrees(base + side * 112.0f + MathUtils.getRandom(-4.0f, 4.0f)), 43.0f + MathUtils.getRandom(-3.0f, 3.0f)))
        jumpStages.add(Rotation(Mth.wrapDegrees(base + side * 34.0f + MathUtils.getRandom(-2.0f, 2.0f)), 52.0f + MathUtils.getRandom(-2.0f, 2.0f)))
        jumpStages.add(Rotation(Mth.wrapDegrees(base + side * 31.0f + MathUtils.getRandom(-2.0f, 2.0f)), 67.0f + MathUtils.getRandom(-2.0f, 2.0f)))
    }

    private fun place() {
        runSafe {
            if (!onAir() || blockPos == null || direction == null || !canUseBlockResult()) {
                return@runSafe
            }
            val pos = blockPos ?: return@runSafe
            val face = direction ?: return@runSafe

            if (rotationMode != RotationMode.Packet) {
                val skip = when (raytrace) {
                    RaytraceMode.Normal -> !overBlock(RotationManager.getRotation(), pos)
                    RaytraceMode.Strict -> !overBlock(RotationManager.getRotation(), pos, face)
                }
                if (skip) return@runSafe
            }

            // 只在“本来这一 tick 就能放”时消耗延迟；跳搭由 airTicks/tellyTicks 控制节奏，不参与延迟。
            if (mode != Mode.TellyBridge && placeCooldown > 0) {
                placeCooldown--
                return@runSafe
            }

            if (placeBlock(pos, face)) {
                if (mode != Mode.TellyBridge) {
                    placeCooldown = nextPlaceCooldown()
                }
                placedThisTick = true
                beginHold()
            }
        }
    }

    /** 下一次放置前要跳过的机会数；[placeDelay] 表示目标间隔 tick，这里做 ±1 抖动。 */
    private fun nextPlaceCooldown(): Int {
        if (placeDelay <= 1) return 0
        val jitter = if (placeDelay >= 3) Random.nextInt(-1, 2) else 0
        return (placeDelay - 1 + jitter).coerceAtLeast(0)
    }

    private fun NonNullContext.placeBlock(pos: BlockPos, face: Direction): Boolean {
        swap()

        val hand = blockResult.getHand()
        val hit = BlockHitResult(getVec3(pos, face), face, pos, false)

        val result = if (rotationMode == RotationMode.Packet) {
            placeBlock(hand, hit)
        } else {
            gameMode.useItemOn(player, hand, hit)
        }

        val success = result.consumesAction()
        if (success) {
            SwingMode.of(swingHand).interactSwing(hand)

            if (render) {
                renderBoxes.add(
                    RenderInfo(
                        AABB(pos.relative(face)),
                        lineColor,
                        sideColor,
                        System.currentTimeMillis(),
                        fade,
                        shrink
                    )
                )
            }
        }

        swapBack()
        return success
    }

    private fun getYLevel(): Int = runSafeOrElse(0) {
        if ((!mc.options.keyJump.isDown && player.isMoving && mode == Mode.TellyBridge) &&
            abs(yLevel - (Mth.floor(player.y) - 1)) <= 1.25
        ) {
            return@runSafeOrElse yLevel
        }
        Mth.floor(player.y) - 1
    }

    private fun getBlockInfo() {
        runSafe {
            lengthSqr = 4.5 * 4.5
            blockPos = null
            direction = null

            val baseVec = player.eyePosition
            val base = BlockPos.containing(baseVec.x, getYLevel().toDouble(), baseVec.z)
            val baseX = base.x
            val baseZ = base.z

            if (!onAir()) {
                return@runSafe
            }

            if (checkBlock(baseVec, base)) {
                return@runSafe
            }

            for (d in 1..6) {
                if (checkBlock(baseVec, BlockPos(baseX, getYLevel() - d, baseZ))) {
                    return@runSafe
                }

                for (x in 0..d) {
                    for (z in 0..(d - x)) {
                        val y = d - x - z
                        for (rev1 in 0..1) {
                            for (rev2 in 0..1) {
                                val pos = BlockPos(
                                    baseX + (if (rev1 == 0) x else -x),
                                    getYLevel() - y,
                                    baseZ + (if (rev2 == 0) z else -z)
                                )
                                checkBlock(baseVec, pos)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkBlock(baseVec: Vec3, pos: BlockPos): Boolean = runSafeOrFalse {
        if (!onAir() || pos.y > getYLevel()) {
            return@runSafeOrFalse false
        }

        // 目标位置必须是可替换方块（或液体）；否则 use-item 只会被服务端静默拒绝，还会产生无效点击包。
        val targetState = level.getBlockState(pos)
        if (!targetState.canBeReplaced() && targetState.fluidState.isEmpty) {
            return@runSafeOrFalse false
        }

        val center = Vec3.atBottomCenterOf(pos)
        for (dir in Direction.entries) {
            val normal = dir.unitVec3
            val hit = center.add(normal.scale(0.5))
            val baseBlockPos = pos.relative(dir)

            val state: BlockState = level.getBlockState(baseBlockPos)
            if (state.getCollisionShape(level, baseBlockPos).isEmpty || state.getMenuProvider(
                    level,
                    baseBlockPos
                ) != null
            ) {
                continue
            }

            val face = dir.opposite
            val relevant = hit.subtract(baseVec)

            if (relevant.lengthSqr() > lengthSqr || relevant.dot(normal) < 0.0) {
                continue
            }

            if (face == Direction.UP && player.isMoving && !mc.options.keyJump.isDown) {
                continue
            }

            lengthSqr = relevant.lengthSqr()
            blockPos = baseBlockPos
            direction = face
            return@runSafeOrFalse true
        }

        false
    }

    private fun getRotation(pos: BlockPos?, direction: Direction?): Rotation = runSafeOrElse(Rotation(0.0f, 0.0f)) {
        val current = rotation ?: return@runSafeOrElse Rotation(Mth.wrapDegrees(player.yRot - 135.0f), 82.0f)

        if (!onAir() || pos == null || direction == null) {
            return@runSafeOrElse current
        }

        val calculated = calculate(pos, direction)
        // 以移动方向为基准由近到远扫描，优先保持“朝着行进方向下方看”的姿势，而不是每次都绕到正后方。
        val base = Mth.wrapDegrees(rawInputYaw)

        for (offset in YAW_OFFSETS) {
            val yaw = Mth.wrapDegrees(base + offset + MathUtils.getRandom(-0.7f, 0.7f))
            if (degrees45 && abs(Mth.wrapDegrees(yaw) % 90.0f) < 1.0f) {
                continue
            }
            for (pitchBase in PITCH_CANDIDATES) {
                val candidate = Rotation(yaw, pitchBase + MathUtils.getRandom(-0.8f, 0.8f))
                if (overBlock(candidate, pos, direction)) {
                    return@runSafeOrElse candidate
                }
            }
        }

        calculated
    }

    private fun onAir(): Boolean = runSafeOrFalse {
        val baseVec = player.eyePosition
        val base = BlockPos.containing(baseVec.x, getYLevel().toDouble(), baseVec.z)
        level.getBlockState(base).canBeReplaced()
    }

    private fun getVec3(pos: BlockPos, face: Direction): Vec3 {
        var x = pos.x + 0.5
        var y = pos.y + 0.5
        var z = pos.z + 0.5

        if (face != Direction.UP && face != Direction.DOWN) {
            y += 0.08
        } else {
            x += MathUtils.getRandom(-0.3, 0.3)
            z += MathUtils.getRandom(-0.3, 0.3)
        }

        if (face == Direction.WEST || face == Direction.EAST) {
            z += MathUtils.getRandom(-0.3, 0.3)
        }

        if (face == Direction.SOUTH || face == Direction.NORTH) {
            x += MathUtils.getRandom(-0.3, 0.3)
        }

        return Vec3(x, y, z)
    }

    private fun findBlockResult(): FindItemResult = runSafeOrElse(FindItemResult(-1, 0, 0)) {
        val offhandStack = player.offhandItem
        if (isValidStack(offhandStack)) {
            return@runSafeOrElse FindItemResult(40, offhandStack.count, offhandStack.maxStackSize)
        }
        if (swapMode == SwapMode.InvSwitch) {
            find { isValidStack(it) }
        } else {
            findInHotbar { isValidStack(it) }
        }
    }

    private fun canUseBlockResult(): Boolean = runSafeOrFalse {
        if (blockResult.isOffhand()) {
            return@runSafeOrFalse isValidStack(player.offhandItem)
        }
        swapMode != SwapMode.None || isValidStack(player.inventory.selectedItem)
    }

    private fun swap() {
        runSafe {
            if (blockResult.isOffhand()) {
                return@runSafe
            }

            when (swapMode) {
                SwapMode.Normal -> {
                    val selectedSlot = player.inventory.selectedSlot
                    swap(blockResult.slot, true)
                    if (swapBack && blockResult.slot != selectedSlot) {
                        shouldSwapBack = true
                    }
                }

                SwapMode.Silent -> swap(blockResult.slot, true)
                SwapMode.InvSwitch -> invSwap(blockResult.slot)
                SwapMode.None -> {
                }
            }
        }
    }

    private fun NonNullContext.swapBack() {
        if (blockResult.isOffhand()) {
            return
        }

        when (swapMode) {
            SwapMode.Silent -> restoreHotbarSlot()
            SwapMode.InvSwitch -> invSwapBack()
            else -> {
            }
        }
    }

    private fun isValidStack(stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty || stack.item !is BlockItem) {
            return false
        }

        val name = stack.displayName.string
        if (name.contains("Click") || name.contains("点击")) {
            return false
        }

        if (stack.item is StandingAndWallBlockItem) {
            return false
        }

        val block = (stack.item as BlockItem).block
        return !(block is FlowerBlock || block is BushBlock || block is NetherFungusBlock || block is CropBlock) && block !is SlabBlock && !blacklistedBlocks.contains(
            block
        )
    }

    val isEmergencyPlacementActive: Boolean
        get() = isEnabled && emergencyPlacementActive

    private data class RenderInfo(
        val aabb: AABB,
        val lineColor: Color,
        val sideColor: Color,
        val startTime: Long,
        val fade: Boolean,
        val shrink: Boolean,
    )

}
