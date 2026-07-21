package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.GameLeftEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.RespawnEvent;
import com.github.epsilon.managers.Managers;
import com.github.epsilon.managers.impl.target.TargetRequest;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.settings.impl.MultiEnumSetting;
import com.github.epsilon.utils.player.PlayerUtils;
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
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Fake Lag 战斗模块。
 *
 * 故意延迟发出出站包来模拟网络延迟，让敌人难以命中玩家的真实位置。
 * 参考 LiquidBounce 的 ModuleFakeLag 实现。
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

    private final ConcurrentLinkedQueue<QueuedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    private long nextDelayMs;
    private long lastFlushTime;
    private Vec3 lagStartPosition = Vec3.ZERO;
    private boolean hasEnemyNearby;
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
        packetQueue.clear();
        nextDelayMs = getRandomDelay();
        lastFlushTime = 0;
        lagStartPosition = Vec3.ZERO;
        hasEnemyNearby = false;
    }

    // -- Tick handler --

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;

        // Safety: never lag when dead, in water, or in GUI
        if (mc.player.isDeadOrDying() || mc.player.isInWater() || mc.gui.screen() != null) {
            flushAll();
            return;
        }

        // Safety: pause during item consumption
        if (pauseOnUse.getValue() && mc.player.isUsingItem()) {
            return;
        }

        // Recoil time check
        long now = System.currentTimeMillis();
        if (now - lastFlushTime < recoilTime.getValue()) {
            return;
        }

        // Check for enemies nearby (for Dynamic mode)
        updateEnemyCheck();

        // Release expired packets
        flushExpired(now);
    }

    // -- Packet send interceptor --

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (nullCheck()) return;

        Packet<?> packet = event.getPacket();

        // Never delay login/handshake packets
        if (packet instanceof ClientIntentionPacket || packet instanceof ServerboundHelloPacket) {
            return;
        }

        // Never delay chat or sign packets (they're informational)
        if (packet instanceof ServerboundSignUpdatePacket) {
            return;
        }

        // FlushOn: always send these immediately and reset recoil timer
        if (shouldFlushOn(packet)) {
            flushAll();
            lastFlushTime = System.currentTimeMillis();
            return;
        }

        // Only queue movement-related packets
        if (!isDelayablePacket(packet)) {
            return;
        }

        // Dynamic mode: only lag when enemy is nearby
        if (mode.is(Mode.Dynamic) && !hasEnemyNearby) {
            return;
        }

        // Track the position where lag started
        if (lagStartPosition.equals(Vec3.ZERO) && packet instanceof ServerboundMovePlayerPacket movePacket
                && movePacket.hasPosition()) {
            // We can't easily extract position from the packet, so use current position
            lagStartPosition = mc.player.position();
        }

        // Check if we should still be lagging (Dynamic mode distance check)
        if (mode.is(Mode.Dynamic) && hasEnemyNearby && !lagStartPosition.equals(Vec3.ZERO)) {
            if (shouldStopLagging()) {
                flushAll();
                lagStartPosition = Vec3.ZERO;
                nextDelayMs = getRandomDelay();
                return;
            }
        }

        // Queue the packet
        event.cancel();
        packetQueue.add(new QueuedPacket(System.currentTimeMillis(), packet));
    }

    // -- Packet receive (safety flush) --

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;

        Packet<?> packet = event.getPacket();

        // Flush on teleport / position sync
        if (packet instanceof ClientboundPlayerPositionPacket) {
            flushAll();
            return;
        }

        // Flush on respawn
        if (packet instanceof ClientboundRespawnPacket) {
            flushAll();
            return;
        }

        // Flush on knockback (velocity change for player)
        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket
                && motionPacket.id() == mc.player.getId()
                && !motionPacket.movement().equals(Vec3.ZERO)) {
            flushAll();
            return;
        }

        // Flush on explosion knockback
        if (packet instanceof ClientboundExplodePacket explodePacket) {
            if (explodePacket.playerKnockback().isPresent()
                    && !explodePacket.playerKnockback().get().equals(Vec3.ZERO)) {
                flushAll();
                return;
            }
        }

        // Flush on damage
        if (packet instanceof ClientboundSetHealthPacket) {
            flushAll();
        }
    }

    // -- Game state events --

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

    // -- Packet classification --

    private boolean isDelayablePacket(Packet<?> packet) {
        return packet instanceof ServerboundMovePlayerPacket
                || packet instanceof ServerboundPlayerInputPacket
                || packet instanceof ServerboundPlayerCommandPacket;
    }

    private boolean shouldFlushOn(Packet<?> packet) {
        Set<FlushOn> triggers = flushOn.getValue();

        if (triggers.contains(FlushOn.EntityInteract)) {
            if (packet instanceof ServerboundInteractPacket
                    || packet instanceof ServerboundAttackPacket
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

    // -- Flush logic --

    private void flushExpired(long now) {
        QueuedPacket queued;
        while ((queued = packetQueue.peek()) != null && now - queued.timestamp >= nextDelayMs) {
            packetQueue.poll();
            sendPacket(queued.packet);
        }

        // If all packets flushed, reset for next cycle
        if (packetQueue.isEmpty()) {
            lagStartPosition = Vec3.ZERO;
            nextDelayMs = getRandomDelay();
        }
    }

    private void flushAll() {
        QueuedPacket queued;
        while ((queued = packetQueue.poll()) != null) {
            sendPacket(queued.packet);
        }
        lagStartPosition = Vec3.ZERO;
        nextDelayMs = getRandomDelay();
    }

    private void sendPacket(Packet<?> packet) {
        if (mc.getConnection() == null) return;
        try {
            mc.getConnection().send(packet);
        } catch (Exception ignored) {
        }
    }

    // -- Dynamic mode helpers --

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

    private boolean shouldStopLagging() {
        // If our current position is closer to enemies than the lag position,
        // it's disadvantageous to keep lagging — flush instead
        if (lagStartPosition.equals(Vec3.ZERO)) return true;

        // Check if any enemy is very close to the current (real) position
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living
                    && living != mc.player
                    && living.isAlive()
                    && !living.isDeadOrDying()) {
                double distToReal = living.position().distanceTo(mc.player.position());
                double distToFake = living.position().distanceTo(lagStartPosition);

                // If real position is closer to the enemy, stop lagging (flush)
                if (distToReal < distToFake) {
                    return true;
                }

                // If enemy is intersecting our real hitbox, stop lagging
                if (living.getBoundingBox().intersects(mc.player.getBoundingBox())) {
                    return true;
                }
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
