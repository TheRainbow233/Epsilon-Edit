package com.github.epsilon.modules.impl.player;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.utils.player.EnchantmentUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class AutoTool extends Module {

    public static final AutoTool INSTANCE = new AutoTool();

    private AutoTool() {
        super("Auto Tool", Category.PLAYER);
    }

    private final BoolSetting swapBack = boolSetting("Swap Back", true);
    private final BoolSetting saveItem = boolSetting("Save Item", true);
    private final BoolSetting silent = boolSetting("Silent", false);
    private final BoolSetting echestSilk = boolSetting("Ender Chest Silk Touch", true);

    private int bestSlot = -1;
    private int prevSlot = -1;
    private boolean swapped;
    private BlockPos lastTargetPos;

    @EventHandler
    private void onClientTick(PlayerTickEvent.Pre event) {
        if (!(mc.hitResult instanceof BlockHitResult result)) {
            trySwapBack();
            return;
        }

        BlockPos pos = result.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {
            trySwapBack();
            return;
        }

        boolean attacking = mc.options.keyAttack.isDown();

        // Attack just released → swap back
        if (!attacking && swapped) {
            trySwapBack();
            return;
        }

        // Not attacking → nothing to do
        if (!attacking) return;

        // Only re-evaluate when target block changes (avoids redundant checks every tick)
        if (swapped && pos.equals(lastTargetPos)) return;

        // Find best tool
        int tool = getTool(pos, state);
        if (tool == -1 || tool == mc.player.getInventory().getSelectedSlot()) return;

        // Save current slot before first swap in this mining session
        if (!swapped) {
            prevSlot = mc.player.getInventory().getSelectedSlot();
        }

        // Switch to best tool
        if (silent.getValue()) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(tool));
        } else {
            mc.player.getInventory().setSelectedSlot(tool);
        }

        bestSlot = tool;
        lastTargetPos = pos;
        swapped = true;
    }

    private void trySwapBack() {
        if (!swapped || !swapBack.getValue()) {
            resetState();
            return;
        }

        if (silent.getValue()) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(prevSlot));
        } else {
            mc.player.getInventory().setSelectedSlot(prevSlot);
        }

        resetState();
    }

    private void resetState() {
        swapped = false;
        bestSlot = -1;
        prevSlot = -1;
        lastTargetPos = null;
    }

    /**
     * Returns the hotbar slot index of the best tool for the given block position.
     * Public single-parameter overload for external callers like Phase.
     */
    public int getTool(BlockPos pos) {
        if (mc.level == null) return -1;
        return getTool(pos, mc.level.getBlockState(pos));
    }

    /**
     * Returns the hotbar slot index of the best tool for the given block,
     * or -1 if no better tool is found.
     */
    private int getTool(BlockPos pos, BlockState state) {
        int index = -1;
        float bestScore = 1.0f;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            // Skip nearly-broken tools when saveItem is on
            if (saveItem.getValue()) {
                int maxDmg = stack.getMaxDamage();
                if (maxDmg > 0) {
                    int remaining = maxDmg - stack.getDamageValue();
                    if (remaining <= 10) continue;
                }
            }

            float destroySpeed = stack.getDestroySpeed(state);
            // Tool can't mine this block properly
            if (destroySpeed <= 1.0f) continue;

            // Ender chest requires silk touch
            if (state.getBlock() instanceof EnderChestBlock && echestSilk.getValue()) {
                if (EnchantmentUtils.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH) == 0) continue;
            }

            // Efficiency bonus: Minecraft uses efficiency² + 1, not flat level
            int effLevel = EnchantmentUtils.getEnchantmentLevel(stack, Enchantments.EFFICIENCY);
            float effBonus = effLevel > 0 ? effLevel * effLevel + 1 : 0;

            float score = destroySpeed + effBonus;
            if (score > bestScore) {
                bestScore = score;
                index = i;
            }
        }
        return index;
    }

    public int getBestSlot() {
        return bestSlot;
    }

    @Override
    protected void onDisable() {
        trySwapBack();
    }
}
