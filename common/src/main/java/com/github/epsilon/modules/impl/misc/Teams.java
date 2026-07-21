package com.github.epsilon.modules.impl.misc;

import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.BoolSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.network.chat.TextColor;

/**
 * Teams module — prevents targeting players on your own team.
 * Detects teammates via scoreboard teams, name color, and armor color.
 * Ported from LiquidBounce (CCBlueX/LiquidBounce).
 */
public class Teams extends Module {

    public static final Teams INSTANCE = new Teams();

    // --- Match methods ---
    private final BoolSetting scoreboardTeam = boolSetting("Scoreboard Team", true);
    private final BoolSetting nameColor = boolSetting("Name Color", true);
    private final BoolSetting armorBool = boolSetting("Armor", false);
    private final SettingGroup armorColor = settingGroup("Armor Color");

    // --- Armor slots to check ---
    private final BoolSetting checkHead = boolSetting("Check Head", true, armorBool::getValue).group(armorColor);
    private final BoolSetting checkChest = boolSetting("Check Chest", true, armorBool::getValue).group(armorColor);
    private final BoolSetting checkLegs = boolSetting("Check Legs", true, armorBool::getValue).group(armorColor);
    private final BoolSetting checkFeet = boolSetting("Check Feet", true, armorBool::getValue).group(armorColor);

    private Teams() {
        super("Teams", Category.MISC);
    }

    /**
     * Check if an entity is on the same team as the local player.
     * Called by other modules (KillAura, etc.) to filter targets.
     */
    public boolean isInYourTeam(Entity entity) {
        if (!isEnabled()) return false;
        if (entity == mc.player) return false;
        if (mc.player == null) return false;

        // Scoreboard team check
        if (scoreboardTeam.getValue() && checkScoreboardTeam(entity)) {
            return true;
        }

        // Name color check
        if (nameColor.getValue() && entity instanceof Player player && checkNameColor(player)) {
            return true;
        }

        // Armor color check
        if (armorBool.getValue() && entity instanceof Player player && checkArmorColor(player)) {
            return true;
        }

        return false;
    }

    /**
     * Quick static access for other modules.
     */
    public static boolean isTeam(Entity entity) {
        return INSTANCE.isInYourTeam(entity);
    }

    // --- Detection methods ---

    /**
     * Checks if the entity shares the same scoreboard team as the local player.
     */
    private boolean checkScoreboardTeam(Entity entity) {
        PlayerTeam playerTeam = mc.player.getTeam();
        if (playerTeam == null) return false;

        return entity.isAlliedTo(playerTeam);
    }

    /**
     * Checks if the entity's display name has the same color as the local player's display name.
     * This catches servers that use colored name prefixes for teams without scoreboard teams.
     */
    private boolean checkNameColor(Player player) {
        TextColor myColor = mc.player.getDisplayName().getStyle().getColor();
        TextColor theirColor = player.getDisplayName().getStyle().getColor();

        if (myColor == null || theirColor == null) return false;

        return myColor.equals(theirColor);
    }

    /**
     * Checks if the entity is wearing leather armor dyed the same color as the local player.
     * Disabled by default to avoid false positives from undyed leather armor.
     */
    private boolean checkArmorColor(Player player) {
        EquipmentSlot[] slotsToCheck = getEnabledArmorSlots();
        if (slotsToCheck.length == 0) return false;

        for (EquipmentSlot slot : slotsToCheck) {
            ItemStack myArmor = mc.player.getItemBySlot(slot);
            ItemStack theirArmor = player.getItemBySlot(slot);

            int myDye = DyedItemColor.getOrDefault(myArmor, -1);
            int theirDye = DyedItemColor.getOrDefault(theirArmor, -2);

            // Only match if both have dyed armor (skip undyed/non-leather with different defaults)
            if (myDye >= 0 && myDye == theirDye) {
                return true;
            }
        }

        return false;
    }

    private EquipmentSlot[] getEnabledArmorSlots() {
        java.util.List<EquipmentSlot> slots = new java.util.ArrayList<>(4);
        if (checkHead.getValue()) slots.add(EquipmentSlot.HEAD);
        if (checkChest.getValue()) slots.add(EquipmentSlot.CHEST);
        if (checkLegs.getValue()) slots.add(EquipmentSlot.LEGS);
        if (checkFeet.getValue()) slots.add(EquipmentSlot.FEET);
        return slots.toArray(new EquipmentSlot[0]);
    }
}
