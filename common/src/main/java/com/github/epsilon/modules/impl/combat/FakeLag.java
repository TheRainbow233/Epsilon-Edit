package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.BlinkPacketEvent;
import com.github.epsilon.events.impl.BlinkPacketEvent.Action;
import com.github.epsilon.events.impl.BlinkPacketEvent.TransferOrigin;
import com.github.epsilon.events.impl.GameLeftEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.RespawnEvent;
import com.github.epsilon.managers.Managers;
import com.github.epsilon.managers.impl.network.ServerboundPacketManager;
import com.github.epsilon.managers.impl.target.TargetRequest;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.settings.impl.MultiEnumSetting;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * Fake Lag 战斗模块。
 *
 * 通过 BlinkManager 的 BlinkPacketEvent 投票机制延迟出站包来模拟网络延迟，
 * 让敌人难以命中玩家的真实位置。参考 LiquidBounce 的 ModuleFakeLag 实现。
 */
public class FakeLag extends Module {

    public static final FakeLag INSTANCE = new FakeLag();

    private FakeLag() {
        super("Fake Lag", Category.COMBAT);
    }

    // -- Modes --

    private enum Mode {
        Constant,
        Dynamic
    }

    private enum FlushOn {
        EntityInteract,
        BlockInteract,
        Action
    }

    // -- Settings --

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Dynamic);

    private final DoubleSetting range = doubleSetting("Range", 5.0, 1.0, 10.0, 0.5);

    private final IntSetting delayMin = intSetting("Delay Min", 300, 0, 1000, 50);
    private final IntSetting delayMax = intSetting("Delay Max", 600, 0, 1000, 50);

    private final IntSetting recoilTime = intSetting("Recoil Time", 250, 0, 1000, 50);

    private final MultiEnumSetting<FlushOn> flushOn = multiEnumSetting("Flush On", EnumSet.allOf(FlushOn.class));

    private final BoolSetting pauseOnUse = boolSetting("Pause On Use", true);

    // -- Runtime state --

    private long nextDelayMs;
    private long lastFlushTime;
    private boolean hasEnemyNearby;
    private Vec3 serverPosition = Vec3.ZERO;
    private final Random random = new Random();

    // -- Lifecycle --

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
        resetState();
    }

    private void resetState() {
        nextDelayMs = getRandomDelay();
        lastFlushTime = 0;
        serverPosition = Vec3.ZERO;
        hasEnemyNearby = false;
    }

    // -- Tick handler (safety + enemy caching) --

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;

        // Safety: never lag when dead, in water, or in GUI
        if (mc.player.isDeadOrDying() || mc.player.isInWater() || mc.gui.screen() != null) {
            ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
            return;
        }

        // Update enemy proximity cache for Dynamic mode (once per tick)
        updateEnemyCheck();
    }

    // -- BlinkPacketEvent: vote on what to do with each packet --

    @EventHandler
    private void onBlinkPacket(BlinkPacketEvent event) {
        if (nullCheck()) return;

        // Only handle outgoing packets
        if (event.getOrigin() != TransferOrigin.OUTGOING) return;

        // Safety: dead / water / GUI → let BlinkManager flush
        if (mc.player.isDeadOrDying() || mc.player.isInWater() || mc.gui.screen() != null) {
            return; // action stays FLUSH
        }

        Packet<?> packet = event.getPacket();

        // Periodic null-packet tick: check window-based time expiry
        if (packet == null) {
            if (ServerboundPacketManager.INSTANCE.isAboveTime(nextDelayMs)) {
                nextDelayMs = getRandomDelay();
                return; // action stays FLUSH → BlinkManager flushes
            }
            return;
        }

        // FlushOn: these packets trigger flush + reset recoil, pass through
        if (shouldFlushOn(packet)) {
            lastFlushTime = System.currentTimeMillis();
            return; // action stays FLUSH → BlinkManager flushes
        }

        // Pause during item consumption
        if (pauseOnUse.getValue() && mc.player.isUsingItem()) {
            return;
        }

        // Recoil time check
        long now = System.currentTimeMillis();
        if (now - lastFlushTime < recoilTime.getValue()) {
            return;
        }

        // -- Mode-specific logic --

        if (mode.is(Mode.Dynamic)) {
            if (!hasEnemyNearby) {
                return;
            }

            // Track server position from the oldest queued position packet
            if (serverPosition.equals(Vec3.ZERO)) {
                serverPosition = getFirstBlinkPosition();
            }

            // Check if lagging is still beneficial
            if (!serverPosition.equals(Vec3.ZERO) && shouldStopLagging()) {
                nextDelayMs = getRandomDelay();
                serverPosition = Vec3.ZERO;
                return; // action stays FLUSH → BlinkManager flushes
            }
        }

        // Constant mode always queues when active, Dynamic mode queues when
        // enemy is nearby and lagging is beneficial
        event.setAction(Action.QUEUE);
    }

    // -- Packet receive (safety flush) --

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;

        Packet<?> packet = event.getPacket();

        // Flush on teleport / position sync
        if (packet instanceof ClientboundPlayerPositionPacket) {
            ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
            return;
        }

        // Flush on respawn
        if (packet instanceof ClientboundRespawnPacket) {
            ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
            return;
        }

        // Flush on knockback (velocity change for our player)
        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket
                && motionPacket.id() == mc.player.getId()
                && !motionPacket.movement().equals(Vec3.ZERO)) {
            ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
            return;
        }

        // Flush on explosion knockback
        if (packet instanceof ClientboundExplodePacket explodePacket) {
            if (explodePacket.playerKnockback().isPresent()
                    && !explodePacket.playerKnockback().get().equals(Vec3.ZERO)) {
                ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
                return;
            }
        }

        // Flush on damage / health change
        if (packet instanceof ClientboundSetHealthPacket) {
            ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
        }
    }

    // -- Game state events --

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
        resetState();
    }

    @EventHandler
    private void onRespawn(RespawnEvent event) {
        ServerboundPacketManager.INSTANCE.flush(TransferOrigin.OUTGOING);
        resetState();
    }

    // -- Packet classification --

    private boolean shouldFlushOn(Packet<?> packet) {
        Set<FlushOn> triggers = flushOn.getValue();

        if (triggers.contains(FlushOn.EntityInteract)) {
            if (packet instanceof ServerboundInteractPacket
                    || packet instanceof ServerboundAttackPacket
                    || packet instanceof ServerboundSpectatorActionPacket
                    || packet instanceof ServerboundSwingPacket) {
                return true;
            }
        }

        if (triggers.contains(FlushOn.BlockInteract)) {
            if (packet instanceof ServerboundUseItemOnPacket
                    || packet instanceof ServerboundUseItemPacket) {
                return true;
            }
        }

        if (triggers.contains(FlushOn.Action)) {
            if (packet instanceof ServerboundPlayerActionPacket) {
                return true;
            }
        }

        return false;
    }

    // -- Dynamic mode helpers --

    private Vec3 getFirstBlinkPosition() {
        var first = ServerboundPacketManager.INSTANCE.packetQueue.peek();
        if (first != null && first.packet() instanceof ServerboundMovePlayerPacket mp
                && mp.hasPosition()) {
            return mc.player.position();
        }
        return Vec3.ZERO;
    }

    private void updateEnemyCheck() {
        LivingEntity enemy = Managers.TARGET.acquirePrimary(TargetRequest.of(
                range.getValue(),
                360f,
                ClientSetting.INSTANCE.targetPlayer.getValue(),
                ClientSetting.INSTANCE.targetMob.getValue(),
                ClientSetting.INSTANCE.targetAnimal.getValue(),
                ClientSetting.INSTANCE.targetVillager.getValue(),
                ClientSetting.INSTANCE.targetInvisible.getValue(),
                1
        ));
        hasEnemyNearby = enemy != null;
    }

    /**
     * Compare the server-side position (what enemies see) against the real
     * client position. If the real position is closer to enemies — or the
     * server position intersects an enemy hitbox — then lagging is hurting
     * us and we should flush.
     */
    private boolean shouldStopLagging() {
        if (serverPosition.equals(Vec3.ZERO)) {
            return true;
        }

        Vec3 playerPos = mc.player.position();
        AABB serverBox = mc.player.getBoundingBox().move(
                serverPosition.x - playerPos.x,
                serverPosition.y - playerPos.y,
                serverPosition.z - playerPos.z
        );

        for (var entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.isAlive() || living.isDeadOrDying()) continue;

            double serverDistSq = living.position().distanceToSqr(serverPosition);
            double clientDistSq = living.position().distanceToSqr(playerPos);

            // Real (client) position closer to enemy → stop lagging
            if (clientDistSq < serverDistSq) {
                return true;
            }

            // Server-side bounding box intersects enemy → stop lagging
            if (serverBox.intersects(living.getBoundingBox())) {
                return true;
            }
        }

        return false;
    }

    // -- Utilities --

    private long getRandomDelay() {
        int min = Math.min(delayMin.getValue(), delayMax.getValue());
        int max = Math.max(delayMin.getValue(), delayMax.getValue());
        return min + random.nextInt(Math.max(1, max - min + 1));
    }

}
