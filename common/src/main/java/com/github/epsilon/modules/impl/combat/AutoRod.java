package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.managers.Managers;
import com.github.epsilon.managers.impl.target.TargetRequest;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Auto Rod 战斗模块。
 *
 * 自动投掷鱼竿攻击附近的敌人，用于连击中的击退控制。
 * 参考 LiquidBounce 的 ModuleAutoRod 实现。
 */
public class AutoRod extends Module {

    public static final AutoRod INSTANCE = new AutoRod();

    private AutoRod() {
        super("Auto Rod", Category.COMBAT);
    }

    // -- Settings --

    private final DoubleSetting range = doubleSetting("Range", 5.0, 2.0, 10.0, 0.5);

    private final IntSetting hitTimeout = intSetting("Hit Timeout", 30, 5, 200, 5);

    private final BoolSetting pullOnOutOfRange = boolSetting("Pull On Out Of Range", true);

    private final IntSetting cooldownMin = intSetting("Cooldown Min", 4, 1, 50, 1);
    private final IntSetting cooldownMax = intSetting("Cooldown Max", 8, 1, 50, 1);

    private final IntSetting chance = intSetting("Chance", 100, 0, 100, 1);

    private final BoolSetting swingHand = boolSetting("Swing Hand", true);

    // -- Internal state --

    private enum State {
        IDLE,
        CAST,
        WAITING,
        PULL,
        COOLDOWN
    }

    private State state = State.IDLE;
    private int tickCounter;
    private int cooldownTicks;
    private LivingEntity target;
    private final Random random = new Random();

    // -- Lifecycle --

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        // Pull back any active bobber we cast
        if (target != null && hasActiveBobber()) {
            pullRod();
        }
        resetState();
    }

    private void resetState() {
        state = State.IDLE;
        tickCounter = 0;
        cooldownTicks = 0;
        target = null;
    }

    // -- Main tick --

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) {
            resetState();
            return;
        }

        switch (state) {
            case IDLE -> handleIdle();
            case CAST -> handleCast();
            case WAITING -> handleWaiting();
            case PULL -> handlePull();
            case COOLDOWN -> handleCooldown();
        }
    }

    // -- State handlers --

    private void handleIdle() {
        // Chance roll
        if (random.nextInt(100) >= chance.getValue()) return;

        // Find target
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

        if (target == null) return;
        if (!target.isAlive() || target.isDeadOrDying()) {
            target = null;
            return;
        }

        // Don't cast if already have an active bobber
        if (hasActiveBobber()) return;

        // Check we have a rod
        if (InvUtils.findInHotbar(stack -> stack.getItem() instanceof FishingRodItem).slot() < 0) {
            return;
        }

        // Check line of sight
        if (!mc.player.hasLineOfSight(target)) return;

        state = State.CAST;
        tickCounter = 0;
    }

    private void handleCast() {
        if (target == null || !target.isAlive()) {
            state = State.IDLE;
            return;
        }

        // Rotate towards target
        Rot2f rotation = RotationUtils.getRotationsToEntity(target);
        Managers.ROTATION.setRotations(rotation, 10, com.github.epsilon.utils.rotation.Priority.High);

        // Switch to rod
        var rodSlot = InvUtils.findInHotbar(stack -> stack.getItem() instanceof FishingRodItem);
        if (rodSlot.slot() < 0) {
            state = State.IDLE;
            return;
        }

        // Silent swap to rod slot
        int slot = rodSlot.slot();
        InteractionHand hand = slot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean swapped = false;

        if (slot != 40 && slot != mc.player.getInventory().getSelectedSlot()) {
            InvUtils.swap(slot, true);
            swapped = true;
        }

        // Cast the rod
        mc.gameMode.useItem(mc.player, hand);

        // Swing
        if (swingHand.getValue()) {
            mc.player.swing(hand);
        } else {
            mc.getConnection().send(new ServerboundSwingPacket(hand));
        }

        state = State.WAITING;
        tickCounter = 0;
    }

    private void handleWaiting() {
        tickCounter++;

        if (target == null || !target.isAlive()) {
            state = State.IDLE;
            return;
        }

        FishingHook bobber = getBobber();
        if (bobber == null) {
            // Bobber disappeared (pulled by server or terrain)
            state = State.COOLDOWN;
            cooldownTicks = getRandomCooldown();
            return;
        }

        // Condition 1: hooked an entity
        if (bobber.getHookedIn() != null) {
            state = State.PULL;
            return;
        }

        // Condition 2: bobber stopped moving (hit terrain)
        if (bobber.getKnownMovement() != null && bobber.getKnownMovement().equals(Vec3.ZERO)) {
            state = State.PULL;
            return;
        }

        // Condition 3: target went out of range
        if (pullOnOutOfRange.getValue()) {
            double dist = RotationUtils.getEyeDistanceToEntity(target);
            if (dist > range.getValue()) {
                state = State.PULL;
                return;
            }
        }

        // Condition 4: timeout
        if (tickCounter >= hitTimeout.getValue()) {
            state = State.PULL;
        }
    }

    private void handlePull() {
        // Pull the rod (useItem while bobber is out = pull)
        pullRod();

        state = State.COOLDOWN;
        cooldownTicks = getRandomCooldown();
    }

    private void handleCooldown() {
        tickCounter++;
        if (tickCounter >= cooldownTicks) {
            // Reset swap if needed
            InvUtils.swapBack();
            state = State.IDLE;
            tickCounter = 0;
        }
    }

    // -- Helpers --

    private boolean hasActiveBobber() {
        return getBobber() != null;
    }

    private FishingHook getBobber() {
        if (mc.level == null || mc.player == null) return null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof FishingHook hook && hook.getPlayerOwner() == mc.player) {
                return hook;
            }
        }
        return null;
    }

    private void pullRod() {
        if (mc.getConnection() == null) return;

        var rodSlot = InvUtils.findInHotbar(stack -> stack.getItem() instanceof FishingRodItem);
        int slot = rodSlot.slot();
        InteractionHand hand = slot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

        if (slot >= 0 && slot != 40 && slot != mc.player.getInventory().getSelectedSlot()) {
            InvUtils.swap(slot, true);
        }

        mc.gameMode.useItem(mc.player, hand);

        if (swingHand.getValue()) {
            mc.player.swing(hand);
        } else {
            mc.getConnection().send(new ServerboundSwingPacket(hand));
        }
    }

    private int getRandomCooldown() {
        int min = Math.min(cooldownMin.getValue(), cooldownMax.getValue());
        int max = Math.max(cooldownMin.getValue(), cooldownMax.getValue());
        return min + random.nextInt(Math.max(1, max - min + 1));
    }

}
