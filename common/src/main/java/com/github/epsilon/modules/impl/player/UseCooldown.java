package com.github.epsilon.modules.impl.player;

import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.settings.impl.MultiEnumSetting;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileItem;

import java.util.EnumSet;
import java.util.Set;

public class UseCooldown extends Module {

    public static final UseCooldown INSTANCE = new UseCooldown();

    private UseCooldown() {
        super("Use Cooldown", Category.PLAYER);
    }

    public final IntSetting cooldown = intSetting("Cooldown", 0, 0, 4, 1);

    public enum ApplyTo {
        Blocks,
        Projectiles,
        Both
    }

    public final MultiEnumSetting<ApplyTo> applyTo = multiEnumSetting("Apply To", EnumSet.of(ApplyTo.Blocks));

    public final IntSetting startDelay = intSetting("Start Delay", 0, 0, 1000, 50);

    /**
     * Checks whether the cooldown override should apply to the given item stack.
     */
    public boolean shouldApplyTo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        Set<ApplyTo> modes = applyTo.getValue();
        if (modes.contains(ApplyTo.Both)) return true;

        Item item = stack.getItem();
        if (modes.contains(ApplyTo.Blocks) && item instanceof BlockItem) return true;
        return modes.contains(ApplyTo.Projectiles) && item instanceof ProjectileItem;
    }

}
