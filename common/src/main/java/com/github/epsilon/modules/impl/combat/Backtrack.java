package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.AttackEntityEvent;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.GameLeftEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.events.impl.RespawnEvent;
import com.github.epsilon.managers.Managers;
import com.github.epsilon.managers.impl.target.TargetRequest;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.ColorSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.rotation.RotationUtils;
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Backtrack 战斗模块。
 *
 * 通过延迟处理服务器发来的目标实体位置更新包，让客户端看到的目标位置比服务器实际时间晚，
 * 从而在攻击时扩大有效命中窗口。
 *
 * 参考 LiquidBounce 的 ModuleBacktrack 实现思路，并结合 Epsilon 现有事件/渲染系统。
 */
public class Backtrack extends Module {

    public static final Backtrack INSTANCE = new Backtrack();

    private Backtrack() {
        super("Backtrack", Category.COMBAT);
    }

    private enum TargetMode {
        Attack,
        Range
    }

    // -- Settings --

    private final EnumSetting<TargetMode> targetMode = enumSetting("Target Mode", TargetMode.Range);

    private final IntSetting minDelay = intSetting("Min Delay", 100, 0, 500, 10);
    private final IntSetting maxDelay = intSetting("Max Delay", 150, 0, 500, 10);

    private final DoubleSetting range = doubleSetting("Range", 4.0, 1.0, 6.0, 0.1);
    private final DoubleSetting chance = doubleSetting("Chance", 100.0, 0.0, 100.0, 1.0);

    private final BoolSetting hurtTimePause = boolSetting("Hurt Time Pause", true);

    private final BoolSetting esp = boolSetting("ESP", true);
    private final ColorSetting sideColor = colorSetting("Side Color", new Color(255, 0, 0, 80), true, esp::getValue);
    private final ColorSetting lineColor = colorSetting("Line Color", new Color(255, 0, 0, 200), true, esp::getValue);

    // -- Runtime state --

    private LivingEntity target;
    private boolean attackPending;
    private boolean holding;
    private long holdDelayMs;

    /**
     * 队列中缓存的入站包（带时间戳）。
     */
    private final ConcurrentLinkedQueue<QueuedPacket> packetQueue = new ConcurrentLinkedQueue<>();

    /**
     * 从最新收到的包解析出的服务器真实位置。
     * 与客户端当前渲染的 target.position()（被延迟后的位置）区分开，
     * 用于判断是否需要提前 flush（有利才延迟策略）。
     */
    private Vec3 trackedPosition = Vec3.ZERO;

    private final Random random = new Random();

    private record QueuedPacket(long timestamp, Packet<?> packet) {
    }

    // -- Lifecycle --

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        flushAll();
        resetState();
    }

    private void resetState() {
        target = null;
        attackPending = false;
        holding = false;
        holdDelayMs = 0;
        packetQueue.clear();
        trackedPosition = Vec3.ZERO;
    }

    // -- Event handlers --

    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (nullCheck()) {
            flushAll();
            target = null;
            return;
        }

        updateTarget();

        boolean shouldHold = shouldHold();

        if (shouldHold) {
            if (!holding) {
                startHolding();
            }

            // 有利才延迟：如果服务器真实位置比当前延迟位置对玩家明显更有利，立即 flush。
            if (shouldFlushForAdvantage()) {
                flushAll();
            } else {
                flushExpired();
            }
        } else {
            if (holding) {
                flushAll();
            }
            target = null;
            attackPending = false;
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();

        // 安全包：立即 flush 并重置，避免卡包导致异常。
        if (isSafetyPacket(packet)) {
            flushAll();
            target = null;
            return;
        }

        if (nullCheck() || !holding || target == null) {
            // 即使不在 holding 状态，也持续追踪服务器真实位置，
            // 这样目标一进入 holding 状态时 trackedPosition 是准确的。
            if (!nullCheck() && target != null && isTargetMovementPacket(packet)) {
                updateTrackedPosition(packet);
            }
            return;
        }

        if (!isTargetMovementPacket(packet)) {
            return;
        }

        Integer entityId = getEntityId(packet);
        if (entityId == null || entityId != target.getId() || entityId == mc.player.getId()) {
            return;
        }

        // 先更新服务器真实位置追踪。
        updateTrackedPosition(packet);

        // 取消并延迟处理该包。
        event.cancel();
        packetQueue.add(new QueuedPacket(System.currentTimeMillis(), packet));
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            attackPending = true;

            // 攻击模式下以被攻击实体作为目标；范围模式下也记录，用于触发回溯。
            if (targetMode.is(TargetMode.Attack) || target == null) {
                target = living;
                trackedPosition = target.position();
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!esp.getValue() || nullCheck() || target == null || target.isRemoved()) {
            return;
        }

        if (!holding) {
            return;
        }

        AABB box = target.getBoundingBox();
        Render3DScheduler.INSTANCE.addFilledBox(box, sideColor.getValue());
        Render3DScheduler.INSTANCE.addOutlineBox(box, lineColor.getValue().getRGB(), 1.5f);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        flushAll();
        resetState();
    }

    @EventHandler
    private void onRespawn(RespawnEvent event) {
        flushAll();
        resetState();
    }

    // -- Target selection --

    private void updateTarget() {
        if (targetMode.is(TargetMode.Range)) {
            target = Managers.TARGET.acquirePrimary(TargetRequest.of(
                    range.getValue(),
                    360f,
                    ClientSetting.INSTANCE.targetPlayer.getValue(),
                    ClientSetting.INSTANCE.targetMob.getValue(),
                    ClientSetting.INSTANCE.targetAnimal.getValue(),
                    ClientSetting.INSTANCE.targetVillager.getValue(),
                    ClientSetting.INSTANCE.targetInvisible.getValue(),
                    1
            ));

            if (target != null) {
                trackedPosition = target.position();
            }
        } else if (target != null) {
            // Attack 模式：验证目标是否仍然有效。
            if (!target.isAlive() || target.isDeadOrDying() || target.level() != mc.level) {
                target = null;
            }
        }
    }

    private boolean shouldHold() {
        if (target == null) {
            return false;
        }

        if (!target.isAlive() || target.isDeadOrDying()) {
            return false;
        }

        if (target.level() != mc.level) {
            return false;
        }

        double distance = RotationUtils.getEyeDistanceToEntity(target);
        if (distance > range.getValue()) {
            return false;
        }

        if (hurtTimePause.getValue() && target.hurtTime > 0) {
            return false;
        }

        if (random.nextDouble() * 100.0 >= chance.getValue()) {
            return false;
        }

        if (targetMode.is(TargetMode.Attack) && !attackPending) {
            return false;
        }

        return true;
    }

    // -- Holding control --

    private void startHolding() {
        int min = Math.min(minDelay.getValue(), maxDelay.getValue());
        int max = Math.max(minDelay.getValue(), maxDelay.getValue());
        holdDelayMs = min + random.nextInt(Math.max(1, max - min + 1));
        holding = true;
    }

    private void stopHolding() {
        holding = false;
        holdDelayMs = 0;
    }

    // -- Flush logic --

    /**
     * 按时间释放已过期的包。
     */
    private void flushExpired() {
        long now = System.currentTimeMillis();
        QueuedPacket queued;
        while ((queued = packetQueue.peek()) != null && now - queued.timestamp >= holdDelayMs) {
            packetQueue.poll();
            handlePacket(queued.packet);
        }
    }

    /**
     * 立即释放所有队列中的包。
     */
    private void flushAll() {
        QueuedPacket queued;
        while ((queued = packetQueue.poll()) != null) {
            handlePacket(queued.packet);
        }
        stopHolding();
    }

    /**
     * 有利才延迟策略：如果服务器真实位置比当前客户端延迟位置离玩家明显更近，
     * 则立即 flush，避免延迟反而导致打不到。
     */
    private boolean shouldFlushForAdvantage() {
        if (target == null || trackedPosition.equals(Vec3.ZERO)) {
            return false;
        }

        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 delayedPos = target.position();

        double delayedDistanceSq = eyePos.distanceToSqr(delayedPos);
        double trackedDistanceSq = eyePos.distanceToSqr(trackedPosition);

        // 只有当真实位置明显更近（至少近 0.05^2）时才 flush，避免抖动。
        return trackedDistanceSq < delayedDistanceSq - 0.0025;
    }

    @SuppressWarnings("unchecked")
    private void handlePacket(Packet<?> packet) {
        if (mc.getConnection() == null || mc.getConnection().getConnection() == null) {
            return;
        }

        try {
            ((Packet<net.minecraft.network.PacketListener>) packet).handle(mc.getConnection().getConnection().getPacketListener());
        } catch (Exception ignored) {
            // 与 ClientboundPacketManager.flush 的错误处理一致，避免崩溃。
        }
    }

    // -- Packet classification --

    private boolean isTargetMovementPacket(Packet<?> packet) {
        return packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundRotateHeadPacket
                || packet instanceof ClientboundSetEntityMotionPacket;
    }

    private boolean isSafetyPacket(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundRespawnPacket) {
            return true;
        }

        if (packet instanceof ClientboundSetHealthPacket healthPacket && healthPacket.getHealth() <= 0.0f) {
            return true;
        }

        if (packet instanceof ClientboundRemoveEntitiesPacket removePacket && target != null) {
            for (int id : removePacket.getEntityIds()) {
                if (id == target.getId()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 从客户端包中提取实体 id。返回 null 表示不是目标移动包或无法解析。
     */
    private Integer getEntityId(Packet<?> packet) {
        if (packet instanceof ClientboundMoveEntityPacket movePacket) {
            Entity entity = movePacket.getEntity(mc.level);
            return entity != null ? entity.getId() : null;
        }

        if (packet instanceof ClientboundTeleportEntityPacket teleportPacket) {
            return teleportPacket.id();
        }

        if (packet instanceof ClientboundRotateHeadPacket headPacket) {
            Entity entity = headPacket.getEntity(mc.level);
            return entity != null ? entity.getId() : null;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket) {
            return motionPacket.id();
        }

        return null;
    }

    /**
     * 根据入站包更新服务器真实位置追踪。
     *
     * 注意：holding 状态下目标实体的位置包被延迟，实体对象本身仍停留在旧位置，
     * 因此 trackedPosition 需要独立维护，不能依赖 target.position()。
     */
    private void updateTrackedPosition(Packet<?> packet) {
        if (target == null) {
            return;
        }

        if (packet instanceof ClientboundTeleportEntityPacket teleportPacket) {
            if (teleportPacket.id() == target.getId()) {
                trackedPosition = teleportPacket.change().position();
            }
            return;
        }

        if (packet instanceof ClientboundMoveEntityPacket movePacket) {
            Entity entity = movePacket.getEntity(mc.level);
            if (entity == null || entity.getId() != target.getId()) {
                return;
            }

            if (movePacket.hasPosition()) {
                // ClientboundMoveEntityPacket 使用 1/4096 block 的定点数表示相对位移。
                trackedPosition = trackedPosition.add(
                        movePacket.getXa() / 4096.0,
                        movePacket.getYa() / 4096.0,
                        movePacket.getZa() / 4096.0
                );
            }
        }

        // RotateHead 和 SetEntityMotion 不直接改变位置，不需要更新 trackedPosition。
    }

}
