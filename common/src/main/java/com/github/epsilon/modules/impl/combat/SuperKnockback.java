package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.AttackEntityEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.settings.impl.MultiEnumSetting;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * Super Knockback 战斗模块。
 *
 * 通过操纵冲刺状态来增加对目标实体的击退效果。
 * 参考 LiquidBounce 的 ModuleSuperKnockback 实现。
 */
public class SuperKnockback extends Module {

    public static final SuperKnockback INSTANCE = new SuperKnockback();

    private SuperKnockback() {
        super("Super Knockback", Category.COMBAT);
    }

    // -- Modes --

    private enum Mode {
        Packet,
        SprintTap
    }

    private enum Condition {
        OnlyFacing,
        OnlyOnGround,
        NotInWater
    }

    // -- General settings --

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Packet);

    private final IntSetting hurtTime = intSetting("Hurt Time", 10, 0, 10, 1);
    private final IntSetting chance = intSetting("Chance", 100, 0, 100, 1);

    private final MultiEnumSetting<Condition> conditions = multiEnumSetting("Conditions", EnumSet.of(Condition.NotInWater));

    // -- Only On Move group --

    private final BoolSetting onlyOnMove = boolSetting("Only On Move", true);
    private final BoolSetting onlyForward = boolSetting("Only Forward", true, onlyOnMove::getValue);

    // -- SprintTap settings --

    private final IntSetting reSprintMin = intSetting("ReSprint Min", 0, 0, 10, 1, () -> mode.is(Mode.SprintTap));
    private final IntSetting reSprintMax = intSetting("ReSprint Max", 1, 0, 10, 1, () -> mode.is(Mode.SprintTap));

    // -- Internal state --

    private boolean cancelSprint;
    private TapState tapState = TapState.IDLE;
    private int tapTickCounter;
    private final Random random = new Random();

    private enum TapState {
        IDLE,
        WAITING_STOP,
        WAITING_RESPRINT
    }

    // -- Lifecycle --

    @Override
    protected void onDisable() {
        cancelSprint = false;
        tapState = TapState.IDLE;
        tapTickCounter = 0;
    }

    // -- Tick handler (for SprintTap state machine) --

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck() || !mode.is(Mode.SprintTap) || tapState == TapState.IDLE) return;
        handleSprintTapTick();
    }

    // -- Attack handler --

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (nullCheck()) return;

        Entity target = event.getEntity();
        if (!shouldOperate(target)) return;

        // Hurt time check — only apply when enemy hurt time is low
        if (target instanceof LivingEntity living && living.hurtTime > hurtTime.getValue()) return;

        // Chance roll
        if (random.nextInt(100) >= chance.getValue()) return;

        // Skip during critical hits — player is airborne with fall distance
        if (mc.player.fallDistance > 0.0f && !mc.player.onGround() && !mc.player.isInWater()) return;

        switch (mode.getValue()) {
            case Packet -> doPacketMode();
            case SprintTap -> doSprintTap();
        }
    }

    // -- Packet mode --

    private void doPacketMode() {
        if (mc.getConnection() == null) return;

        boolean wasSprinting = mc.player.isSprinting();

        if (wasSprinting) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }

        // Rapid sprint toggle to maximize knockback
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));

        // Sync local state
        mc.player.setSprinting(true);
        mc.player.wasSprinting = true;
    }

    // -- SprintTap mode --

    private void doSprintTap() {
        if (!mc.player.isSprinting() || !mc.player.wasSprinting) return;
        if (tapState != TapState.IDLE) return;

        tapState = TapState.WAITING_STOP;
        cancelSprint = true;
        tapTickCounter = 0;
    }

    private void handleSprintTapTick() {
        switch (tapState) {
            case WAITING_STOP -> {
                if (!mc.player.isSprinting() && !mc.player.wasSprinting) {
                    tapState = TapState.WAITING_RESPRINT;
                    tapTickCounter = 0;
                }
            }
            case WAITING_RESPRINT -> {
                int min = Math.min(reSprintMin.getValue(), reSprintMax.getValue());
                int max = Math.max(reSprintMin.getValue(), reSprintMax.getValue());
                int delay = min + random.nextInt(Math.max(1, max - min + 1));

                tapTickCounter++;
                if (tapTickCounter >= delay) {
                    tapState = TapState.IDLE;
                    cancelSprint = false;
                }
            }
        }
    }

    // -- Input handler (blocks sprint during SprintTap sequence) --

    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (cancelSprint && mode.is(Mode.SprintTap)) {
            event.setSprint(false);
        }
    }

    // -- Condition checks --

    private boolean shouldOperate(Entity target) {
        Set<Condition> conds = conditions.getValue();

        // OnlyFacing: enemy must be looking away from the player
        if (conds.contains(Condition.OnlyFacing)) {
            if (target.getLookAngle().dot(mc.player.position().subtract(target.position())) >= 0) {
                return false;
            }
        }

        // OnlyOnGround: player must be on the ground
        if (conds.contains(Condition.OnlyOnGround) && !mc.player.onGround()) {
            return false;
        }

        // NotInWater: player must not be in water
        if (conds.contains(Condition.NotInWater) && mc.player.isInWater()) {
            return false;
        }

        // OnlyOnMove: player must be providing movement input
        if (onlyOnMove.getValue()) {
            boolean hasForward = mc.player.input.keyPresses.forward() || mc.player.input.keyPresses.backward();
            boolean hasStrafe = mc.player.input.keyPresses.left() || mc.player.input.keyPresses.right();

            if (!hasForward && !hasStrafe) return false;
            if (onlyForward.getValue() && hasStrafe && !hasForward) return false;
        }

        return true;
    }

}
